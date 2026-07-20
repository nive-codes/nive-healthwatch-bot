package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.NotificationSenderSlack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author nive
 * @class NotificationSenderSlackRepository
 * @desc Slack sender detail 리포지토리. sender_id 로 1:1 조회.
 * @since 2026-07-20
 */
public interface NotificationSenderSlackRepository extends JpaRepository<NotificationSenderSlack, Long> {

    Optional<NotificationSenderSlack> findBySenderId(Long senderId);
}
