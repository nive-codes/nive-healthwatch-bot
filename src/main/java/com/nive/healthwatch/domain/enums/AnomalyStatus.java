package com.nive.healthwatch.domain.enums;

/**
 * @author nive
 * @class AnomalyStatus
 * @desc anomaly_event 라이프사이클.
 * @since 2026-07-06
 */
public enum AnomalyStatus {
    OPEN, NOTIFIED, RESOLVED, SUPPRESSED;

    public String code() {
        return name().toLowerCase();
    }
}
