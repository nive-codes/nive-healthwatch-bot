package com.nive.healthwatch.schedule;

import com.nive.healthwatch.ai.AiAnalyzer;
import com.nive.healthwatch.ai.PromptBuilder;
import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.HealthSnapshot;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.enums.HealthStatus;
import com.nive.healthwatch.domain.repository.AnomalyEventRepository;
import com.nive.healthwatch.domain.repository.HealthSnapshotRepository;
import com.nive.healthwatch.notify.AlertMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DailyReportServiceTest {

    private final HealthSnapshotRepository snapshotRepo = mock(HealthSnapshotRepository.class);
    private final AnomalyEventRepository anomalyRepo = mock(AnomalyEventRepository.class);
    private final HealthWatchProperties props = new HealthWatchProperties();
    private final DailyReportService service = new DailyReportService(
            props, snapshotRepo, anomalyRepo, new PromptBuilder(), mock(AiAnalyzer.class));

    @Test
    void daily_report_contains_only_today_raw_stats() {
        props.setDailyReportUseLlm(false);
        LocalDateTime monday = LocalDateTime.of(2026, 7, 6, 17, 0);
        stubSnapshots("service-a-api");

        AlertMessage message = service.buildCombinedDigest(List.of(monitoredService("service-a-api")), monday);

        assertThat(message.title()).isEqualTo("[daily] [report] 일간 서버 보고서");
        assertThat(message.body()).contains("금일");
        assertThat(message.body()).doesNotContain("금주\n");
        assertThat(message.body()).doesNotContain("월간\n");
        assertThat(message.body()).doesNotContain("[금주 추이]");
        assertThat(message.body()).doesNotContain("[월간 총평]");
    }

    @Test
    void friday_report_includes_weekly_raw_stats() {
        props.setDailyReportUseLlm(false);
        LocalDateTime friday = LocalDateTime.of(2026, 7, 10, 17, 0);
        stubSnapshots("service-a-api");

        AlertMessage message = service.buildCombinedDigest(List.of(monitoredService("service-a-api")), friday);

        assertThat(message.title()).isEqualTo("[weekly] [report] 주간 서버 보고서");
        assertThat(message.body()).contains("금일");
        assertThat(message.body()).contains("금주\n");
        assertThat(message.body()).doesNotContain("월간\n");
        assertThat(message.body()).contains("[금주 추이]");
    }

    @Test
    void month_end_report_includes_monthly_raw_stats() {
        props.setDailyReportUseLlm(false);
        LocalDateTime monthEnd = LocalDateTime.of(2026, 7, 31, 17, 0);
        stubSnapshots("service-a-api");

        AlertMessage message = service.buildCombinedDigest(List.of(monitoredService("service-a-api")), monthEnd);

        assertThat(message.title()).isEqualTo("[monthly] [report] 월간 서버 보고서");
        assertThat(message.body()).contains("금일");
        assertThat(message.body()).contains("금주\n");
        assertThat(message.body()).contains("월간\n");
        assertThat(message.body()).contains("[월간 총평]");
    }

    private void stubSnapshots(String serviceName) {
        when(snapshotRepo.findByServiceNameAndCollectedAtBetween(eq(serviceName), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(snapshot(serviceName)));
        when(anomalyRepo.findByFirstDetectedAtBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());
    }

    private HealthSnapshot snapshot(String serviceName) {
        HealthSnapshot snapshot = new HealthSnapshot();
        snapshot.setServiceName(serviceName);
        snapshot.setStatus(HealthStatus.OK.code());
        snapshot.setHttpStatus(200);
        snapshot.setAvgLatencyMs5m(10.0);
        snapshot.setP95LatencyMs5m(50.0);
        snapshot.setErrorRate5m(0.0);
        return snapshot;
    }

    private MonitoredService monitoredService(String name) {
        MonitoredService service = new MonitoredService();
        service.setId(1L);
        service.setName(name);
        service.setProfile("USER_API");
        service.setEnabled(true);
        return service;
    }
}
