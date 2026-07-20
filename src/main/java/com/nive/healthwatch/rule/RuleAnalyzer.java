package com.nive.healthwatch.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nive.healthwatch.collect.CollectResult;
import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.AnomalyEvent;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.HealthDbSnapshot;
import com.nive.healthwatch.domain.HealthSnapshot;
import com.nive.healthwatch.domain.enums.AnomalyStatus;
import com.nive.healthwatch.domain.enums.HealthStatus;
import com.nive.healthwatch.domain.enums.Severity;
import com.nive.healthwatch.domain.repository.AnomalyEventRepository;
import com.nive.healthwatch.domain.repository.HealthDbSnapshotRepository;
import com.nive.healthwatch.domain.repository.HealthSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * @author nive
 * @class RuleAnalyzer
 * @desc 숫자 기반 rule 로 이상 후보를 판단한다. AI 는 여기서 판정이 끝난 뒤에만 붙는다.
 *       cooldown(동일 service+trigger+db)과 resolved 복구 감지를 함께 처리한다.
 * @since 2026-07-06
 */
@Slf4j
@Service
public class RuleAnalyzer {

    private final HealthWatchProperties props;
    private final AnomalyEventRepository anomalyRepo;
    private final HealthDbSnapshotRepository dbSnapshotRepo;
    private final HealthSnapshotRepository snapshotRepo;
    private final ObjectMapper objectMapper;

    public RuleAnalyzer(HealthWatchProperties props, AnomalyEventRepository anomalyRepo,
                        HealthDbSnapshotRepository dbSnapshotRepo, HealthSnapshotRepository snapshotRepo,
                        ObjectMapper objectMapper) {
        this.props = props;
        this.anomalyRepo = anomalyRepo;
        this.dbSnapshotRepo = dbSnapshotRepo;
        this.snapshotRepo = snapshotRepo;
        this.objectMapper = objectMapper;
    }

    public RuleResult evaluate(MonitoredService service, CollectResult collected) {
        HealthSnapshot snap = collected.snapshot();
        List<Trigger> triggers = detect(service, snap, collected.dbSnapshots());

        LocalDateTime now = LocalDateTime.now();
        List<AnomalyEvent> toAlert = new ArrayList<>();
        Set<String> firedKeys = new HashSet<>();

        for (Trigger t : triggers) {
            firedKeys.add(t.key());
            Optional<AnomalyEvent> latest = findLatest(service.getName(), t.triggerType(), t.dbName());
            AnomalyEvent event = latest.filter(this::isOngoing).orElseGet(AnomalyEvent::new);

            boolean isNew = event.getId() == null || !isOngoing(event);
            if (isNew) {
                event = new AnomalyEvent();
                event.setServiceName(service.getName());
                event.setTriggerType(t.triggerType());
                event.setDbName(t.dbName());
                event.setFirstDetectedAt(now);
                event.setStatus(AnomalyStatus.OPEN.code());
            }
            event.setSeverity(t.severity().code());
            event.setTriggerSummary(t.summary());
            event.setBaselineJson(t.baselineJson());
            event.setCurrentJson(t.currentJson());
            event.setLastDetectedAt(now);

            boolean notify = isNew || cooldownElapsed(event, t.severity(), now);
            if (notify) {
                event.setStatus(AnomalyStatus.NOTIFIED.code());
                event.setLastNotifiedAt(now);
            }
            AnomalyEvent saved = anomalyRepo.save(event);
            if (notify) {
                toAlert.add(saved);
            }
        }

        List<AnomalyEvent> resolved = resolveDisappeared(service.getName(), firedKeys, now);
        return new RuleResult(toAlert, resolved);
    }

    /** 이전에 열려있던(OPEN/NOTIFIED) 이벤트 중 이번에 안 잡힌 것 → RESOLVED + 복구 알림 대상. */
    private List<AnomalyEvent> resolveDisappeared(String serviceName, Set<String> firedKeys, LocalDateTime now) {
        List<AnomalyEvent> open = anomalyRepo.findByServiceNameAndStatusIn(
                serviceName, List.of(AnomalyStatus.OPEN.code(), AnomalyStatus.NOTIFIED.code()));
        List<AnomalyEvent> resolved = new ArrayList<>();
        for (AnomalyEvent e : open) {
            String key = e.getTriggerType() + "|" + (e.getDbName() == null ? "" : e.getDbName());
            if (!firedKeys.contains(key)) {
                e.setStatus(AnomalyStatus.RESOLVED.code());
                e.setLastDetectedAt(now);
                resolved.add(anomalyRepo.save(e));
            }
        }
        return resolved;
    }

    private List<Trigger> detect(MonitoredService service, HealthSnapshot snap,
                                 List<HealthDbSnapshot> dbs) {
        List<Trigger> triggers = new ArrayList<>();
        String status = snap.getStatus();

        // timeout — 단발 blip 오탐 방지를 위해 '연속 N회' 일 때만 알린다(디바운스).
        if (HealthStatus.TIMEOUT.code().equals(status)) {
            int threshold = Math.max(1, props.getRule().getTimeoutConsecutiveThreshold());
            int streak = consecutiveTimeoutStreak(service.getName(), threshold);
            if (streak >= threshold) {
                triggers.add(Trigger.of("timeout", null, Severity.CRITICAL,
                        timeoutSummary(snap) + " (연속 " + streak + "회)", null, current(snap)));
            } else {
                log.info("[Rule] {} timeout 감지했으나 연속 {}/{}회 — 임계 미달로 알림 보류(blip 흡수)",
                        service.getName(), streak, threshold);
            }
        }
        // 503
        if (snap.getHttpStatus() != null && snap.getHttpStatus() == 503) {
            triggers.add(Trigger.of("health_503", null, Severity.CRITICAL,
                    "/health 503 반환", null, current(snap)));
        }
        // degraded
        if (HealthStatus.DEGRADED.code().equals(status)) {
            triggers.add(Trigger.of("degraded", null, Severity.WARNING,
                    "/health/detail status=degraded", null, current(snap)));
        }
        // error (수집/파싱 실패 지속) — 503 처럼 더 구체적인 trigger 가 설명하지 못하는 error 만 fallback 으로 잡는다.
        boolean is503 = snap.getHttpStatus() != null && snap.getHttpStatus() == 503;
        if (HealthStatus.ERROR.code().equals(status) && !is503) {
            triggers.add(Trigger.of("collect_error", null, Severity.WARNING,
                    "수집/파싱 실패(status=error, http=" + snap.getHttpStatus() + ")", null, current(snap)));
        }
        // error rate
        if (snap.getErrorRate5m() != null && snap.getErrorRate5m() >= props.getRule().getErrorRateThresholdPercent()) {
            triggers.add(Trigger.of("error_rate", null, Severity.WARNING,
                    "errorRate5m=" + pct(snap.getErrorRate5m()) + " (임계 " + pct(props.getRule().getErrorRateThresholdPercent()) + ")",
                    null, current(snap)));
        }
        // p95 latency
        if (snap.getP95LatencyMs5m() != null && snap.getP95LatencyMs5m() >= props.getRule().getP95LatencyThresholdMs()) {
            triggers.add(Trigger.of("latency_spike", null, Severity.WARNING,
                    "p95LatencyMs5m=" + fmt(snap.getP95LatencyMs5m()) + "ms (임계 " + fmt(props.getRule().getP95LatencyThresholdMs()) + "ms)",
                    null, current(snap)));
        }
        // DB 상태/지연
        for (HealthDbSnapshot db : dbs) {
            if (Boolean.FALSE.equals(db.getOk())) {
                triggers.add(Trigger.of("db_fail", db.getDbName(), Severity.CRITICAL,
                        db.getDbName() + " ok=false" + (db.getDetail() != null ? " (" + db.getDetail() + ")" : ""),
                        null, current(snap)));
            }
            Double baseline = dbLatencyBaseline(service.getName(), db.getDbName());
            if (db.getLastQueryLatencyMs() != null && baseline != null
                    && baseline >= props.getRule().getDbLatencyMinMs()
                    && db.getLastQueryLatencyMs() >= baseline * props.getRule().getDbLatencySpikeMult()) {
                triggers.add(Trigger.of("db_latency_spike", db.getDbName(), Severity.WARNING,
                        db.getDbName() + " latency " + db.getLastQueryLatencyMs() + "ms (baseline "
                                + fmt(baseline) + "ms)",
                        "{\"baselineLatencyMs\":" + fmt(baseline) + "}", current(snap)));
            }
        }
        return triggers;
    }

    /**
     * 가장 최근 스냅샷부터 연속으로 timeout 인 개수(최대 threshold 까지 확인).
     * 현재 timeout 스냅샷은 이미 저장되어 최신으로 포함된다. non-timeout 을 만나면 멈춘다.
     */
    private int consecutiveTimeoutStreak(String serviceName, int threshold) {
        List<HealthSnapshot> recent = snapshotRepo.findByServiceNameOrderByCollectedAtDesc(
                serviceName, PageRequest.of(0, threshold));
        int streak = 0;
        for (HealthSnapshot s : recent) {
            if (HealthStatus.TIMEOUT.code().equals(s.getStatus())) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    /** window 내 해당 DB 의 평균 query latency. 표본 없으면 null. */
    private Double dbLatencyBaseline(String serviceName, String dbName) {
        LocalDateTime from = LocalDateTime.now().minusMinutes(props.getAnalysisWindowMinutes());
        List<HealthDbSnapshot> window = dbSnapshotRepo
                .findByServiceNameAndDbNameAndCollectedAtGreaterThanEqualOrderByCollectedAtDesc(serviceName, dbName, from);
        return window.stream()
                .map(HealthDbSnapshot::getLastQueryLatencyMs)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .average()
                .stream().boxed().findFirst().orElse(null);
    }

    private Optional<AnomalyEvent> findLatest(String service, String triggerType, String dbName) {
        return dbName == null
                ? anomalyRepo.findFirstByServiceNameAndTriggerTypeAndDbNameIsNullOrderByLastDetectedAtDesc(service, triggerType)
                : anomalyRepo.findFirstByServiceNameAndTriggerTypeAndDbNameOrderByLastDetectedAtDesc(service, triggerType, dbName);
    }

    private boolean isOngoing(AnomalyEvent e) {
        return AnomalyStatus.OPEN.code().equals(e.getStatus())
                || AnomalyStatus.NOTIFIED.code().equals(e.getStatus());
    }

    private boolean cooldownElapsed(AnomalyEvent event, Severity severity, LocalDateTime now) {
        if (event.getLastNotifiedAt() == null) {
            return true;
        }
        int minutes = severity == Severity.CRITICAL
                ? props.getCriticalCooldownMinutes() : props.getAlertCooldownMinutes();
        return Duration.between(event.getLastNotifiedAt(), now).toMinutes() >= minutes;
    }

    private String current(HealthSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s.getStatus());
        m.put("httpStatus", s.getHttpStatus());
        m.put("responseTimeMs", s.getResponseTimeMs());
        m.put("health", endpoint(s.getHealthOutcome(), s.getHealthHttpStatus(), s.getHealthResponseTimeMs()));
        m.put("detail", endpoint(s.getDetailOutcome(), s.getDetailHttpStatus(), s.getDetailResponseTimeMs()));
        m.put("errorRate5m", s.getErrorRate5m());
        m.put("avgLatencyMs5m", s.getAvgLatencyMs5m());
        m.put("p95LatencyMs5m", s.getP95LatencyMs5m());
        m.put("requestCount5m", s.getRequestCount5m());
        m.put("inFlightRequestCount", s.getInFlightRequestCount());
        m.put("threadCount", s.getThreadCount());
        m.put("workingSetMb", s.getWorkingSetMb());
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String pct(double v) {
        return String.format("%.2f%%", v);
    }

    private String fmt(double v) {
        return String.format("%.0f", v);
    }

    private String timeoutSummary(HealthSnapshot snap) {
        List<String> timedOut = new ArrayList<>();
        if ("timeout".equalsIgnoreCase(snap.getHealthOutcome())) {
            timedOut.add("/health");
        }
        if ("timeout".equalsIgnoreCase(snap.getDetailOutcome())) {
            timedOut.add("/health/detail");
        }
        if (timedOut.isEmpty()) {
            return "/health 또는 /health/detail timeout";
        }
        return String.join(", ", timedOut) + " timeout";
    }

    private Map<String, Object> endpoint(String outcome, Integer httpStatus, Long responseTimeMs) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("outcome", outcome);
        endpoint.put("httpStatus", httpStatus);
        endpoint.put("responseTimeMs", responseTimeMs);
        return endpoint;
    }

    /** 내부 트리거 표현. */
    private record Trigger(String triggerType, String dbName, Severity severity, String summary,
                           String baselineJson, String currentJson) {
        static Trigger of(String type, String dbName, Severity sev, String summary,
                          String baselineJson, String currentJson) {
            return new Trigger(type, dbName, sev, summary, baselineJson, currentJson);
        }

        String key() {
            return triggerType + "|" + (dbName == null ? "" : dbName);
        }
    }
}
