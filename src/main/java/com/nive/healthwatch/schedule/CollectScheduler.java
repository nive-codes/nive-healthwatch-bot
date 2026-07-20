package com.nive.healthwatch.schedule;

import com.nive.healthwatch.collect.CollectResult;
import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.HealthSnapshot;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.repository.MonitoredServiceRepository;
import com.nive.healthwatch.notify.AlertMessage;
import com.nive.healthwatch.notify.NotificationRouter;
import com.nive.healthwatch.watch.WatchOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author nive
 * @class CollectScheduler
 * @desc 주기 수집 루프. DB(monitored_service)의 활성 대상을 순회하며 collect→rule→AI→notify 를 수행한다.
 *       간격은 health-watch.collect-interval-seconds(초) → ms 로 환산해 fixedDelay 로 돈다.
 * @since 2026-07-06
 */
@Slf4j
@Component
public class CollectScheduler {

    private final MonitoredServiceRepository serviceRepo;
    private final WatchOrchestrator orchestrator;
    private final NotificationRouter router;
    private final HealthWatchProperties props;
    private final AtomicBoolean firstCollectReportSent = new AtomicBoolean(false);

    public CollectScheduler(MonitoredServiceRepository serviceRepo, WatchOrchestrator orchestrator,
                            NotificationRouter router, HealthWatchProperties props) {
        this.serviceRepo = serviceRepo;
        this.orchestrator = orchestrator;
        this.router = router;
        this.props = props;
    }

    @Scheduled(
            fixedDelayString = "${health-watch.collect-interval-seconds:60}000",
            initialDelayString = "10000")
    public void collectAll() {
        List<MonitoredService> targets = serviceRepo.findByEnabledTrue();
        Map<String, CollectResult> results = new LinkedHashMap<>();
        for (MonitoredService service : targets) {
            CollectResult result = orchestrator.watch(service);
            if (result != null) {
                results.put(service.getName(), result);
            }
        }
        sendFirstCollectReportIfNeeded(targets, results);
    }

    private void sendFirstCollectReportIfNeeded(List<MonitoredService> targets, Map<String, CollectResult> results) {
        if (!props.isFirstCollectReportEnabled() || targets.isEmpty()) {
            return;
        }
        if (!firstCollectReportSent.compareAndSet(false, true)) {
            return;
        }
        router.dispatchMainMailOnce(targets, AlertMessage.system("[startup] 첫 수집 완료 알림", firstCollectBody(targets, results)));
    }

    private String firstCollectBody(List<MonitoredService> targets, Map<String, CollectResult> results) {
        long ok = results.values().stream()
                .map(CollectResult::snapshot)
                .filter(s -> "ok".equalsIgnoreCase(s.getStatus()))
                .count();
        int failed = targets.size() - (int) ok;
        String overall = failed == 0
                ? "첫 수집이 완료되었고 활성 감시 대상이 모두 정상 응답했습니다."
                : "첫 수집이 완료되었고 일부 감시 대상은 확인이 필요합니다.";

        StringBuilder out = new StringBuilder();
        out.append("[요약]\n");
        out.append(overall).append(" 정상 ").append(ok).append("개 / 전체 ").append(targets.size()).append("개입니다.\n\n");
        out.append("[수집 결과]\n");
        for (MonitoredService service : targets) {
            CollectResult result = results.get(service.getName());
            if (result == null) {
                out.append("- ")
                        .append(service.getName())
                        .append(" | 수집 실패 | http=- | latency=- | db=- | ")
                        .append(service.getBaseUrl())
                        .append("\n");
                continue;
            }
            HealthSnapshot snapshot = result.snapshot();
            out.append("- ")
                    .append(service.getName())
                    .append(" | ")
                    .append(snapshot.getStatus() == null ? "unknown" : snapshot.getStatus())
                    .append(" | http=")
                    .append(snapshot.getHttpStatus())
                    .append(" | latency=")
                    .append(snapshot.getResponseTimeMs())
                    .append("ms | db=")
                    .append(result.dbSnapshots().size())
                    .append(" | ")
                    .append(snapshot.getCollectedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .append(" KST\n");
        }
        out.append("\n[안내]\n");
        out.append("이 메일은 프로세스 기동 후 첫 수집 루프에 대해서만 1회 발송됩니다. 이후 장애/복구 알림과 17:00 정기 리포트는 기존 정책대로 발송됩니다.");
        return out.toString();
    }
}
