package com.nive.healthwatch.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.NotificationSender;
import com.nive.healthwatch.domain.NotificationSenderDiscord;
import com.nive.healthwatch.domain.enums.NotificationChannel;
import com.nive.healthwatch.domain.repository.NotificationSenderDiscordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * @author nive
 * @class DiscordChannelSender
 * @desc 디스코드(보조 채널) 발송기. sender_id 로 webhook(복호화)+thread 를 해석해 REST 로 POST 한다.
 *       메일 우선이라 기본은 안 쓰이며, 특정 프로젝트에 DISCORD sender 를 등록하면 함께 발송된다.
 * @since 2026-07-06
 */
@Slf4j
@Component
public class DiscordChannelSender implements ChannelSender {

    private static final int DISCORD_LIMIT = 1900;

    private final NotificationSenderDiscordRepository discordRepo;
    private final ObjectMapper objectMapper;
    private final HealthWatchProperties.Notifier notifier;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DiscordChannelSender(NotificationSenderDiscordRepository discordRepo, ObjectMapper objectMapper,
                                HealthWatchProperties props) {
        this.discordRepo = discordRepo;
        this.objectMapper = objectMapper;
        this.notifier = props.getNotifier();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.DISCORD;
    }

    @Override
    public boolean available() {
        return notifier.getDiscord().isEnabled(); // webhook 은 sender 별로 해석
    }

    @Override
    public NotificationResult send(NotificationSender sender, AlertMessage message) {
        Optional<NotificationSenderDiscord> cfg = discordRepo.findBySenderId(sender.getId());
        if (cfg.isEmpty() || cfg.get().getWebhookUrl() == null || cfg.get().getWebhookUrl().isBlank()) {
            return NotificationResult.fail(channel().name(), null, "디스코드 detail/webhook 없음(sender_id=" + sender.getId() + ")");
        }
        try {
            String url = withThread(cfg.get().getWebhookUrl(), cfg.get().getThreadId());
            String body = objectMapper.writeValueAsString(Map.of(
                    "content", truncate(message.render(), DISCORD_LIMIT),
                    "username", "health-watch"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
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
            log.warn("[Notify] 디스코드 발송 실패 sender_id={}", sender.getId(), e);
            return NotificationResult.fail(channel().name(), null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String withThread(String webhook, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return webhook;
        }
        return webhook + (webhook.contains("?") ? "&" : "?") + "thread_id=" + threadId;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
