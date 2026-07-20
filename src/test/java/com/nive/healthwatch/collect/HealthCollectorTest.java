package com.nive.healthwatch.collect;

import com.nive.healthwatch.domain.HealthDbSnapshot;
import com.nive.healthwatch.domain.HealthSnapshot;
import com.nive.healthwatch.domain.MonitoredService;
import com.nive.healthwatch.domain.enums.HealthStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author nive
 * @class HealthCollectorTest
 * @desc health/detail payload 호환성 테스트 — 표준 샘플 필드와 토큰 오류 payload 를 검증한다.
 * @since 2026-07-06
 */
@SpringBootTest
@Transactional
class HealthCollectorTest {

    @Autowired
    HealthCollector collector;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parses_standard_health_detail_sample() throws Exception {
        startServer(healthOk(), detailOk());

        CollectResult result = collector.collect(service(baseUrl()));

        HealthSnapshot snap = result.snapshot();
        assertThat(snap.getStatus()).isEqualTo(HealthStatus.OK.code());
        assertThat(snap.getHttpStatus()).isEqualTo(200);
        assertThat(snap.getHealthOutcome()).isEqualTo("ok");
        assertThat(snap.getHealthHttpStatus()).isEqualTo(200);
        assertThat(snap.getHealthResponseTimeMs()).isNotNull();
        assertThat(snap.getDetailOutcome()).isEqualTo("ok");
        assertThat(snap.getDetailHttpStatus()).isEqualTo(200);
        assertThat(snap.getDetailResponseTimeMs()).isNotNull();
        assertThat(snap.getUptimeSec()).isEqualTo(30);
        assertThat(snap.getWorkingSetMb()).isEqualTo(136);
        assertThat(snap.getGcHeapMb()).isEqualTo(97);
        assertThat(snap.getThreadCount()).isEqualTo(31);
        assertThat(snap.getRequestCount1m()).isZero();
        assertThat(snap.getRequestCount5m()).isZero();
        assertThat(snap.getErrorCount5m()).isZero();
        assertThat(snap.getErrorRate5m()).isZero();
        assertThat(snap.getAvgLatencyMs5m()).isZero();
        assertThat(snap.getP95LatencyMs5m()).isZero();
        assertThat(snap.getInFlightRequestCount()).isZero();
        assertThat(snap.getRawPayload()).contains("\"checkedAt\"");

        assertThat(result.dbSnapshots()).hasSize(5);
        HealthDbSnapshot reportingDb = result.dbSnapshots().stream()
                .filter(db -> "ReportingDb".equals(db.getDbName()))
                .findFirst()
                .orElseThrow();
        assertThat(reportingDb.getOk()).isTrue();
        assertThat(reportingDb.getLastQueryLatencyMs()).isEqualTo(1074);
        assertThat(reportingDb.getLastSuccessAt()).isEqualTo(LocalDateTime.of(2026, 7, 6, 9, 59, 36, 725015000));
        assertThat(reportingDb.getLastFailureAt()).isNull();

        HealthDbSnapshot fastest = result.dbSnapshots().stream()
                .min(Comparator.comparing(HealthDbSnapshot::getLastQueryLatencyMs))
                .orElseThrow();
        assertThat(fastest.getDbName()).isEqualTo("ServiceDb");
        assertThat(fastest.getLastQueryLatencyMs()).isEqualTo(12);
    }

    @Test
    void marks_unauthorized_payload_as_error_even_when_http_is_200() throws Exception {
        String unauthorized = """
                {
                  "status": "unauthorized",
                  "message": "Healthcheck token is missing or invalid."
                }
                """;
        startServer(unauthorized, unauthorized);

        CollectResult result = collector.collect(service(baseUrl()));

        assertThat(result.snapshot().getHttpStatus()).isEqualTo(200);
        assertThat(result.snapshot().getStatus()).isEqualTo(HealthStatus.ERROR.code());
        assertThat(result.dbSnapshots()).isEmpty();
    }

    private MonitoredService service(String baseUrl) {
        MonitoredService s = new MonitoredService();
        s.setName("sample-api");
        s.setProfile("ADMIN_API");
        s.setBaseUrl(baseUrl);
        return s;
    }

    private void startServer(String healthBody, String detailBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            if ("/health/detail".equals(exchange.getRequestURI().getPath())) {
                writeJson(exchange, detailBody);
                return;
            }
            writeJson(exchange, healthBody);
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void writeJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String healthOk() {
        return """
                {
                  "status": "ok",
                  "app": "ok",
                  "db": [
                    { "name": "PrimaryDb", "status": "ok", "queryResult": "1", "detail": null },
                    { "name": "LocationDb", "status": "ok", "queryResult": "1", "detail": null },
                    { "name": "LogDb", "status": "ok", "queryResult": "1", "detail": null },
                    { "name": "ServiceDb", "status": "ok", "queryResult": "1", "detail": null },
                    { "name": "ReportingDb", "status": "ok", "queryResult": "1", "detail": null }
                  ],
                  "timestamp": "2026-07-06T09:59:36.299708+09:00"
                }
                """;
    }

    private String detailOk() {
        return """
                {
                  "status": "ok",
                  "runtime": {
                    "uptimeSec": 30,
                    "workingSetMb": 136,
                    "gcHeapMb": 97,
                    "threadCount": 31,
                    "gcCount": { "gen0": 1, "gen1": 0, "gen2": 0 }
                  },
                  "api": {
                    "requestCount1m": 0,
                    "requestCount5m": 0,
                    "errorCount5m": 0,
                    "errorRate5m": 0,
                    "avgLatencyMs5m": 0,
                    "p95LatencyMs5m": 0,
                    "inFlightRequestCount": 0
                  },
                  "db": [
                    {
                      "name": "ReportingDb",
                      "ok": true,
                      "lastQueryLatencyMs": 1074,
                      "lastSuccessAt": "2026-07-06T09:59:36.725015+09:00",
                      "lastFailureAt": null,
                      "detail": null
                    },
                    {
                      "name": "PrimaryDb",
                      "ok": true,
                      "lastQueryLatencyMs": 117,
                      "lastSuccessAt": "2026-07-06T09:59:36.301909+09:00",
                      "lastFailureAt": null,
                      "detail": null
                    },
                    {
                      "name": "LocationDb",
                      "ok": true,
                      "lastQueryLatencyMs": 79,
                      "lastSuccessAt": "2026-07-06T09:59:36.419728+09:00",
                      "lastFailureAt": null,
                      "detail": null
                    },
                    {
                      "name": "LogDb",
                      "ok": true,
                      "lastQueryLatencyMs": 212,
                      "lastSuccessAt": "2026-07-06T09:59:36.499259+09:00",
                      "lastFailureAt": null,
                      "detail": null
                    },
                    {
                      "name": "ServiceDb",
                      "ok": true,
                      "lastQueryLatencyMs": 12,
                      "lastSuccessAt": "2026-07-06T09:59:36.712012+09:00",
                      "lastFailureAt": null,
                      "detail": null
                    }
                  ],
                  "checkedAt": "2026-07-06T09:59:37.800573+09:00"
                }
                """;
    }
}
