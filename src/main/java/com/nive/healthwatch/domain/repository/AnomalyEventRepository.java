package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author nive
 * @class AnomalyEventRepository
 * @desc anomaly_event 리포지토리. cooldown/dedup·복구 판정·retention 조회 쿼리.
 * @since 2026-07-06
 */
public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {

    /** 동일 service+trigger(+db) 의 최근 이벤트 — cooldown/dedup 판단용. */
    Optional<AnomalyEvent> findFirstByServiceNameAndTriggerTypeAndDbNameOrderByLastDetectedAtDesc(
            String serviceName, String triggerType, String dbName);

    Optional<AnomalyEvent> findFirstByServiceNameAndTriggerTypeAndDbNameIsNullOrderByLastDetectedAtDesc(
            String serviceName, String triggerType);

    /** 정기 리포트 집계 — 기간 내 이벤트. */
    List<AnomalyEvent> findByFirstDetectedAtBetween(LocalDateTime from, LocalDateTime to);

    /** resolved 복구 알림 대상 — 아직 열려있는 이벤트. */
    List<AnomalyEvent> findByServiceNameAndStatusIn(String serviceName, List<String> statuses);

    long deleteByLastDetectedAtBefore(LocalDateTime cutoff);
}
