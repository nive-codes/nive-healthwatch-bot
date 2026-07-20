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
 * @class NotificationSenderSlack
 * @desc Slack sender detail. webhook URL 은 준시크릿이라 AES-GCM 으로 암호화 저장한다.
 * @since 2026-07-20
 */
@Entity
@Table(name = "notification_sender_slack")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSenderSlack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long senderId;

    @Convert(converter = AesGcmStringConverter.class)
    private String webhookUrl;

    private String channelName;
}
