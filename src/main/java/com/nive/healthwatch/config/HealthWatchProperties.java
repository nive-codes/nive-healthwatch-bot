package com.nive.healthwatch.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * @author nive
 * @class HealthWatchProperties
 * @desc health-watch.* 설정 바인딩. services 는 최초 부팅 시 DB(monitored_service) 시딩용 부트스트랩이며,
 *       런타임 감시 대상의 진실 원천은 DB 다. 시크릿(SMTP/crypto)은 env 로 주입한다.
 * @since 2026-07-06
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "health-watch")
public class HealthWatchProperties {

    private int collectIntervalSeconds = 60;
    private int requestTimeoutSeconds = 10;
    private int analysisWindowMinutes = 20;
    private int alertCooldownMinutes = 10;
    private int criticalCooldownMinutes = 5;
    private String dailyReportCron = "0 0 17 * * *";
    private String retentionCron = "0 30 4 * * *";
    /** 18시 정기 요약에 LLM 내러티브를 쓸지(실패 시 rule 집계 폴백). */
    private boolean dailyReportUseLlm = true;
    /** 운영 검증/수동 발송용. true 면 기동 직후 통합 daily report 를 1회 발송한다. */
    private boolean dailyReportSendOnStart = false;
    /** true 면 앱 기동 완료 후 MAIN 메일 수신자에게 기동 알림을 1회 발송한다. */
    private boolean startupNotificationEnabled = false;
    /** true 면 첫 수집 루프 완료 후 수집 결과 요약 메일을 1회 발송한다. */
    private boolean firstCollectReportEnabled = false;

    /** 최초 부팅 시 monitored_service 가 비어있으면 여기서 시딩(+ MAIN 메일 seed). */
    private List<Service> services = new ArrayList<>();

    /** health endpoint 인증 헤더명. 각 서비스 token 이 설정된 경우에만 전송한다. */
    private String healthcheckTokenHeader = "Healthcheck-Token";

    @NestedConfigurationProperty
    private Ai ai = new Ai();
    @NestedConfigurationProperty
    private Notifier notifier = new Notifier();
    @NestedConfigurationProperty
    private Rule rule = new Rule();
    @NestedConfigurationProperty
    private Retention retention = new Retention();

    @Getter
    @Setter
    public static class Service {
        private String name;
        private String baseUrl;
        private String profile;
        private boolean enabled = true;
        /** 시딩용 MAIN 담당자 메일(있으면 MAIL sender 를 is_main 으로 함께 시딩). */
        private String alertEmail;
        /** 런타임 healthcheck 호출용 token. DB 에 저장하지 않고 yml/env 에서만 사용한다. */
        private String healthcheckToken;
    }

    @Getter
    @Setter
    public static class Ai {
        private boolean enabled = true;
        private int timeoutSeconds = 90;
        private int maxOutputChars = 20000;
        private int maxConcurrent = 1;
        private int reanalyzeCooldownMinutes = 30;
        private List<String> command = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Notifier {
        @NestedConfigurationProperty
        private Mail mail = new Mail();
        @NestedConfigurationProperty
        private Slack slack = new Slack();
        @NestedConfigurationProperty
        private Discord discord = new Discord();
        @NestedConfigurationProperty
        private Telegram telegram = new Telegram();
    }

    /** 메일(주력 채널). SMTP 접속 자체는 spring.mail.* 로 설정하고, 여기선 발신자/활성만 둔다. */
    @Getter
    @Setter
    public static class Mail {
        private boolean enabled = true;
        private String from;
        private String subjectPrefix = "[health-watch] ";
        /**
         * 이 시각 이후에는 메일을 발송하지 않는다(KST, 0~23). 18이면 18:00:00부터 차단.
         * 음수로 두면 시간 제한을 사용하지 않는다.
         */
        private int suppressAfterHour = 18;
    }

    @Getter
    @Setter
    public static class Slack {
        /** Slack sender detail 이 DB 에 등록되어 있어도 false 면 발송하지 않는다. */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Discord {
        /** Discord sender detail 이 DB 에 등록되어 있어도 false 면 발송하지 않는다. */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Telegram {
        /** Telegram sender detail 이 DB 에 등록되어 있어도 false 면 발송하지 않는다. */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Rule {
        /** /health/detail api.errorRate5m 값은 percent 단위로 해석한다. 예: 1.32 = 1.32%. */
        private double errorRateThresholdPercent = 5.0;
        private double p95LatencyThresholdMs = 1000;
        private double dbLatencySpikeMult = 3.0;
        private long dbLatencyMinMs = 100;
        /**
         * timeout 을 CRITICAL 로 알리기 위한 최소 '연속' 발생 횟수(디바운스).
         * 1 이면 단발 blip 에도 즉시 알림(과민), 2~3 이면 최근 N회 연속 timeout 일 때만 알림.
         * 유휴 API 의 무거운 /health/detail 이 일시적으로 1회 지연되는 오탐을 흡수한다.
         */
        private int timeoutConsecutiveThreshold = 2;
    }

    @Getter
    @Setter
    public static class Retention {
        private int healthSnapshotDays = 7;
        private int healthDbSnapshotDays = 7;
        private int anomalyEventDays = 30;
        private int aiReportDays = 30;
        private int notificationLogDays = 30;
    }
}
