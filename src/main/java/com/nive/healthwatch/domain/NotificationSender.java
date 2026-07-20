package com.nive.healthwatch.domain;

import com.nive.healthwatch.domain.enums.NotificationChannel;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * @author nive
 * @class NotificationSender
 * @desc 알림 sender base(supertype). 프로젝트(service_id) × 채널(channel) 한 건을 나타내며,
 *       실제 발송 정보는 채널별 detail 테이블(notification_sender_mail/slack/discord/telegram)에 1:1 로 있다.
 *       프로젝트당 여러 행을 둬 다중 채널·다중 수신자 발송을 표현한다.
 * @since 2026-07-06
 */
@Entity
@Table(name = "notification_sender")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSender {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long serviceId;

    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    private boolean enabled = true;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
