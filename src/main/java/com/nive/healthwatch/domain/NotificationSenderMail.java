package com.nive.healthwatch.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * @author nive
 * @class NotificationSenderMail
 * @desc 메일 sender detail. sender_id 로 base 와 1:1. is_main 이 true 인 행이 프로젝트의 MAIN 담당자로,
 *       18시 정기 요약 메일 수신 대상이 된다(프로젝트당 1명 권장).
 * @since 2026-07-06
 */
@Entity
@Table(name = "notification_sender_mail")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSenderMail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long senderId;
    private String email;
    private String displayName;
    private boolean isMain = false;
}
