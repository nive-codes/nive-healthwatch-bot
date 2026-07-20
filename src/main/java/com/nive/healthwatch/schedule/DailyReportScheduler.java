package com.nive.healthwatch.schedule;

import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.repository.MonitoredServiceRepository;
import com.nive.healthwatch.notify.NotificationRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author nive
 * @class DailyReportScheduler
 * @desc 매일(기본 17:00) 활성 프로젝트 전체를 하나의 통합 다이제스트로 만들어 MAIN 메일 수신자에게 발송한다.
 * @since 2026-07-06
 */
@Slf4j
@Component
public class DailyReportScheduler implements ApplicationRunner {

    private final HealthWatchProperties props;
    private final MonitoredServiceRepository serviceRepo;
    private final DailyReportService reportService;
    private final NotificationRouter router;

    public DailyReportScheduler(HealthWatchProperties props, MonitoredServiceRepository serviceRepo, DailyReportService reportService,
                                NotificationRouter router) {
        this.props = props;
        this.serviceRepo = serviceRepo;
        this.reportService = reportService;
        this.router = router;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.isDailyReportSendOnStart()) {
            sendDailyReport();
        }
    }

    @Scheduled(cron = "${health-watch.daily-report-cron:0 0 17 * * *}", zone = "Asia/Seoul")
    public void sendDailyReport() {
        LocalDateTime now = LocalDateTime.now();
        List<MonitoredService> services = serviceRepo.findByEnabledTrue();
        if (services.isEmpty()) {
            log.warn("[DailyReport] 활성 프로젝트 없음 — 통합 다이제스트 skip");
            return;
        }
        try {
            router.dispatchMainMailOnce(services, reportService.buildCombinedDigest(services, now));
            log.info("[DailyReport] 통합 다이제스트 발송 projects={}", services.size());
        } catch (Exception e) {
            log.warn("[DailyReport] 통합 다이제스트 실패", e);
        }
    }
}
