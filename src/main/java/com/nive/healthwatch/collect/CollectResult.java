package com.nive.healthwatch.collect;

import com.nive.healthwatch.domain.HealthDbSnapshot;
import com.nive.healthwatch.domain.HealthSnapshot;

import java.util.List;

/**
 * @author nive
 * @class CollectResult
 * @desc 한 서비스 1회 수집 결과(저장 완료된 스냅샷 + DB 스냅샷들). RuleAnalyzer 입력으로 넘긴다.
 * @since 2026-07-06
 */
public record CollectResult(HealthSnapshot snapshot, List<HealthDbSnapshot> dbSnapshots) {
}
