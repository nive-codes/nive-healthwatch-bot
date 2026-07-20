package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.NotificationSenderMail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * @author nive
 * @class NotificationSenderMailRepository
 * @desc 메일 sender detail 리포지토리. sender_id 로 1:1 조회, MAIN 담당자 판별에 사용.
 * @since 2026-07-06
 */
public interface NotificationSenderMailRepository extends JpaRepository<NotificationSenderMail, Long> {

    Optional<NotificationSenderMail> findBySenderId(Long senderId);

    List<NotificationSenderMail> findBySenderIdIn(List<Long> senderIds);
}
