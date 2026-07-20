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
 * @class AiReport
 * @desc AI CLI 분석 결과.
 * @since 2026-07-06
 */
@Entity
@Table(name = "ai_report")
@Getter
@Setter
@NoArgsConstructor
public class AiReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long anomalyEventId;

    @Column(columnDefinition = "text")
    private String prompt;
    @Column(columnDefinition = "text")
    private String response;

    private Integer exitCode;
    private boolean timedOut;
    private LocalDateTime createdAt = LocalDateTime.now();
}
