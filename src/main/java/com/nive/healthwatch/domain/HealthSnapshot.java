package com.nive.healthwatch.domain;

import jakarta.persistence.Column;
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
 * @class HealthSnapshot
 * @desc API 단위 health/detail 수집 스냅샷. status 는 HealthStatus.code() 문자열.
 * @since 2026-07-06
 */
@Entity
@Table(name = "health_snapshot")
@Getter
@Setter
@NoArgsConstructor
public class HealthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String serviceName;
    private String status;
    private Integer httpStatus;
    private Long responseTimeMs;

    @Column(name = "health_outcome")
    private String healthOutcome;

    @Column(name = "health_http_status")
    private Integer healthHttpStatus;

    @Column(name = "health_response_time_ms")
    private Long healthResponseTimeMs;

    @Column(name = "detail_outcome")
    private String detailOutcome;

    @Column(name = "detail_http_status")
    private Integer detailHttpStatus;

    @Column(name = "detail_response_time_ms")
    private Long detailResponseTimeMs;

    private Long uptimeSec;
    private Double workingSetMb;
    private Double gcHeapMb;
    private Integer threadCount;

    @Column(name = "request_count_1m")
    private Long requestCount1m;

    @Column(name = "request_count_5m")
    private Long requestCount5m;

    @Column(name = "error_count_5m")
    private Long errorCount5m;

    @Column(name = "error_rate_5m")
    private Double errorRate5m;

    @Column(name = "avg_latency_ms_5m")
    private Double avgLatencyMs5m;

    @Column(name = "p95_latency_ms_5m")
    private Double p95LatencyMs5m;

    @Column(name = "in_flight_request_count")
    private Integer inFlightRequestCount;

    @Column(columnDefinition = "text")
    private String rawPayload;

    private LocalDateTime collectedAt = LocalDateTime.now();
}
