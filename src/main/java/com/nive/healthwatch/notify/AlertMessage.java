package com.nive.healthwatch.notify;

/**
 * @author nive
 * @class AlertMessage
 * @desc 발송할 알림 한 건. anomalyEventId 는 rule/AI 알림에서 채워지고, 정기 리포트는 null.
 *       title 은 [severity] 헤더, body 는 trigger/current/ai/check 본문.
 * @since 2026-07-06
 */
public record AlertMessage(Long anomalyEventId, String title, String body, String kind, String projectName) {

    public static AlertMessage of(Long anomalyEventId, String title, String body) {
        return new AlertMessage(anomalyEventId, title, body, "alert", null);
    }

    public static AlertMessage alert(Long anomalyEventId, String title, String body, String projectName) {
        return new AlertMessage(anomalyEventId, title, body, "alert", projectName);
    }

    public static AlertMessage recovery(Long anomalyEventId, String title, String body, String projectName) {
        return new AlertMessage(anomalyEventId, title, body, "recovery", projectName);
    }

    /** 정기 리포트 등 anomaly 와 무관한 알림. */
    public static AlertMessage report(String title, String body) {
        return new AlertMessage(null, title, body, "report", null);
    }

    public static AlertMessage report(String title, String body, String projectName) {
        return new AlertMessage(null, title, body, "report", projectName);
    }

    /** 기동/첫 수집 등 시스템성 안내 메일. */
    public static AlertMessage system(String title, String body) {
        return new AlertMessage(null, title, body, "system", null);
    }

    public String render() {
        return (title == null || title.isBlank()) ? body : title + "\n\n" + body;
    }
}
