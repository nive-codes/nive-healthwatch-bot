package com.nive.healthwatch.domain;

import jakarta.persistence.Entity;
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
 * @class NotificationLog
 * @desc 알림 발송 로그. 정기 리포트는 anomalyEventId=null.
 * @since 2026-07-06
 */
@Entity
@Table(name = "notification_log")
@Getter
@Setter
@NoArgsConstructor
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long anomalyEventId;
    private Long serviceId;
    private Long senderId;
    private String channel;
    private boolean success;
    private Integer responseCode;
    private String errorMessage;
    private LocalDateTime sentAt = LocalDateTime.now();
}
