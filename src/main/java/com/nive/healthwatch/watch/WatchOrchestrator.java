package com.nive.healthwatch.watch;

import com.nive.healthwatch.ai.AiAnalysisResult;
import com.nive.healthwatch.ai.AiAnalyzer;
import com.nive.healthwatch.ai.AnomalyContext;
import com.nive.healthwatch.ai.PromptBuilder;
import com.nive.healthwatch.collect.CollectResult;
import com.nive.healthwatch.collect.HealthCollector;
import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.AiReport;
import com.nive.healthwatch.domain.AnomalyEvent;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.repository.AiReportRepository;
import com.nive.healthwatch.notify.NotificationRouter;
import com.nive.healthwatch.rule.RuleAnalyzer;
import com.nive.healthwatch.rule.RuleResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author nive
 * @class WatchOrchestrator
 * @desc 한 프로젝트(monitored_service)에 대한 collect → rule → (trigger 시)AI → notify 파이프라인을 엮는다.
 *       프로젝트 단위로 예외를 격리해 한 서비스의 실패가 다른 서비스·수집 루프를 막지 않는다.
 * @since 2026-07-06
 */
@Slf4j
@Service
public class WatchOrchestrator {

    private final HealthCollector collector;
    private final RuleAnalyzer ruleAnalyzer;
    private final AiAnalyzer aiAnalyzer;
    private final PromptBuilder promptBuilder;
    private final AiReportRepository aiReportRepo;
    private final AlertFormatter formatter;
    private final NotificationRouter router;
    private final HealthWatchProperties props;

    public WatchOrchestrator(HealthCollector collector, RuleAnalyzer ruleAnalyzer, AiAnalyzer aiAnalyzer,
                             PromptBuilder promptBuilder, AiReportRepository aiReportRepo, AlertFormatter formatter,
                             NotificationRouter router, HealthWatchProperties props) {
        this.collector = collector;
        this.ruleAnalyzer = ruleAnalyzer;
        this.aiAnalyzer = aiAnalyzer;
        this.promptBuilder = promptBuilder;
        this.aiReportRepo = aiReportRepo;
        this.formatter = formatter;
        this.router = router;
        this.props = props;
    }

    public CollectResult watch(MonitoredService service) {
        try {
            CollectResult collected = collector.collect(service);
            RuleResult rule = ruleAnalyzer.evaluate(service, collected);

            for (AnomalyEvent event : rule.toAlert()) {
                String aiSummary = analyzeIfEnabled(service, event);
                router.dispatch(service, formatter.anomaly(event, aiSummary));
            }
            for (AnomalyEvent recovered : rule.resolved()) {
                router.dispatch(service, formatter.recovery(recovered));
            }
            return collected;
        } catch (Exception e) {
            log.warn("[Watch] {} 처리 중 예외 — 이 서비스만 skip", service.getName(), e);
            return null;
        }
    }

    private String analyzeIfEnabled(MonitoredService service, AnomalyEvent event) {
        if (!props.getAi().isEnabled()) {
            return null;
        }
        AnomalyContext ctx = new AnomalyContext(
                event.getId(), service.getName(), service.getProfile(), event.getSeverity(),
                event.getTriggerType(), event.getTriggerSummary(), event.getBaselineJson(), event.getCurrentJson());
        AiAnalysisResult result = aiAnalyzer.run(promptBuilder.buildAnomaly(ctx));

        AiReport report = new AiReport();
        report.setAnomalyEventId(event.getId());
        report.setPrompt(result.prompt());
        report.setResponse(result.response());
        report.setExitCode(result.exitCode());
        report.setTimedOut(result.timedOut());
        aiReportRepo.save(report);

        return result.success() ? result.response() : null;
    }
}
