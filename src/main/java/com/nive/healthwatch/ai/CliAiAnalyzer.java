package com.nive.healthwatch.ai;

import com.nive.healthwatch.config.HealthWatchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * @author nive
 * @class CliAiAnalyzer
 * @desc CLI subprocess 기반 AI 분석기(ProcessBuilder). 우리 로컬 에이전트 ClaudeCodeAdapter 의
 *       timeout/destroyForcibly/exit code/drain 패턴을 이식했다.
 *       설정 command(예: [codex, exec, --json])의 마지막에 prompt 를 인자로 붙여 실행한다.
 *       동시 실행은 Semaphore(maxConcurrent)로 제한하고, timeout/실패는 예외 대신 결과 객체로 표현한다.
 * @since 2026-07-06
 */
@Slf4j
@Component
public class CliAiAnalyzer implements AiAnalyzer {

    private final HealthWatchProperties.Ai config;
    private final Semaphore slots;

    public CliAiAnalyzer(HealthWatchProperties props) {
        this.config = props.getAi();
        this.slots = new Semaphore(Math.max(1, config.getMaxConcurrent()));
    }

    @Override
    public AiAnalysisResult run(String prompt) {
        if (!config.isEnabled() || config.getCommand() == null || config.getCommand().isEmpty()) {
            return AiAnalysisResult.failed(prompt, null, "AI 비활성 또는 command 미설정");
        }
        if (!slots.tryAcquire()) {
            log.info("[AI] 동시 실행 한도({}) 초과 → 이번 요청은 AI 생략, 호출자 폴백 사용",
                    config.getMaxConcurrent());
            return AiAnalysisResult.failed(prompt, null, "동시 실행 한도 초과");
        }
        try {
            return exec(prompt);
        } finally {
            slots.release();
        }
    }

    private AiAnalysisResult exec(String prompt) {
        List<String> command = new ArrayList<>(config.getCommand());
        command.add(prompt); // 마지막 인자로 prompt 전달
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            process = pb.start();

            // 일부 CLI 는 stdin 을 기다리므로 즉시 닫는다.
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.flush();
            } catch (Exception ignore) {
                // ignore
            }

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread outThread = drain(process.getInputStream(), out, config.getMaxOutputChars());
            Thread errThread = drain(process.getErrorStream(), err, 4000);

            boolean finished = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[AI] 분석 타임아웃({}s) → fallback", config.getTimeoutSeconds());
                return AiAnalysisResult.timedOut(prompt);
            }
            outThread.join(2000);
            errThread.join(2000);

            int exit = process.exitValue();
            String response = extractText(out.toString());
            if (exit != 0) {
                log.warn("[AI] 비정상 종료 exit={} stderr={}", exit, truncate(err.toString(), 300));
                return AiAnalysisResult.failed(prompt, exit,
                        response.isBlank() ? truncate(err.toString(), 1000) : response);
            }
            if (response.isBlank()) {
                return AiAnalysisResult.failed(prompt, exit, "빈 응답");
            }
            return AiAnalysisResult.ok(prompt, response, exit);
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            log.warn("[AI] 실행 예외 → fallback", e);
            return AiAnalysisResult.failed(prompt, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** stdout/stderr 비동기 수집 스레드. maxChars 초과분은 버려 과다 output 을 막는다. */
    private Thread drain(InputStream in, StringBuilder sink, int maxChars) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sink.length() < maxChars) {
                        sink.append(line).append('\n');
                    }
                }
            } catch (Exception ignore) {
                // 프로세스 종료 시 stream 닫힘 — 무시
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * stream-json(NDJSON) 이면 text/result 필드를 모아 사람이 읽을 텍스트로 만들고,
     * 아니면 plain text 그대로 반환한다. 파싱 실패는 원문 fallback.
     */
    private String extractText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return trimmed; // plain text
        }
        // NDJSON 라인들에서 흔한 텍스트 키를 최선 노력으로 추출. 실패 시 원문.
        StringBuilder collected = new StringBuilder();
        for (String line : trimmed.split("\\r?\\n")) {
            String l = line.trim();
            String v = jsonStringValue(l, "\"text\"");
            if (v == null) {
                v = jsonStringValue(l, "\"result\"");
            }
            if (v == null) {
                v = jsonStringValue(l, "\"content\"");
            }
            if (v != null) {
                collected.append(v);
            }
        }
        return collected.length() > 0 ? collected.toString() : trimmed;
    }

    /** 아주 단순한 "key":"value" 추출(이스케이프 최소 처리). 정교한 파싱은 엔진 확정 후 개선. */
    private String jsonStringValue(String line, String quotedKey) {
        int k = line.indexOf(quotedKey);
        if (k < 0) {
            return null;
        }
        int colon = line.indexOf(':', k + quotedKey.length());
        if (colon < 0) {
            return null;
        }
        int firstQuote = line.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = firstQuote + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(n);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
