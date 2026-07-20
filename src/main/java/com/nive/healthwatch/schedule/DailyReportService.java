package com.nive.healthwatch.schedule;

import com.nive.healthwatch.ai.AiAnalysisResult;
import com.nive.healthwatch.ai.AiAnalyzer;
import com.nive.healthwatch.ai.PromptBuilder;
import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.AnomalyEvent;
import com.nive.healthwatch.domain.HealthSnapshot;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.enums.HealthStatus;
import com.nive.healthwatch.domain.repository.AnomalyEventRepository;
import com.nive.healthwatch.domain.repository.HealthSnapshotRepository;
import com.nive.healthwatch.notify.AlertMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author nive
 * @class DailyReportService
 * @desc 프로젝트별 18시 다이제스트 생성. 최근 24시간을 집계하고, LLM 으로 담백한 리포트 내러티브를 만든다.
 *       LLM 실패/비활성 시 rule 집계 텍스트로 폴백해 요약 메일이 반드시 나가게 한다.
 * @since 2026-07-06
 */
@Slf4j
@Service
public class DailyReportService {

    private final HealthWatchProperties props;
    private final HealthSnapshotRepository snapshotRepo;
    private final AnomalyEventRepository anomalyRepo;
    private final PromptBuilder promptBuilder;
    private final AiAnalyzer aiAnalyzer;

    public DailyReportService(HealthWatchProperties props, HealthSnapshotRepository snapshotRepo,
                              AnomalyEventRepository anomalyRepo, PromptBuilder promptBuilder, AiAnalyzer aiAnalyzer) {
        this.props = props;
        this.snapshotRepo = snapshotRepo;
        this.anomalyRepo = anomalyRepo;
        this.promptBuilder = promptBuilder;
        this.aiAnalyzer = aiAnalyzer;
    }

    /** 한 프로젝트의 최근 24시간 다이제스트 메시지(제목/본문). */
    public AlertMessage buildDigest(MonitoredService service, LocalDateTime now) {
        LocalDateTime from = now.minusHours(24);
        List<HealthSnapshot> snaps = snapshotRepo.findByServiceNameAndCollectedAtBetween(service.getName(), from, now);
        List<AnomalyEvent> anomalies = anomalyRepo.findByFirstDetectedAtBetween(from, now).stream()
                .filter(a -> service.getName().equals(a.getServiceName())).toList();

        String statsText = statsText(snaps, anomalies.size());
        String anomalyText = anomalyText(anomalies);

        String body;
        if (props.isDailyReportUseLlm() && props.getAi().isEnabled()) {
            String prompt = promptBuilder.buildDailyDigest(service.getName(), service.getProfile(), statsText, anomalyText);
            AiAnalysisResult result = aiAnalyzer.run(prompt);
            body = result.success() && result.response() != null && !result.response().isBlank()
                    ? result.response()
                    : fallbackBody(statsText, anomalyText); // LLM 실패 → rule 집계 폴백
            if (!result.success()) {
                log.info("[DailyReport] {} LLM 다이제스트 실패 → rule 폴백", service.getName());
            }
        } else {
            body = fallbackBody(statsText, anomalyText);
        }
        return AlertMessage.report("[daily] " + service.getName() + " 상태 요약", body);
    }

    /** 활성 프로젝트 전체를 하나의 18시 리포트 메일로 묶는다. */
    public AlertMessage buildCombinedDigest(List<MonitoredService> services, LocalDateTime now) {
        StringBuilder stats = new StringBuilder();
        StringBuilder anomalies = new StringBuilder();
        StringBuilder projectSections = new StringBuilder();
        int totalSamples = 0;
        int totalAnomalies = 0;
        int warningProjects = 0;
        boolean weeklyReport = isWeeklyReport(now);
        boolean monthlyReport = isMonthlyReport(now);
        for (MonitoredService service : services) {
            LocalDateTime from = now.minusHours(24);
            List<HealthSnapshot> snaps = snapshotRepo.findByServiceNameAndCollectedAtBetween(service.getName(), from, now);
            List<HealthSnapshot> weekSnaps = snapshotRepo.findByServiceNameAndCollectedAtBetween(service.getName(), now.minusDays(7), now);
            List<AnomalyEvent> serviceAnomalies = anomalyRepo.findByFirstDetectedAtBetween(from, now).stream()
                    .filter(a -> service.getName().equals(a.getServiceName())).toList();
            totalSamples += snaps.size();
            totalAnomalies += serviceAnomalies.size();
            if (!serviceAnomalies.isEmpty() || snaps.stream().anyMatch(s -> !HealthStatus.OK.code().equals(s.getStatus()))) {
                warningProjects++;
            }

            String dayStats = statsText(snaps, serviceAnomalies.size());
            stats.append("[프로젝트: ").append(service.getName()).append("]\n")
                    .append("금일\n").append(dayStats).append("\n");
            if (weeklyReport || monthlyReport) {
                String weekStats = statsText(weekSnaps, anomalyCount(service.getName(), now.minusDays(7), now));
                stats.append("금주\n").append(weekStats).append("\n");
            }
            if (monthlyReport) {
                List<HealthSnapshot> monthSnaps = snapshotRepo.findByServiceNameAndCollectedAtBetween(
                        service.getName(), YearMonth.from(now).atDay(1).atStartOfDay(), now);
                String monthStats = statsText(monthSnaps, anomalyCount(service.getName(), YearMonth.from(now).atDay(1).atStartOfDay(), now));
                stats.append("월간\n").append(monthStats).append("\n");
            }
            stats.append("\n");
            anomalies.append("[프로젝트: ").append(service.getName()).append("]\n")
                    .append(anomalyText(serviceAnomalies)).append("\n\n");
            projectSections.append("[프로젝트: ").append(service.getName()).append("]\n")
                    .append("상태: ").append(projectStatus(snaps, serviceAnomalies)).append("\n")
                    .append("서술: ").append(projectNarrative(snaps, serviceAnomalies)).append("\n")
                    .append("이상 징후:\n").append(anomalyText(serviceAnomalies)).append("\n")
                    .append("메트릭:\n").append(metricLines(snaps, weekSnaps)).append("\n\n");
        }

        String aiNarrative;
        if (props.isDailyReportUseLlm() && props.getAi().isEnabled()) {
            String prompt = promptBuilder.buildDailyCombinedDigest(stats.toString(), anomalies.toString(), now);
            AiAnalysisResult result = aiAnalyzer.run(prompt);
            aiNarrative = result.success() && result.response() != null && !result.response().isBlank()
                    ? result.response().trim()
                    : fallbackOverall(stats.toString(), anomalies.toString(), now);
            if (!result.success()) {
                log.info("[DailyReport] 통합 LLM 다이제스트 실패 → rule 폴백");
            }
        } else {
            aiNarrative = fallbackOverall(stats.toString(), anomalies.toString(), now);
        }

        String body = "[요약]\n"
                + summaryLine(services.size(), warningProjects, totalSamples, totalAnomalies) + "\n\n"
                + projectSections
                + "[전체 총평]\n"
                + aiNarrative + "\n\n"
                + "[다음 리포트]\n매일 17:00 KST";
        return AlertMessage.report(reportTitle(now), body, "user-api / admin-api / driver-api");
    }

    private String statsText(List<HealthSnapshot> snaps, int anomalyCount) {
        int total = snaps.size();
        long ok = snaps.stream().filter(s -> HealthStatus.OK.code().equals(s.getStatus())).count();
        double successRate = total == 0 ? 0 : (double) ok / total * 100;
        double avgLatency = snaps.stream().map(HealthSnapshot::getAvgLatencyMs5m)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0);
        double avgP95 = snaps.stream().map(HealthSnapshot::getP95LatencyMs5m)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0);
        return String.format("- 수집 표본: %d건, 정상률: %.1f%% (%d/%d)%n"
                        + "- 평균 latency: %.0fms, 평균 p95: %.0fms%n"
                        + "- anomaly 발생: %d건",
                total, successRate, ok, total, avgLatency, avgP95, anomalyCount);
    }

    private String anomalyText(List<AnomalyEvent> anomalies) {
        if (anomalies.isEmpty()) {
            return "없음";
        }
        StringBuilder sb = new StringBuilder();
        for (AnomalyEvent a : anomalies) {
            sb.append("- [").append(a.getSeverity()).append("] ")
                    .append(a.getTriggerType());
            if (a.getDbName() != null) {
                sb.append("(").append(a.getDbName()).append(")");
            }
            sb.append(" — ").append(a.getTriggerSummary() == null ? "" : a.getTriggerSummary()).append("\n");
        }
        return sb.toString();
    }

    /** LLM 없이도 나가는 최소 리포트. */
    private String fallbackBody(String statsText, String anomalyText) {
        return "최근 24시간 요약\n\n" + statsText + "\n\nanomaly:\n" + anomalyText;
    }

    private String fallbackCombinedBody(String statsText, String anomalyText) {
        List<String> sections = new ArrayList<>();
        sections.add("[오늘 상태]\n프로젝트별 최근 24시간 health 수집 결과를 요약합니다.");
        sections.add("[프로젝트별 지표]\n" + statsText.strip());
        sections.add("[눈에 띄는 점]\n" + anomalyText.strip());
        sections.add("[확인 제안]\n반복 anomaly 가 있는 프로젝트는 최근 배포, DB 연결, p95 latency 추이를 확인하세요.");
        return String.join("\n\n", sections);
    }

    private int anomalyCount(String serviceName, LocalDateTime from, LocalDateTime to) {
        return (int) anomalyRepo.findByFirstDetectedAtBetween(from, to).stream()
                .filter(a -> serviceName.equals(a.getServiceName()))
                .count();
    }

    private String projectStatus(List<HealthSnapshot> snaps, List<AnomalyEvent> anomalies) {
        if (!anomalies.isEmpty()) {
            return "주의";
        }
        if (snaps.isEmpty()) {
            return "데이터 부족";
        }
        return snaps.stream().allMatch(s -> HealthStatus.OK.code().equals(s.getStatus())) ? "정상" : "주의";
    }

    private String projectNarrative(List<HealthSnapshot> snaps, List<AnomalyEvent> anomalies) {
        if (!anomalies.isEmpty()) {
            return "금일 anomaly 가 기록되어 확인이 필요합니다.";
        }
        if (snaps.isEmpty()) {
            return "최근 24시간 수집 표본이 없어 상태 판단 데이터가 부족합니다.";
        }
        long ok = snaps.stream().filter(s -> HealthStatus.OK.code().equals(s.getStatus())).count();
        return "최근 24시간 수집 표본 " + snaps.size() + "건 중 정상 " + ok + "건입니다.";
    }

    private String metricLines(List<HealthSnapshot> daySnaps, List<HealthSnapshot> weekSnaps) {
        HealthSnapshot latest = daySnaps.stream()
                .filter(Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(null);
        double weekAvgLatency = weekSnaps.stream().map(HealthSnapshot::getAvgLatencyMs5m)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0);
        double weekP95 = weekSnaps.stream().map(HealthSnapshot::getP95LatencyMs5m)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0);
        if (latest == null) {
            return "- 수집 표본 | 없음 | - | 데이터 부족";
        }
        return "- HTTP 상태 | " + latest.getHttpStatus() + " | - | " + state(latest.getHttpStatus() != null && latest.getHttpStatus() == 200) + "\n"
                + "- 평균 latency | " + fmtNullable(latest.getAvgLatencyMs5m()) + "ms | 금주 평균 " + fmt(weekAvgLatency) + "ms | "
                + state(latest.getAvgLatencyMs5m() == null || weekAvgLatency == 0 || latest.getAvgLatencyMs5m() <= weekAvgLatency * 1.5) + "\n"
                + "- p95 latency | " + fmtNullable(latest.getP95LatencyMs5m()) + "ms | 금주 평균 " + fmt(weekP95) + "ms | "
                + state(latest.getP95LatencyMs5m() == null || weekP95 == 0 || latest.getP95LatencyMs5m() <= weekP95 * 1.5) + "\n"
                + "- error rate | " + pct(latest.getErrorRate5m() == null ? 0 : latest.getErrorRate5m()) + " | - | "
                + state(latest.getErrorRate5m() == null || latest.getErrorRate5m() < props.getRule().getErrorRateThresholdPercent());
    }

    private String summaryLine(int projects, int warningProjects, int totalSamples, int totalAnomalies) {
        if (warningProjects == 0 && totalAnomalies == 0) {
            return projects + "개 프로젝트 모두 현재 정상 범위입니다. 최근 24시간 수집 표본은 총 " + totalSamples + "건입니다.";
        }
        return projects + "개 프로젝트 중 " + warningProjects + "개에서 확인 대상 신호가 있습니다. 금일 anomaly " + totalAnomalies + "건입니다.";
    }

    private String fallbackOverall(String statsText, String anomalyText, LocalDateTime now) {
        StringBuilder sb = new StringBuilder();
        sb.append("[오늘 상태]\n금일 수집 지표와 anomaly 기록을 기준으로 상태를 요약했습니다.\n");
        if (isWeeklyReport(now) || isMonthlyReport(now)) {
            sb.append("\n[금주 추이]\nDB에 저장된 최근 7일 표본 기준으로 정상률, latency, p95 변화 방향을 함께 확인해야 합니다.\n");
            if (isWeeklyReport(now)) {
                sb.append("금요일 리포트이므로 금주 anomaly와 latency 변화 로그를 평소보다 자세히 검토하세요.\n");
            }
        }
        if (isMonthlyReport(now)) {
            sb.append("\n[월간 총평]\n월말 리포트이므로 월간 표본 기준의 정상률과 latency 변화를 총평에 반영하세요.\n");
        }
        sb.append("\n[원본 집계]\n").append(statsText).append("\n[anomaly]\n").append(anomalyText);
        return sb.toString();
    }

    private String reportTitle(LocalDateTime now) {
        if (isMonthlyReport(now)) {
            return "[monthly] [report] 월간 서버 보고서";
        }
        if (isWeeklyReport(now)) {
            return "[weekly] [report] 주간 서버 보고서";
        }
        return "[daily] [report] 일간 서버 보고서";
    }

    private boolean isWeeklyReport(LocalDateTime now) {
        return now != null && now.getDayOfWeek() == java.time.DayOfWeek.FRIDAY;
    }

    private boolean isMonthlyReport(LocalDateTime now) {
        return now != null && now.toLocalDate().equals(YearMonth.from(now).atEndOfMonth());
    }

    private String state(boolean ok) {
        return ok ? "정상" : "주의";
    }

    private String fmtNullable(Double v) {
        return v == null ? "-" : fmt(v);
    }

    private String fmt(double v) {
        return String.format("%.0f", v);
    }

    private String pct(double v) {
        return String.format("%.2f%%", v);
    }
}
