package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.HealthDbSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author nive
 * @class HealthDbSnapshotRepository
 * @desc health_db_snapshot 리포지토리. DB latency baseline window 조회·retention 삭제.
 * @since 2026-07-06
 */
public interface HealthDbSnapshotRepository extends JpaRepository<HealthDbSnapshot, Long> {

    List<HealthDbSnapshot> findByServiceNameAndDbNameAndCollectedAtGreaterThanEqualOrderByCollectedAtDesc(
            String serviceName, String dbName, LocalDateTime from);

    long deleteByCollectedAtBefore(LocalDateTime cutoff);
}
