package com.nive.healthwatch.notify;

/**
 * @author nive
 * @class NotificationResult
 * @desc 채널 1회 발송 결과.
 * @since 2026-07-06
 */
public record NotificationResult(String channel, boolean success, Integer responseCode, String errorMessage) {

    public static NotificationResult ok(String channel, int responseCode) {
        return new NotificationResult(channel, true, responseCode, null);
    }

    public static NotificationResult fail(String channel, Integer responseCode, String errorMessage) {
        return new NotificationResult(channel, false, responseCode, errorMessage);
    }
}
