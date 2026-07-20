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
 * @class MonitoredService
 * @desc 감시 대상 서버(프로젝트) 레지스트리 엔티티. "무엇을 감시할지"의 SSoT — 스케줄러가 이 목록을 순회한다.
 * @since 2026-07-06
 */
@Entity
@Table(name = "monitored_service")
@Getter
@Setter
@NoArgsConstructor
public class MonitoredService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String baseUrl;
    private String profile;
    private boolean enabled = true;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
