package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

/**
 * @author nive
 * @class NotificationLogRepository
 * @desc notification_log 리포지토리. retention 정리용 삭제 쿼리.
 * @since 2026-07-06
 */
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    long deleteBySentAtBefore(LocalDateTime cutoff);
}
