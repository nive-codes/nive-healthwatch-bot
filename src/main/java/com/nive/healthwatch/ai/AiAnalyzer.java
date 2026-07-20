package com.nive.healthwatch.ai;

/**
 * @author nive
 * @class AiAnalyzer
 * @desc AI 분석 엔진 포트(우리 로컬 에이전트 AiEnginePort 와 같은 역할). 완성된 prompt 를 받아 CLI subprocess 로 실행한다.
 *       엔진(codex/claude 등)은 설정 command 로 교체하며, 프롬프트 조립은 호출자(PromptBuilder)가 담당한다.
 * @since 2026-07-06
 */
public interface AiAnalyzer {

    AiAnalysisResult run(String prompt);
}
