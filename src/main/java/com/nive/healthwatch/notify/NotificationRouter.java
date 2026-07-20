package com.nive.healthwatch.notify;

import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.NotificationLog;
import com.nive.healthwatch.domain.NotificationSender;
import com.nive.healthwatch.domain.NotificationSenderMail;
import com.nive.healthwatch.domain.enums.NotificationChannel;
import com.nive.healthwatch.domain.repository.NotificationLogRepository;
import com.nive.healthwatch.domain.repository.NotificationSenderMailRepository;
import com.nive.healthwatch.domain.repository.NotificationSenderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author nive
 * @class NotificationRouter
 * @desc 프로젝트(monitored_service)의 활성 sender 를 조회해 채널별 발송기로 라우팅한다(다중 발송).
 *       anomaly 는 dispatch()로 전 채널, 18시 요약은 dispatchMainMail()로 MAIN 담당자에게만 보낸다.
 *       한 채널 실패가 다른 채널이나 수집 루프를 막지 않으며 결과는 notification_log 에 남긴다.
 * @since 2026-07-06
 */
@Slf4j
@Component
public class NotificationRouter {

    private final Map<NotificationChannel, ChannelSender> senders = new EnumMap<>(NotificationChannel.class);
    private final NotificationSenderRepository senderRepo;
    private final NotificationSenderMailRepository mailRepo;
    private final NotificationLogRepository logRepo;

    public NotificationRouter(List<ChannelSender> channelSenders,
                              NotificationSenderRepository senderRepo,
                              NotificationSenderMailRepository mailRepo,
                              NotificationLogRepository logRepo) {
        for (ChannelSender cs : channelSenders) {
            senders.put(cs.channel(), cs);
        }
        this.senderRepo = senderRepo;
        this.mailRepo = mailRepo;
        this.logRepo = logRepo;
    }

    /** anomaly/복구 알림 — 프로젝트의 모든 활성 채널로 발송. */
    public void dispatch(MonitoredService service, AlertMessage message) {
        List<NotificationSender> active = senderRepo.findByServiceIdAndEnabledTrue(service.getId());
        if (active.isEmpty()) {
            log.warn("[Notify] {} 활성 sender 없음 — 알림 skip (최소 MAIL sender 등록 필요)", service.getName());
            return;
        }
        for (NotificationSender sender : active) {
            deliver(service, sender, message);
        }
    }

    /** 18시 정기 요약 — 프로젝트 MAIN 담당자(is_main 메일)에게만. main 이 없으면 첫 메일로 폴백. */
    public void dispatchMainMail(MonitoredService service, AlertMessage message) {
        List<NotificationSender> mailSenders =
                senderRepo.findByServiceIdAndChannelAndEnabledTrue(service.getId(), NotificationChannel.MAIL);
        if (mailSenders.isEmpty()) {
            log.warn("[Notify] {} MAIL sender 없음 — 정기 요약 skip", service.getName());
            return;
        }
        NotificationSender target = pickMain(mailSenders);
        deliver(service, target, message);
    }

    /** 통합 정기 요약 — 여러 프로젝트 MAIN 메일을 수집하되 같은 email 은 한 번만 발송한다. */
    public void dispatchMainMailOnce(List<MonitoredService> services, AlertMessage message) {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (MonitoredService service : services) {
            List<NotificationSender> mailSenders =
                    senderRepo.findByServiceIdAndChannelAndEnabledTrue(service.getId(), NotificationChannel.MAIL);
            if (mailSenders.isEmpty()) {
                log.warn("[Notify] {} MAIL sender 없음 — 통합 정기 요약 대상 제외", service.getName());
                continue;
            }
            NotificationSender picked = pickMain(mailSenders);
            Optional<NotificationSenderMail> detail = mailRepo.findBySenderId(picked.getId());
            if (detail.isEmpty()) {
                log.warn("[Notify] {} MAIL detail 없음 — 통합 정기 요약 대상 제외(sender_id={})", service.getName(), picked.getId());
                continue;
            }
            String email = detail.get().getEmail();
            if (email != null && !email.isBlank()) {
                targets.putIfAbsent(email.toLowerCase(), new Target(service, picked));
            }
        }
        if (targets.isEmpty()) {
            log.warn("[Notify] 통합 정기 요약 수신자 없음 — 발송 skip");
            return;
        }
        for (Target target : targets.values()) {
            deliver(target.service(), target.sender(), message);
        }
    }

    private NotificationSender pickMain(List<NotificationSender> mailSenders) {
        for (NotificationSender s : mailSenders) {
            Optional<NotificationSenderMail> mail = mailRepo.findBySenderId(s.getId());
            if (mail.isPresent() && mail.get().isMain()) {
                return s;
            }
        }
        return mailSenders.get(0); // MAIN 미지정 시 첫 메일로 폴백
    }

    private void deliver(MonitoredService service, NotificationSender sender, AlertMessage message) {
        ChannelSender cs = senders.get(sender.getChannel());
        NotificationResult result;
        if (cs == null) {
            result = NotificationResult.fail(sender.getChannel().name(), null, "발송기 미구현(" + sender.getChannel() + ")");
        } else if (!cs.available()) {
            result = NotificationResult.fail(sender.getChannel().name(), null, "채널 비활성/미설정");
        } else {
            try {
                result = cs.send(sender, message);
            } catch (Exception e) {
                log.warn("[Notify] {} 발송 예외", sender.getChannel(), e);
                result = NotificationResult.fail(sender.getChannel().name(), null, e.getMessage());
            }
        }
        record(service.getId(), sender.getId(), message.anomalyEventId(), result);
    }

    private void record(Long serviceId, Long senderId, Long anomalyEventId, NotificationResult result) {
        NotificationLog log = new NotificationLog();
        log.setServiceId(serviceId);
        log.setSenderId(senderId);
        log.setAnomalyEventId(anomalyEventId);
        log.setChannel(result.channel());
        log.setSuccess(result.success());
        log.setResponseCode(result.responseCode());
        log.setErrorMessage(result.errorMessage());
        logRepo.save(log);
    }

    private record Target(MonitoredService service, NotificationSender sender) {
    }
}
