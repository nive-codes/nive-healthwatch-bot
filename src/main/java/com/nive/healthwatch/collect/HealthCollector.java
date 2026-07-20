package com.nive.healthwatch.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.HealthDbSnapshot;
import com.nive.healthwatch.domain.HealthSnapshot;
import com.nive.healthwatch.domain.enums.HealthStatus;
import com.nive.healthwatch.domain.repository.HealthDbSnapshotRepository;
import com.nive.healthwatch.domain.repository.HealthSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * @author nive
 * @class HealthCollector
 * @desc 한 서비스의 /health + /health/detail 를 호출·파싱·저장한다.
 *       /health 는 status code 중심, /health/detail 은 runtime/api/db 를 추세 분석용으로 파싱한다.
 *       파싱 실패나 timeout 도 스냅샷으로 남겨 rule 이 판단할 수 있게 한다.
 * @since 2026-07-06
 */
@Slf4j
@Service
public class HealthCollector {

    private final HealthClient client;
    private final ObjectMapper objectMapper;
    private final HealthSnapshotRepository snapshotRepo;
    private final HealthDbSnapshotRepository dbSnapshotRepo;
    private final HealthWatchProperties props;

    public HealthCollector(HealthClient client, ObjectMapper objectMapper, HealthWatchProperties props,
                           HealthSnapshotRepository snapshotRepo,
                           HealthDbSnapshotRepository dbSnapshotRepo) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.props = props;
        this.snapshotRepo = snapshotRepo;
        this.dbSnapshotRepo = dbSnapshotRepo;
    }

    public CollectResult collect(MonitoredService service) {
        String base = trimTrailingSlash(service.getBaseUrl());
        String token = healthcheckToken(service);
        HttpProbe health = client.get(base + "/health", props.getHealthcheckTokenHeader(), token);
        HttpProbe detail = client.get(base + "/health/detail", props.getHealthcheckTokenHeader(), token);

        HealthSnapshot snap = new HealthSnapshot();
        snap.setServiceName(service.getName());
        snap.setCollectedAt(LocalDateTime.now());
        snap.setHealthOutcome(health.outcome().name().toLowerCase());
        snap.setHealthHttpStatus(health.statusCode() == 0 ? null : health.statusCode());
        snap.setHealthResponseTimeMs(health.elapsedMs());
        snap.setDetailOutcome(detail.outcome().name().toLowerCase());
        snap.setDetailHttpStatus(detail.statusCode() == 0 ? null : detail.statusCode());
        snap.setDetailResponseTimeMs(detail.elapsedMs());
        snap.setHttpStatus(health.statusCode() != 0 ? health.statusCode() : detail.statusCode());
        // 응답시간은 detail 우선(더 무거운 호출), 없으면 health
        snap.setResponseTimeMs(detail.elapsedMs() > 0 ? detail.elapsedMs() : health.elapsedMs());
        snap.setStatus(resolveStatus(health, detail).code());

        List<HealthDbSnapshot> dbSnapshots = new ArrayList<>();
        applyReportedStatus(health.body(), snap, "/health");
        if (detail.is2xx() && detail.body() != null) {
            try {
                JsonNode root = objectMapper.readTree(detail.body());
                snap.setRawPayload(truncate(detail.body(), 60000));
                parseRuntime(root, snap);
                parseApi(root, snap);
                dbSnapshots = parseDbs(root, service.getName(), snap.getCollectedAt());
                applyReportedStatus(root, snap, "/health/detail");
            } catch (Exception e) {
                log.warn("[Collect] {} /health/detail 파싱 실패 → error 로 기록", service.getName(), e);
                snap.setStatus(HealthStatus.ERROR.code());
                snap.setRawPayload(truncate(detail.body(), 2000));
            }
        }

        HealthSnapshot saved = snapshotRepo.save(snap);
        for (HealthDbSnapshot db : dbSnapshots) {
            db.setSnapshotId(saved.getId());
        }
        List<HealthDbSnapshot> savedDbs = dbSnapshotRepo.saveAll(dbSnapshots);
        log.debug("[Collect] {} status={} http={} rt={}ms dbs={}",
                service.getName(), saved.getStatus(), saved.getHttpStatus(),
                saved.getResponseTimeMs(), savedDbs.size());
        return new CollectResult(saved, savedDbs);
    }

    /** /health(status code) 와 /health/detail 결과를 합쳐 종합 상태를 정한다. */
    private HealthStatus resolveStatus(HttpProbe health, HttpProbe detail) {
        if (health.outcome() == HttpProbe.Outcome.TIMEOUT || detail.outcome() == HttpProbe.Outcome.TIMEOUT) {
            return HealthStatus.TIMEOUT;
        }
        if (health.outcome() == HttpProbe.Outcome.ERROR && detail.outcome() == HttpProbe.Outcome.ERROR) {
            return HealthStatus.ERROR;
        }
        if (!health.is2xx() || !detail.is2xx()) {
            return HealthStatus.ERROR;
        }
        return HealthStatus.OK;
    }

    private void parseRuntime(JsonNode root, HealthSnapshot snap) {
        JsonNode rt = firstNode(root, "runtime", "process");
        if (rt.isMissingNode()) {
            return;
        }
        snap.setUptimeSec(asLong(rt, "uptimeSec", "uptime_sec", "uptime"));
        snap.setWorkingSetMb(asDouble(rt, "workingSetMb", "working_set_mb", "workingSet"));
        snap.setGcHeapMb(asDouble(rt, "gcHeapMb", "gc_heap_mb", "gcHeap"));
        snap.setThreadCount(asInt(rt, "threadCount", "thread_count", "threads"));
    }

    private void parseApi(JsonNode root, HealthSnapshot snap) {
        JsonNode api = firstNode(root, "api", "metrics");
        if (api.isMissingNode()) {
            return;
        }
        snap.setRequestCount1m(asLong(api, "requestCount1m", "request_count_1m"));
        snap.setRequestCount5m(asLong(api, "requestCount5m", "request_count_5m"));
        snap.setErrorCount5m(asLong(api, "errorCount5m", "error_count_5m"));
        snap.setErrorRate5m(asDouble(api, "errorRate5m", "error_rate_5m"));
        snap.setAvgLatencyMs5m(asDouble(api, "avgLatencyMs5m", "avg_latency_ms_5m"));
        snap.setP95LatencyMs5m(asDouble(api, "p95LatencyMs5m", "p95_latency_ms_5m"));
        snap.setInFlightRequestCount(asInt(api, "inFlightRequestCount", "in_flight_request_count"));
    }

    private List<HealthDbSnapshot> parseDbs(JsonNode root, String serviceName, LocalDateTime at) {
        List<HealthDbSnapshot> out = new ArrayList<>();
        JsonNode dbs = firstNode(root, "db", "databases", "dbs");
        if (!dbs.isArray()) {
            return out;
        }
        for (JsonNode node : dbs) {
            HealthDbSnapshot db = new HealthDbSnapshot();
            db.setServiceName(serviceName);
            db.setDbName(text(firstField(node, "name", "dbName", "db_name")));
            JsonNode okNode = firstField(node, "ok", "healthy");
            db.setOk(okNode.isMissingNode() ? null : okNode.asBoolean());
            db.setLastQueryLatencyMs(asLong(node, "lastQueryLatencyMs", "last_query_latency_ms", "latencyMs"));
            db.setLastSuccessAt(asDateTime(node, "lastSuccessAt", "last_success_at"));
            db.setLastFailureAt(asDateTime(node, "lastFailureAt", "last_failure_at"));
            db.setDetail(truncate(text(firstField(node, "detail", "message")), 1000));
            db.setCollectedAt(at);
            out.add(db);
        }
        return out;
    }

    /**
     * HTTP 200 이더라도 payload status 가 ok/degraded 외 값이면 수집 실패로 본다.
     * 예: healthcheck token 누락/오류 시 {"status":"unauthorized", ...}
     */
    private void applyReportedStatus(String body, HealthSnapshot snap, String endpoint) {
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            applyReportedStatus(objectMapper.readTree(body), snap, endpoint);
        } catch (Exception e) {
            log.debug("[Collect] {} status payload 파싱 생략", endpoint, e);
        }
    }

    private void applyReportedStatus(JsonNode root, HealthSnapshot snap, String endpoint) {
        String reported = text(root.path("status"));
        if (reported == null || reported.isBlank() || "ok".equalsIgnoreCase(reported)) {
            return;
        }
        if ("degraded".equalsIgnoreCase(reported)) {
            snap.setStatus(HealthStatus.DEGRADED.code());
            return;
        }
        log.warn("[Collect] {} payload status={} → error 로 기록", endpoint, reported);
        snap.setStatus(HealthStatus.ERROR.code());
    }

    // ── JSON 유틸 (필드명 변형 허용) ──

    private JsonNode firstNode(JsonNode parent, String... keys) {
        for (String k : keys) {
            JsonNode n = parent.path(k);
            if (!n.isMissingNode()) {
                return n;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private JsonNode firstField(JsonNode parent, String... keys) {
        return firstNode(parent, keys);
    }

    private Long asLong(JsonNode parent, String... keys) {
        JsonNode n = firstNode(parent, keys);
        return n.isMissingNode() || n.isNull() ? null : n.asLong();
    }

    private Integer asInt(JsonNode parent, String... keys) {
        JsonNode n = firstNode(parent, keys);
        return n.isMissingNode() || n.isNull() ? null : n.asInt();
    }

    private Double asDouble(JsonNode parent, String... keys) {
        JsonNode n = firstNode(parent, keys);
        return n.isMissingNode() || n.isNull() ? null : n.asDouble();
    }

    private LocalDateTime asDateTime(JsonNode parent, String... keys) {
        JsonNode n = firstNode(parent, keys);
        if (n.isMissingNode() || n.isNull() || n.asText().isBlank()) {
            return null;
        }
        String value = n.asText();
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception ignored) {
            // offset/zoned timestamp from .NET health endpoint, e.g. 2026-07-06T09:59:36.725015+09:00
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String healthcheckToken(MonitoredService service) {
        return props.getServices().stream()
                .filter(s -> same(s.getName(), service.getName()) || same(s.getProfile(), service.getProfile()))
                .map(HealthWatchProperties.Service::getHealthcheckToken)
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .orElse(null);
    }

    private boolean same(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
