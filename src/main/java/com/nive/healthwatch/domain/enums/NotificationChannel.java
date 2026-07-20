package com.nive.healthwatch.domain.enums;

/**
 * @author nive
 * @class NotificationChannel
 * @desc 알림 채널 종류. 라우터는 이 enum 값으로 ChannelSender 구현체를 선택한다.
 * @since 2026-07-06
 */
public enum NotificationChannel {
    MAIL, SLACK, DISCORD, TELEGRAM
}
