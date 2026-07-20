package com.nive.healthwatch.notify;

import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.NotificationSender;
import com.nive.healthwatch.domain.NotificationSenderMail;
import com.nive.healthwatch.domain.enums.NotificationChannel;
import com.nive.healthwatch.domain.repository.NotificationLogRepository;
import com.nive.healthwatch.domain.repository.NotificationSenderMailRepository;
import com.nive.healthwatch.domain.repository.NotificationSenderRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author nive
 * @class NotificationRouterTest
 * @desc 라우터 단위 테스트 — 프로젝트의 다중 sender 전체 발송(dispatch)과 MAIN 담당자 단일 발송(dispatchMainMail) 검증.
 * @since 2026-07-06
 */
class NotificationRouterTest {

    /** 발송된 sender id 를 기록하는 스텁 채널. */
    static class CapturingMail implements ChannelSender {
        final List<Long> sent = new ArrayList<>();

        public NotificationChannel channel() {
            return NotificationChannel.MAIL;
        }

        public boolean available() {
            return true;
        }

        public NotificationResult send(NotificationSender sender, AlertMessage message) {
            sent.add(sender.getId());
            return NotificationResult.ok("MAIL", 200);
        }
    }

    private NotificationSender sender(long id, long serviceId) {
        NotificationSender s = new NotificationSender();
        s.setId(id);
        s.setServiceId(serviceId);
        s.setChannel(NotificationChannel.MAIL);
        s.setEnabled(true);
        return s;
    }

    private MonitoredService service() {
        MonitoredService s = new MonitoredService();
        s.setId(10L);
        s.setName("service-a-api");
        return s;
    }

    @Test
    void dispatch_sends_to_all_enabled_senders() {
        NotificationSenderRepository senderRepo = mock(NotificationSenderRepository.class);
        NotificationSenderMailRepository mailRepo = mock(NotificationSenderMailRepository.class);
        NotificationLogRepository logRepo = mock(NotificationLogRepository.class);
        CapturingMail mail = new CapturingMail();

        when(senderRepo.findByServiceIdAndEnabledTrue(10L))
                .thenReturn(List.of(sender(100L, 10L), sender(101L, 10L)));

        NotificationRouter router = new NotificationRouter(List.of(mail), senderRepo, mailRepo, logRepo);
        router.dispatch(service(), AlertMessage.report("t", "b"));

        assertThat(mail.sent).containsExactly(100L, 101L);
    }

    @Test
    void dispatchMainMail_picks_the_main_recipient() {
        NotificationSenderRepository senderRepo = mock(NotificationSenderRepository.class);
        NotificationSenderMailRepository mailRepo = mock(NotificationSenderMailRepository.class);
        NotificationLogRepository logRepo = mock(NotificationLogRepository.class);
        CapturingMail mail = new CapturingMail();

        when(senderRepo.findByServiceIdAndChannelAndEnabledTrue(10L, NotificationChannel.MAIL))
                .thenReturn(List.of(sender(100L, 10L), sender(101L, 10L)));
        when(mailRepo.findBySenderId(100L)).thenReturn(Optional.of(mailDetail(100L, false)));
        when(mailRepo.findBySenderId(101L)).thenReturn(Optional.of(mailDetail(101L, true)));

        NotificationRouter router = new NotificationRouter(List.of(mail), senderRepo, mailRepo, logRepo);
        router.dispatchMainMail(service(), AlertMessage.report("t", "b"));

        assertThat(mail.sent).containsExactly(101L); // is_main 인 101 에게만
    }

    @Test
    void dispatchMainMailOnce_deduplicates_same_main_email() {
        NotificationSenderRepository senderRepo = mock(NotificationSenderRepository.class);
        NotificationSenderMailRepository mailRepo = mock(NotificationSenderMailRepository.class);
        NotificationLogRepository logRepo = mock(NotificationLogRepository.class);
        CapturingMail mail = new CapturingMail();

        MonitoredService user = service(10L, "service-a-api");
        MonitoredService admin = service(11L, "service-b-api");

        when(senderRepo.findByServiceIdAndChannelAndEnabledTrue(10L, NotificationChannel.MAIL))
                .thenReturn(List.of(sender(100L, 10L)));
        when(senderRepo.findByServiceIdAndChannelAndEnabledTrue(11L, NotificationChannel.MAIL))
                .thenReturn(List.of(sender(110L, 11L)));
        when(mailRepo.findBySenderId(100L)).thenReturn(Optional.of(mailDetail(100L, true, "ops@example.com")));
        when(mailRepo.findBySenderId(110L)).thenReturn(Optional.of(mailDetail(110L, true, "OPS@example.com")));

        NotificationRouter router = new NotificationRouter(List.of(mail), senderRepo, mailRepo, logRepo);
        router.dispatchMainMailOnce(List.of(user, admin), AlertMessage.report("t", "b"));

        assertThat(mail.sent).containsExactly(100L);
    }

    private NotificationSenderMail mailDetail(long senderId, boolean main) {
        return mailDetail(senderId, main, "x@x.com");
    }

    private NotificationSenderMail mailDetail(long senderId, boolean main, String email) {
        NotificationSenderMail m = new NotificationSenderMail();
        m.setSenderId(senderId);
        m.setEmail(email);
        m.setMain(main);
        return m;
    }

    private MonitoredService service(long id, String name) {
        MonitoredService s = new MonitoredService();
        s.setId(id);
        s.setName(name);
        return s;
    }
}
