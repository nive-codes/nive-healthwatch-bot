package com.nive.healthwatch.domain;

import com.nive.healthwatch.domain.crypto.AesGcmStringConverter;
import jakarta.persistence.Convert;
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
 * @class NotificationSenderDiscord
 * @desc 디스코드 sender detail. webhook URL 은 준시크릿이라 AES-GCM 으로 암호화 저장한다(키 미설정 시 평문 폴백).
 * @since 2026-07-06
 */
@Entity
@Table(name = "notification_sender_discord")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSenderDiscord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long senderId;

    @Convert(converter = AesGcmStringConverter.class)
    private String webhookUrl;

    private String threadId;
}
