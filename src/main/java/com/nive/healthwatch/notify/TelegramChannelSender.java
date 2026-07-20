package com.nive.healthwatch.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.NotificationSender;
import com.nive.healthwatch.domain.NotificationSenderTelegram;
import com.nive.healthwatch.domain.enums.NotificationChannel;
import com.nive.healthwatch.domain.repository.NotificationSenderTelegramRepository;
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
 * @class TelegramChannelSender
 * @desc Telegram Bot API sendMessage 발송기. sender_id 로 bot token/chat id 를 조회한다.
 * @since 2026-07-20
 */
@Slf4j
@Component
public class TelegramChannelSender implements ChannelSender {

    private static final int TELEGRAM_LIMIT = 3900;

    private final NotificationSenderTelegramRepository telegramRepo;
    private final ObjectMapper objectMapper;
    private final HealthWatchProperties.Notifier notifier;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public TelegramChannelSender(NotificationSenderTelegramRepository telegramRepo, ObjectMapper objectMapper,
                                 HealthWatchProperties props) {
        this.telegramRepo = telegramRepo;
        this.objectMapper = objectMapper;
        this.notifier = props.getNotifier();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.TELEGRAM;
    }

    @Override
    public boolean available() {
        return notifier.getTelegram().isEnabled();
    }

    @Override
    public NotificationResult send(NotificationSender sender, AlertMessage message) {
        Optional<NotificationSenderTelegram> cfg = telegramRepo.findBySenderId(sender.getId());
        if (cfg.isEmpty() || cfg.get().getBotToken() == null || cfg.get().getBotToken().isBlank()
                || cfg.get().getChatId() == null || cfg.get().getChatId().isBlank()) {
            return NotificationResult.fail(channel().name(), null, "Telegram detail/token/chat_id 없음(sender_id=" + sender.getId() + ")");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chat_id", cfg.get().getChatId());
            payload.put("text", truncate(message.render(), TELEGRAM_LIMIT));
            payload.put("disable_web_page_preview", true);
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + cfg.get().getBotToken() + "/sendMessage"))
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
            log.warn("[Notify] Telegram 발송 실패 sender_id={}", sender.getId(), e);
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
