package com.nive.healthwatch.ai;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * @author nive
 * @class PromptBuilder
 * @desc AI prompt 조립기. (1) anomaly 원인 추정 프롬프트, (2) 18시 정기 요약(데일리 다이제스트) 프롬프트를 만든다.
 *       공통 원칙: 장애 판정은 rule 이 하고 AI 는 조언만. 정기 요약은 "담백한 리포트 톤"으로, 리팩토링·DB 구조
 *       변경 제안은 신호가 반복·강할 때만 조심스럽게 1~2개 이내로 한다(매일 잔소리 방지).
 * @since 2026-07-06
 */
@Component
public class PromptBuilder {

    // ── (1) anomaly 원인 추정 ──
    private static final String ANOMALY_COMMON = """
            너는 백엔드 운영을 돕는 분석 보조다. 아래 규칙을 반드시 지켜라.
            - 장애 여부 판정은 이미 rule 이 끝냈다. 너는 '원인 추정 / 영향 범위 / 지금 확인할 것'만 정리한다.
            - 확실하지 않은 것은 반드시 '추정'이라고 표시한다. 근거 없는 단정 금지.
            - baseline(최근 window)과 current(현재 snapshot)의 '차이'를 근거로 말한다.
            - 개발팀이 5분 안에 확인할 수 있는 실행 가능한 항목을 우선순위로 제시한다.
            - 자동 재시작/배포 중단 등 조치는 '제안'만 하고 직접 수행하지 않는다.
            - 과장하지 마라. 데이터가 부족하면 '데이터 부족'이라고 말한다.
            - 메일 리포트 본문에 그대로 들어간다. 아래 섹션 제목을 반드시 그대로 사용한다.
            - 한국어. 아래 형식을 지켜라:
              [장애 원인] 2~4줄
              [영향 범위] 1~2줄 (해당 서비스 컨텍스트 기준)
              [확인 항목]
              1. ...
              2. ...
            """;

    // ── (2) 데일리 다이제스트(정기 요약) ──
    private static final String DIGEST_COMMON = """
            너는 하루 한 번 나가는 서비스 상태 '일일 리포트'를 쓰는 보조다. 아래를 지켜라.
            - 이건 경보가 아니라 담백한 리포트다. 대부분의 날은 '대체로 정상'일 것이고, 그러면 짧게 정상이라고 써라.
            - 숫자를 나열만 하지 말고, 어제 대비/최근 추세에서 '눈에 띄는 변화'가 있으면 그것만 짚어라.
            - 리팩토링, 인덱스/DB 구조 변경, 코드 개선 제안은 '정말 필요할 때만' 한다:
              * 같은 신호가 여러 번 반복되거나, 추세가 뚜렷이 나빠질 때만.
              * 그럴 때도 단정 말고 '한번 살펴보면 좋겠다' 수준으로, 최대 1~2개.
              * 특별한 신호가 없으면 제안 섹션은 아예 '없음'이라고 쓴다. 매일 개선 잔소리를 하지 마라.
            - 추정은 '추정'이라고 표시. 데이터가 적으면 그렇다고 말한다.
            - 메일 리포트 본문에 그대로 들어간다. 섹션 제목을 유지하고, 프로젝트별 요약이 있으면 프로젝트명을 명확히 쓴다.
            - 출력은 메일용 HTML 템플릿의 요약/프로젝트/전체 총평 태그 내부에 삽입될 텍스트다.
            - 전체 HTML, table, div, markdown table, code block 을 만들지 말고 섹션별 자연어만 작성한다.
            - 한국어. 아래 형식을 지켜라:
              [오늘 상태] 한두 줄 (정상/주의/이상 중 하나로 시작)
              [눈에 띄는 점] 없으면 '특이사항 없음'
              [확인 제안] 없으면 '없음' (있으면 1~2개, 조심스럽게)
            """;

    /** anomaly 발생 시 원인 추정 프롬프트. */
    public String buildAnomaly(AnomalyContext ctx) {
        return ANOMALY_COMMON + "\n"
                + profileContext(ctx.profile()) + "\n"
                + "service: " + ctx.serviceName() + "\n"
                + "severity: " + ctx.severity() + "\n"
                + "trigger: " + ctx.triggerType() + " — " + safe(ctx.triggerSummary()) + "\n"
                + "baseline(recent window):\n" + safe(ctx.baselineJson()) + "\n"
                + "current snapshot:\n" + safe(ctx.currentJson()) + "\n";
    }

    /**
     * 18시 정기 요약 프롬프트. statsText 는 최근 24시간 집계(수집 성공률/latency/p95/anomaly 등).
     * anomalyText 는 그날 발생한 anomaly 요약(없으면 "없음").
     */
    public String buildDailyDigest(String serviceName, String profile, String statsText, String anomalyText) {
        return DIGEST_COMMON + "\n"
                + profileContext(profile) + "\n"
                + "service: " + serviceName + "\n"
                + "최근 24시간 집계:\n" + safe(statsText) + "\n"
                + "오늘 발생한 anomaly:\n" + safe(anomalyText) + "\n";
    }

    /** 여러 프로젝트를 하나의 18시 리포트 메일로 묶는 프롬프트. */
    public String buildDailyCombinedDigest(String statsText, String anomalyText, LocalDateTime now) {
        boolean friday = now != null && now.getDayOfWeek() == DayOfWeek.FRIDAY;
        boolean monthEnd = now != null && now.toLocalDate().equals(YearMonth.from(now).atEndOfMonth());
        return DIGEST_COMMON + "\n"
                + "scope: registered backend services combined report\n"
                + "reportDateTime: " + safe(now == null ? null : now.toString()) + "\n"
                + "프로젝트별 집계:\n" + safe(statsText) + "\n"
                + "오늘 발생한 anomaly:\n" + safe(anomalyText) + "\n"
                + "작성 지침:\n"
                + "- 전체 총평에 금일 상태를 먼저 요약한다.\n"
                + "- 일반 일간 리포트에서는 금일 상태와 금일 anomaly 중심으로 작성한다.\n"
                + "- todayIsFriday=" + friday + " 이 true 일 때만 금주 anomaly 로그와 latency 변화 형태를 자세히 설명한다.\n"
                + "- todayIsMonthEnd=" + monthEnd + " 이 true 일 때만 월 기준 metric 정상률/latency/p95/anomaly 흐름에 대한 총평을 추가한다.\n"
                + "- 메일 HTML 템플릿의 각 영역 안에 들어갈 문장만 작성한다. HTML 태그를 직접 출력하지 않는다.\n"
                + "- 반드시 아래 섹션 제목을 사용한다:\n"
                + "  [오늘 상태]\n"
                + (friday || monthEnd ? "  [금주 추이]\n" : "")
                + (friday ? "  [금주 로그]\n" : "")
                + (monthEnd ? "  [월간 총평]\n" : "")
                + "  [확인 제안]\n";
    }

    private String profileContext(String profile) {
        if (profile == null) {
            return "";
        }
        return switch (profile) {
            case "USER_API" -> """
                    context: USER_API 는 사용자-facing API 다.
                    사용자 요청, 알림, 공지, 세션 흐름에 영향을 줄 수 있다.
                    DB 지연·API latency 증가는 사용자 체감 장애로 이어지므로 p95 latency 와 DB latency 를 특히 중요하게 본다.
                    """;
            case "ADMIN_API" -> """
                    context: ADMIN_API 는 관리자/운영자용 API 다.
                    운영 업무, 설정 변경, 감사 로그, 권한 기반 관리 기능에 영향을 줄 수 있다.
                    트래픽이 낮을 수 있으므로 requestCount 가 낮더라도 DB 장애·인증/감사 로그 오류를 중요하게 본다.
                    """;
            case "DRIVER_API" -> """
                    context: DRIVER_API 는 현장/모바일 클라이언트용 API 다.
                    실시간 상태 변경, 인증, 위치성 데이터, 작업 처리 흐름에 영향을 줄 수 있다.
                    운영 시간대에는 latency·DB 지연이 현장 처리에 직접 영향을 준다.
                    """;
            default -> "";
        };
    }

    private String safe(String s) {
        return s == null ? "(none)" : s;
    }
}
