package com.nive.healthwatch.collect;

import com.nive.healthwatch.config.HealthWatchProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * @author nive
 * @class HealthClient
 * @desc /health, /health/detail 를 호출하는 얇은 HTTP 클라이언트. java.net.http 사용(우리 로컬 에이전트와 동일 스택).
 *       timeout/에러는 예외 대신 HttpProbe.outcome 으로 표현해 수집 루프를 막지 않는다.
 * @since 2026-07-06
 */
@Component
public class HealthClient {

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public HealthClient(HealthWatchProperties props) {
        this.requestTimeout = Duration.ofSeconds(props.getRequestTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
    }

    public HttpProbe get(String url) {
        return get(url, null, null);
    }

    public HttpProbe get(String url, String tokenHeader, String token) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .GET();
            if (tokenHeader != null && !tokenHeader.isBlank() && token != null && !token.isBlank()) {
                builder.header(tokenHeader, token);
            }
            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = elapsedMs(start);
            if (response.statusCode() / 100 == 2) {
                return HttpProbe.ok(response.statusCode(), response.body(), elapsed);
            }
            return HttpProbe.httpError(response.statusCode(), response.body(), elapsed);
        } catch (HttpTimeoutException e) {
            return HttpProbe.timeout(elapsedMs(start));
        } catch (Exception e) {
            return HttpProbe.error(elapsedMs(start));
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
