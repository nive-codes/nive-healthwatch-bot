package com.nive.healthwatch.ai;

/**
 * @author nive
 * @class AiAnalysisResult
 * @desc AI CLI 분석 결과. success=false 여도 rule fallback 알림은 별도로 나간다.
 * @since 2026-07-06
 */
public record AiAnalysisResult(String prompt, String response, Integer exitCode, boolean timedOut, boolean success) {

    public static AiAnalysisResult ok(String prompt, String response, int exitCode) {
        return new AiAnalysisResult(prompt, response, exitCode, false, true);
    }

    public static AiAnalysisResult timedOut(String prompt) {
        return new AiAnalysisResult(prompt, null, null, true, false);
    }

    public static AiAnalysisResult failed(String prompt, Integer exitCode, String response) {
        return new AiAnalysisResult(prompt, response, exitCode, false, false);
    }
}
