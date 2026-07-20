package com.nive.healthwatch.ai;

/**
 * @author nive
 * @class AnomalyContext
 * @desc AI 분석에 넘길 anomaly 컨텍스트. rule 이 이미 판정한 결과(severity/triggerSummary)와
 *       비교 근거(baseline/current)를 담는다. AI 는 이걸로 원인 추정/확인항목만 제안한다.
 * @since 2026-07-06
 */
public record AnomalyContext(
        Long anomalyEventId,
        String serviceName,
        String profile,
        String severity,
        String triggerType,
        String triggerSummary,
        String baselineJson,
        String currentJson) {
}
