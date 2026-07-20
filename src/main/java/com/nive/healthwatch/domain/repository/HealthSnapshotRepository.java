package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.HealthSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author nive
 * @class HealthSnapshotRepository
 * @desc health_snapshot 리포지토리. baseline window·정기 리포트 집계·retention 조회.
 * @since 2026-07-06
 */
public interface HealthSnapshotRepository extends JpaRepository<HealthSnapshot, Long> {

    /** window baseline / 추세 비교용 — 서비스별 최근 스냅샷(최신순). */
    List<HealthSnapshot> findByServiceNameAndCollectedAtGreaterThanEqualOrderByCollectedAtDesc(
            String serviceName, LocalDateTime from);

    /** 정기 리포트 집계용 — 서비스별 기간 스냅샷. */
    List<HealthSnapshot> findByServiceNameAndCollectedAtBetween(
            String serviceName, LocalDateTime from, LocalDateTime to);

    /** timeout 디바운스용 — 서비스별 가장 최근 N개 스냅샷(최신순). PageRequest.of(0, N) 로 개수 지정. */
    List<HealthSnapshot> findByServiceNameOrderByCollectedAtDesc(String serviceName, Pageable pageable);

    long deleteByCollectedAtBefore(LocalDateTime cutoff);
}
