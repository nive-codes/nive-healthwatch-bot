package com.nive.healthwatch.domain.enums;

/**
 * @author nive
 * @class Severity
 * @desc anomaly 심각도.
 * @since 2026-07-06
 */
public enum Severity {
    WARNING, CRITICAL;

    public String code() {
        return name().toLowerCase();
    }
}
