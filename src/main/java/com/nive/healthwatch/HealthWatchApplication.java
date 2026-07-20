package com.nive.healthwatch;

import com.nive.healthwatch.config.HealthWatchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author nive
 * @class HealthWatchApplication
 * @desc Health Watch Bot — 등록된 백엔드 서비스의 /health, /health/detail 을 주기 수집하고
 *       rule trigger 발생 시에만 AI CLI 분석을 붙여 알림 채널로 리포트한다.
 *       수집·판단은 rule 이 주체이고, AI 는 원인 추정/확인항목 제안 역할만 한다.
 * @since 2026-07-06
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(HealthWatchProperties.class)
public class HealthWatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthWatchApplication.class, args);
    }
}
