package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.NotificationSender;
import com.nive.healthwatch.domain.enums.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * @author nive
 * @class NotificationSenderRepository
 * @desc notification_sender(base) 리포지토리. 프로젝트별 활성 sender 를 조회해 다중 채널 발송에 쓴다.
 * @since 2026-07-06
 */
public interface NotificationSenderRepository extends JpaRepository<NotificationSender, Long> {

    List<NotificationSender> findByServiceIdAndEnabledTrue(Long serviceId);

    List<NotificationSender> findByServiceIdAndChannelAndEnabledTrue(Long serviceId, NotificationChannel channel);
}
