package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.NotificationSenderDiscord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author nive
 * @class NotificationSenderDiscordRepository
 * @desc 디스코드 sender detail 리포지토리. sender_id 로 1:1 조회(webhook 은 컨버터가 복호화).
 * @since 2026-07-06
 */
public interface NotificationSenderDiscordRepository extends JpaRepository<NotificationSenderDiscord, Long> {

    Optional<NotificationSenderDiscord> findBySenderId(Long senderId);
}
