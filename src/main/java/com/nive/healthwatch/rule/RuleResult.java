package com.nive.healthwatch.rule;

import com.nive.healthwatch.domain.AnomalyEvent;

import java.util.List;

/**
 * @author nive
 * @class RuleResult
 * @desc 한 서비스 1회 rule 평가 결과.
 *       toAlert: 신규 발생 또는 cooldown 경과로 다시 알릴 anomaly(AI 분석 대상).
 *       resolved: 이번 사이클에 조건이 사라져 복구된 anomaly(복구 알림 1회, AI 미대상).
 * @since 2026-07-06
 */
public record RuleResult(List<AnomalyEvent> toAlert, List<AnomalyEvent> resolved) {
}
