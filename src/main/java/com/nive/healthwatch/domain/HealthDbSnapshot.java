package com.nive.healthwatch.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * @author nive
 * @class HealthDbSnapshot
 * @desc health_snapshot 하위의 DB별 상태.
 * @since 2026-07-06
 */
@Entity
@Table(name = "health_db_snapshot")
@Getter
@Setter
@NoArgsConstructor
public class HealthDbSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long snapshotId;
    private String serviceName;
    private String dbName;
    private Boolean ok;
    private Long lastQueryLatencyMs;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastFailureAt;
    private String detail;
    private LocalDateTime collectedAt = LocalDateTime.now();
}
