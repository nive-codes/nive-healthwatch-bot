package com.nive.healthwatch.domain.enums;

/**
 * @author nive
 * @class HealthStatus
 * @desc 수집 스냅샷의 종합 상태.
 * @since 2026-07-06
 */
public enum HealthStatus {
    OK, DEGRADED, TIMEOUT, ERROR;

    public String code() {
        return name().toLowerCase();
    }
}
