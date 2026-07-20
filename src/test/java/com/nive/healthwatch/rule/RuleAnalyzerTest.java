package com.nive.healthwatch.rule;

import com.nive.healthwatch.collect.CollectResult;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.HealthSnapshot;
import com.nive.healthwatch.domain.enums.AnomalyStatus;
import com.nive.healthwatch.domain.enums.HealthStatus;
import com.nive.healthwatch.domain.enums.Severity;
import com.nive.healthwatch.domain.repository.HealthSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author nive
 * @class RuleAnalyzerTest
 * @desc RuleAnalyzer 통합 테스트 — 503 트리거 발생/cooldown 억제/복구(resolved) 감지 검증.
 * @since 2026-07-06
 */
@SpringBootTest
@Transactional
class RuleAnalyzerTest {

    @Autowired
    RuleAnalyzer ruleAnalyzer;

    @Autowired
    HealthSnapshotRepository snapshotRepo;

    private MonitoredService userApi() {
        MonitoredService s = new MonitoredService();
        s.setId(1L);
        s.setName("service-a-api");
        s.setProfile("USER_API");
        s.setBaseUrl("http://localhost:19999");
        return s;
    }

    private HealthSnapshot snapshot503() {
        HealthSnapshot snap = new HealthSnapshot();
        snap.setServiceName("service-a-api");
        snap.setHttpStatus(503);
        snap.setStatus(HealthStatus.ERROR.code());
        return snap;
    }

    @Test
    void fires_critical_anomaly_on_503() {
        RuleResult result = ruleAnalyzer.evaluate(userApi(), new CollectResult(snapshot503(), List.of()));

        assertThat(result.toAlert()).hasSize(1);
        assertThat(result.toAlert().get(0).getTriggerType()).isEqualTo("health_503");
        assertThat(result.toAlert().get(0).getSeverity()).isEqualTo(Severity.CRITICAL.code());
        assertThat(result.toAlert().get(0).getStatus()).isEqualTo(AnomalyStatus.NOTIFIED.code());
    }

    @Test
    void suppresses_duplicate_within_cooldown() {
        MonitoredService svc = userApi();
        ruleAnalyzer.evaluate(svc, new CollectResult(snapshot503(), List.of()));
        // 즉시 재평가 → critical cooldown 미경과 → 재알림 없음
        RuleResult second = ruleAnalyzer.evaluate(svc, new CollectResult(snapshot503(), List.of()));

        assertThat(second.toAlert()).isEmpty();
    }

    @Test
    void resolves_when_condition_disappears() {
        MonitoredService svc = userApi();
        ruleAnalyzer.evaluate(svc, new CollectResult(snapshot503(), List.of()));

        HealthSnapshot healthy = new HealthSnapshot();
        healthy.setServiceName("service-a-api");
        healthy.setHttpStatus(200);
        healthy.setStatus(HealthStatus.OK.code());
        RuleResult recovered = ruleAnalyzer.evaluate(svc, new CollectResult(healthy, List.of()));

        assertThat(recovered.resolved()).hasSize(1);
        assertThat(recovered.resolved().get(0).getStatus()).isEqualTo(AnomalyStatus.RESOLVED.code());
    }

    @Test
    void does_not_fire_error_rate_below_five_percent() {
        HealthSnapshot snap = healthySnapshot("service-b-api");
        snap.setErrorRate5m(1.32);

        RuleResult result = ruleAnalyzer.evaluate(service("service-b-api", "ADMIN_API"), new CollectResult(snap, List.of()));

        assertThat(result.toAlert()).isEmpty();
    }

    @Test
    void fires_error_rate_at_five_percent_or_higher() {
        HealthSnapshot snap = healthySnapshot("service-c-api");
        snap.setErrorRate5m(5.0);

        RuleResult result = ruleAnalyzer.evaluate(service("service-c-api", "DRIVER_API"), new CollectResult(snap, List.of()));

        assertThat(result.toAlert()).hasSize(1);
        assertThat(result.toAlert().get(0).getTriggerType()).isEqualTo("error_rate");
        assertThat(result.toAlert().get(0).getTriggerSummary()).contains("5.00%");
        assertThat(result.toAlert().get(0).getCurrentJson()).contains("\"health\"");
        assertThat(result.toAlert().get(0).getCurrentJson()).contains("\"detail\"");
    }

    @Test
    void single_timeout_does_not_fire() {
        MonitoredService svc = service("timeout-service-a", "USER_API");
        HealthSnapshot t1 = saveTimeout("timeout-service-a", LocalDateTime.now());
        // 단발 timeout(연속 1회) → 임계(2) 미달로 알림 보류
        RuleResult result = ruleAnalyzer.evaluate(svc, new CollectResult(t1, List.of()));

        assertThat(result.toAlert()).isEmpty();
    }

    @Test
    void consecutive_timeouts_fire_critical() {
        MonitoredService svc = service("timeout-service-b", "USER_API");
        saveTimeout("timeout-service-b", LocalDateTime.now().minusSeconds(60));
        HealthSnapshot t2 = saveTimeout("timeout-service-b", LocalDateTime.now());
        // 연속 2회 → 임계 충족 → CRITICAL 발생
        RuleResult result = ruleAnalyzer.evaluate(svc, new CollectResult(t2, List.of()));

        assertThat(result.toAlert()).hasSize(1);
        assertThat(result.toAlert().get(0).getTriggerType()).isEqualTo("timeout");
        assertThat(result.toAlert().get(0).getSeverity()).isEqualTo(Severity.CRITICAL.code());
    }

    private HealthSnapshot saveTimeout(String serviceName, LocalDateTime at) {
        HealthSnapshot snap = new HealthSnapshot();
        snap.setServiceName(serviceName);
        snap.setStatus(HealthStatus.TIMEOUT.code());
        snap.setCollectedAt(at);
        return snapshotRepo.save(snap);
    }

    private MonitoredService service(String name, String profile) {
        MonitoredService s = new MonitoredService();
        s.setId(10L);
        s.setName(name);
        s.setProfile(profile);
        s.setBaseUrl("http://localhost:19999");
        return s;
    }

    private HealthSnapshot healthySnapshot(String serviceName) {
        HealthSnapshot snap = new HealthSnapshot();
        snap.setServiceName(serviceName);
        snap.setHttpStatus(200);
        snap.setStatus(HealthStatus.OK.code());
        snap.setHealthOutcome("ok");
        snap.setHealthHttpStatus(200);
        snap.setHealthResponseTimeMs(20L);
        snap.setDetailOutcome("ok");
        snap.setDetailHttpStatus(200);
        snap.setDetailResponseTimeMs(35L);
        return snap;
    }
}
