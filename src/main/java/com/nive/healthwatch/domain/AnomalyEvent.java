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
 * @class AnomalyEvent
 * @desc Rule trigger 이벤트. severity/status 는 enum code() 문자열.
 * @since 2026-07-06
 */
@Entity
@Table(name = "anomaly_event")
@Getter
@Setter
@NoArgsConstructor
public class AnomalyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String serviceName;
    private String severity;
    private String triggerType;
    private String dbName;
    private String triggerSummary;

    @Column(columnDefinition = "text")
    private String baselineJson;
    @Column(columnDefinition = "text")
    private String currentJson;

    private String status;
    private LocalDateTime firstDetectedAt = LocalDateTime.now();
    private LocalDateTime lastDetectedAt = LocalDateTime.now();
    private LocalDateTime lastNotifiedAt;
}
