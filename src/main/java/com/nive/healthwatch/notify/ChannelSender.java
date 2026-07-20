package com.nive.healthwatch.notify;

import com.nive.healthwatch.domain.NotificationSender;
import com.nive.healthwatch.domain.enums.NotificationChannel;

/**
 * @author nive
 * @class ChannelSender
 * @desc 채널별 실제 발송기 추상화(MAIL/DISCORD/...). 라우터가 sender 의 channel 로 구현체를 골라 위임한다.
 *       발송 대상(메일주소/webhook 등)은 sender_id 로 detail 테이블에서 해석한다.
 * @since 2026-07-06
 */
public interface ChannelSender {

    NotificationChannel channel();

    /** 전역적으로 발송 가능한 상태인지(예: 메일 활성/발신자 설정). false 면 라우터가 스킵 기록. */
    boolean available();

    NotificationResult send(NotificationSender sender, AlertMessage message);
}
