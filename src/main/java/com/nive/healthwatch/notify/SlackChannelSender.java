package com.nive.healthwatch.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.NotificationSender;
import com.nive.healthwatch.domain.NotificationSenderSlack;
import com.nive.healthwatch.domain.enums.NotificationChannel;
import com.nive.healthwatch.domain.repository.NotificationSenderSlackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * @author nive
 * @class SlackChannelSender
 * @desc Slack incoming webhook 발송기. sender_id 로 webhook 을 조회해 REST 로 POST 한다.
 * @since 2026-07-20
 */
@Slf4j
@Component
public class SlackChannelSender implements ChannelSender {

    private static final int SLACK_LIMIT = 3000;

    private final NotificationSenderSlackRepository slackRepo;
    private final ObjectMapper objectMapper;
    private final HealthWatchProperties.Notifier notifier;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public SlackChannelSender(NotificationSenderSlackRepository slackRepo, ObjectMapper objectMapper,
                              HealthWatchProperties props) {
        this.slackRepo = slackRepo;
        this.objectMapper = objectMapper;
        this.notifier = props.getNotifier();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SLACK;
    }

    @Override
    public boolean available() {
        return notifier.getSlack().isEnabled();
    }

    @Override
    public NotificationResult send(NotificationSender sender, AlertMessage message) {
        Optional<NotificationSenderSlack> cfg = slackRepo.findBySenderId(sender.getId());
        if (cfg.isEmpty() || cfg.get().getWebhookUrl() == null || cfg.get().getWebhookUrl().isBlank()) {
            return NotificationResult.fail(channel().name(), null, "Slack detail/webhook 없음(sender_id=" + sender.getId() + ")");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", truncate(message.render(), SLACK_LIMIT));
            if (cfg.get().getChannelName() != null && !cfg.get().getChannelName().isBlank()) {
                payload.put("channel", cfg.get().getChannelName());
            }
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.get().getWebhookUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code / 100 == 2) {
                return NotificationResult.ok(channel().name(), code);
            }
            return NotificationResult.fail(channel().name(), code, truncate(response.body(), 300));
        } catch (Exception e) {
            log.warn("[Notify] Slack 발송 실패 sender_id={}", sender.getId(), e);
            return NotificationResult.fail(channel().name(), null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
