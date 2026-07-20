package com.nive.healthwatch.bootstrap;

import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.repository.MonitoredServiceRepository;
import com.nive.healthwatch.notify.AlertMessage;
import com.nive.healthwatch.notify.NotificationRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * @author nive
 * @class StartupNotificationRunner
 * @desc 앱 기동 완료 후 운영자에게 1회 기동 알림을 발송한다. 실제 health 결과는 첫 수집 완료 알림에서 다룬다.
 * @since 2026-07-06
 */
@Slf4j
@Component
public class StartupNotificationRunner {

    private final HealthWatchProperties props;
    private final MonitoredServiceRepository serviceRepo;
    private final NotificationRouter router;

    public StartupNotificationRunner(HealthWatchProperties props, MonitoredServiceRepository serviceRepo,
                                     NotificationRouter router) {
        this.props = props;
        this.serviceRepo = serviceRepo;
        this.router = router;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sendStartupNotification() {
        if (!props.isStartupNotificationEnabled()) {
            return;
        }
        List<MonitoredService> services = serviceRepo.findByEnabledTrue();
        if (services.isEmpty()) {
            log.warn("[StartupNotify] 활성 감시 대상 없음 — 기동 알림 skip");
            return;
        }
        router.dispatchMainMailOnce(services, AlertMessage.system(
                "[startup] Health Watch Bot 기동 알림",
                body(services)));
    }

    private String body(List<MonitoredService> services) {
        StringBuilder out = new StringBuilder();
        out.append("[요약]\n");
        out.append("Health Watch Bot이 기동되었습니다. 이 메일은 application.yml/DB 기준 활성 감시 대상과 알림 라우팅 확인을 위한 1회성 기동 알림입니다.\n\n");
        out.append("[기동 정보]\n");
        out.append("- 기동 시각: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" KST\n");
        out.append("- 활성 감시 대상: ").append(services.size()).append("개\n");
        out.append("- 수집 주기: ").append(props.getCollectIntervalSeconds()).append("초\n");
        out.append("- 요청 타임아웃: ").append(props.getRequestTimeoutSeconds()).append("초\n");
        out.append("- 첫 수집 완료 알림: ").append(props.isFirstCollectReportEnabled() ? "활성" : "비활성").append("\n\n");
        out.append("[감시 대상]\n");
        for (MonitoredService service : services) {
            out.append("- ")
                    .append(service.getName())
                    .append(" | ")
                    .append(service.getBaseUrl())
                    .append(" | profile=")
                    .append(service.getProfile())
                    .append("\n");
        }
        out.append("\n[안내]\n");
        out.append("실제 /health 수집 성공 여부는 첫 수집 루프가 완료된 뒤 별도 메일로 1회 발송됩니다.");
        return out.toString();
    }
}
