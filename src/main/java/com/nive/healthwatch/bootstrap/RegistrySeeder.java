package com.nive.healthwatch.bootstrap;

import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.NotificationSender;
import com.nive.healthwatch.domain.NotificationSenderMail;
import com.nive.healthwatch.domain.enums.NotificationChannel;
import com.nive.healthwatch.domain.repository.MonitoredServiceRepository;
import com.nive.healthwatch.domain.repository.NotificationSenderMailRepository;
import com.nive.healthwatch.domain.repository.NotificationSenderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * @author nive
 * @class RegistrySeeder
 * @desc 최초 부팅 시 monitored_service 가 비어있으면 yml(health-watch.services)로 감시 대상을 시딩하고,
 *       alertEmail 이 있으면 MAIN 메일 sender 를 함께 시딩한다. 이후로는 DB 가 진실 원천(재시딩 안 함).
 * @since 2026-07-06
 */
@Slf4j
@Component
public class RegistrySeeder implements ApplicationRunner {

    private final HealthWatchProperties props;
    private final MonitoredServiceRepository serviceRepo;
    private final NotificationSenderRepository senderRepo;
    private final NotificationSenderMailRepository mailRepo;

    public RegistrySeeder(HealthWatchProperties props, MonitoredServiceRepository serviceRepo,
                          NotificationSenderRepository senderRepo, NotificationSenderMailRepository mailRepo) {
        this.props = props;
        this.serviceRepo = serviceRepo;
        this.senderRepo = senderRepo;
        this.mailRepo = mailRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (serviceRepo.count() > 0) {
            return; // 이미 등록됨 — DB 가 진실 원천
        }
        if (props.getServices().isEmpty()) {
            log.info("[Seed] yml services 비어있음 — 시딩 없음(DB 직접 등록 필요)");
            return;
        }
        int seeded = 0;
        for (HealthWatchProperties.Service s : props.getServices()) {
            MonitoredService ms = new MonitoredService();
            ms.setName(s.getName());
            ms.setBaseUrl(s.getBaseUrl());
            ms.setProfile(s.getProfile());
            ms.setEnabled(s.isEnabled());
            MonitoredService savedSvc = serviceRepo.save(ms);
            seeded++;

            if (s.getAlertEmail() != null && !s.getAlertEmail().isBlank()) {
                NotificationSender sender = new NotificationSender();
                sender.setServiceId(savedSvc.getId());
                sender.setChannel(NotificationChannel.MAIL);
                sender.setEnabled(true);
                NotificationSender savedSender = senderRepo.save(sender);

                NotificationSenderMail mail = new NotificationSenderMail();
                mail.setSenderId(savedSender.getId());
                mail.setEmail(s.getAlertEmail().trim());
                mail.setMain(true); // 시드는 MAIN 담당자로
                mailRepo.save(mail);
            } else {
                log.warn("[Seed] {} alertEmail 미설정 — MAIL sender 미시딩(요약/알림 발송 안 됨). DB 에 직접 등록 필요.",
                        s.getName());
            }
        }
        log.info("[Seed] monitored_service {}건 시딩 완료(+MAIN 메일)", seeded);
    }
}
