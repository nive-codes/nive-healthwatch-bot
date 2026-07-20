package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.NotificationSenderTelegram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author nive
 * @class NotificationSenderTelegramRepository
 * @desc 텔레그램 sender detail 리포지토리(선반영). sender_id 로 1:1 조회.
 * @since 2026-07-06
 */
public interface NotificationSenderTelegramRepository extends JpaRepository<NotificationSenderTelegram, Long> {

    Optional<NotificationSenderTelegram> findBySenderId(Long senderId);
}
