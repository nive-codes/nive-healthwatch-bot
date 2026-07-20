package com.nive.healthwatch.notify;

import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.NotificationSender;
import com.nive.healthwatch.domain.NotificationSenderMail;
import com.nive.healthwatch.domain.enums.NotificationChannel;
import com.nive.healthwatch.domain.repository.NotificationSenderMailRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author nive
 * @class MailChannelSender
 * @desc 메일(주력 채널) 발송기. sender_id 로 수신 주소를 해석해 SMTP(JavaMailSender)로 보낸다.
 *       제목=AlertMessage.title, 본문=Health Watch Bot HTML 리포트. from/활성은 health-watch.notifier.mail.*.
 * @since 2026-07-06
 */
@Slf4j
@Component
public class MailChannelSender implements ChannelSender {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JavaMailSender mailSender;
    private final NotificationSenderMailRepository mailRepo;
    private final HealthWatchProperties.Mail config;

    public MailChannelSender(JavaMailSender mailSender, NotificationSenderMailRepository mailRepo,
                             HealthWatchProperties props) {
        this.mailSender = mailSender;
        this.mailRepo = mailRepo;
        this.config = props.getNotifier().getMail();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.MAIL;
    }

    @Override
    public boolean available() {
        return config.isEnabled() && config.getFrom() != null && !config.getFrom().isBlank() && withinSendWindow();
    }

    @Override
    public NotificationResult send(NotificationSender sender, AlertMessage message) {
        Optional<NotificationSenderMail> mail = mailRepo.findBySenderId(sender.getId());
        if (mail.isEmpty()) {
            return NotificationResult.fail(channel().name(), null, "메일 detail 없음(sender_id=" + sender.getId() + ")");
        }
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(config.getFrom());
            helper.setTo(mail.get().getEmail());
            helper.setSubject(config.getSubjectPrefix() + nz(message.title()));
            helper.setText(plainText(message), html(message));
            mailSender.send(mime);
            return NotificationResult.ok(channel().name(), 200);
        } catch (Exception e) {
            log.warn("[Notify] 메일 발송 실패 to={}", mail.get().getEmail(), e);
            return NotificationResult.fail(channel().name(), null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String plainText(AlertMessage message) {
        return "Health Watch Bot\n"
                + "project: " + nz(displayProject(message)) + "\n"
                + "type: " + nz(message.kind()) + "\n\n"
                + nz(message.title()) + "\n\n"
                + nz(message.body());
    }

    private boolean withinSendWindow() {
        int suppressAfterHour = config.getSuppressAfterHour();
        if (suppressAfterHour < 0) {
            return true;
        }
        if (suppressAfterHour > 23) {
            log.warn("[Notify] mail.suppress-after-hour={} 설정 오류 — 메일 시간 제한을 적용하지 않음", suppressAfterHour);
            return true;
        }
        LocalTime now = LocalTime.now(KST);
        boolean allowed = now.isBefore(LocalTime.of(suppressAfterHour, 0));
        if (!allowed) {
            log.info("[Notify] 현재 {} KST, mail.suppress-after-hour={} — 메일 발송 skip",
                    now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), suppressAfterHour);
        }
        return allowed;
    }

    private String html(AlertMessage message) {
        if ("report".equals(nz(message.kind()))) {
            return reportHtml(message);
        }
        return alertHtml(message);
    }

    private String reportHtml(AlertMessage message) {
        StatusStyle overall = overallStatus(message.body());
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String summary = firstNonBlank(section(message.body(), "요약"), "프로젝트 health 상태 리포트입니다.");
        String overallText = firstNonBlank(sectionUntil(message.body(), "전체 총평", "다음 리포트"), "전체 총평 데이터가 없습니다.");
        String blocks = projectBlocks(message.body());
        if (blocks.isBlank()) {
            blocks = genericProjectBlock(displayProject(message), "정상", nz(message.body()));
        }
        return """
                <!DOCTYPE html>
                <html lang="ko" xmlns="http://www.w3.org/1999/xhtml">
                <head><meta charset="UTF-8" /><meta name="viewport" content="width=device-width, initial-scale=1.0" /></head>
                <body style="margin:0; padding:0; background-color:#f2f4f7; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Malgun Gothic',Arial,sans-serif;">
                <div style="display:none; max-height:0; overflow:hidden; opacity:0;">%s</div>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f2f4f7; padding:24px 0;">
                  <tr><td align="center">
                    <table role="presentation" width="640" cellpadding="0" cellspacing="0" style="width:640px; max-width:640px; background-color:#ffffff; border-radius:12px; overflow:hidden; box-shadow:0 1px 3px rgba(0,0,0,0.06);">
                      <tr>
                        <td style="background-color:#1a1f2e; padding:24px 32px;">
                          <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                            <tr>
                              <td style="color:#ffffff; font-size:16px; font-weight:600; letter-spacing:0.3px;">Health Watch Bot</td>
                              <td align="right" style="color:#9aa5b8; font-size:12px;">%s KST</td>
                            </tr>
                            <tr>
                              <td colspan="2" style="padding-top:8px; color:#c8d1e3; font-size:12px; line-height:1.6;">
                                이 메일은 정기 발송으로 처리되는 LLM 서버 상태 리포트입니다.
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                      <tr><td style="padding:28px 32px 8px 32px;"><span style="background-color:%s; color:%s; font-size:13px; font-weight:700; padding:6px 14px; border-radius:20px;">%s</span></td></tr>
                      <tr>
                        <td style="padding:12px 32px 8px 32px;">
                          <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f7f8fa; border-left:4px solid #4f6bed; border-radius:6px;">
                            <tr><td style="padding:16px 18px;">
                              <div style="font-size:11px; font-weight:700; color:#4f6bed; text-transform:uppercase; letter-spacing:0.5px; margin-bottom:6px;">요약</div>
                              <div style="font-size:14px; line-height:1.6; color:#2a2f3a;">%s</div>
                            </td></tr>
                          </table>
                        </td>
                      </tr>
                      %s
                      <tr>
                        <td style="padding:8px 32px 24px 32px;">
                          <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#1a1f2e; border-radius:10px;">
                            <tr><td style="padding:20px 22px;">
                              <div style="font-size:11px; font-weight:700; color:#8fa0ff; text-transform:uppercase; letter-spacing:0.5px; margin-bottom:8px;">전체 총평</div>
                              <div style="font-size:13.5px; line-height:1.7; color:#e6e9f0;">%s</div>
                            </td></tr>
                          </table>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:20px 32px; background-color:#f7f8fa; border-top:1px solid #eceef2;">
                          <div style="font-size:11px; color:#9aa5b8; line-height:1.6;">
                            이 리포트는 Watch Bot이 LLM을 사용해 자동 생성한 서버 상태 리포트입니다.<br />
                            다음 정기 리포트는 매일 17:00 KST에 발송됩니다. 매주 금요일과 월말 리포트에는 금주·월간 기준의 상세 관점이 포함됩니다.
                          </div>
                        </td>
                      </tr>
                    </table>
                  </td></tr>
                </table>
                </body>
                </html>
                """.formatted(esc(summary), now, overall.bg(), overall.fg(), esc(overall.label()), renderInline(summary), blocks,
                renderInline(overallText));
    }

    private String alertHtml(AlertMessage message) {
        String accent = switch (nz(message.kind())) {
            case "report" -> "#2563eb";
            case "recovery" -> "#15803d";
            case "system" -> "#4f6bed";
            default -> "#dc2626";
        };
        String label = switch (nz(message.kind())) {
            case "report" -> "Daily Report";
            case "recovery" -> "Recovered";
            case "system" -> "System Notice";
            default -> "Anomaly Alert";
        };
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return """
                <!doctype html>
                <html>
                <body style="margin:0;padding:0;background:#f5f7fb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;color:#172033;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f5f7fb;padding:24px 0;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="720" cellpadding="0" cellspacing="0" style="width:720px;max-width:calc(100%% - 32px);background:#ffffff;border:1px solid #dbe2ef;border-radius:8px;overflow:hidden;">
                          <tr>
                            <td style="background:#101827;color:#ffffff;padding:18px 22px;">
                              <div style="font-size:13px;letter-spacing:.02em;color:#a9b7cf;">Health Watch Bot</div>
                              <div style="font-size:22px;font-weight:700;line-height:1.35;margin-top:4px;">%s</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:18px 22px;border-bottom:1px solid #e7edf6;">
                              <span style="display:inline-block;background:%s;color:#ffffff;border-radius:4px;padding:5px 9px;font-size:12px;font-weight:700;">%s</span>
                              <span style="display:inline-block;margin-left:8px;color:#526174;font-size:13px;">project: <strong style="color:#172033;">%s</strong></span>
                              <span style="display:inline-block;margin-left:8px;color:#526174;font-size:13px;">sent: %s KST</span>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:22px;">
                              %s
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(esc(nz(message.title())), accent, label, esc(displayProject(message)), now,
                renderBody(message.body()));
    }

    private String projectBlocks(String body) {
        List<ProjectSection> sections = projectSections(body);
        StringBuilder out = new StringBuilder();
        for (ProjectSection s : sections) {
            out.append(projectBlock(s.name(), sectionStatus(s.text()), s.text()));
        }
        return out.toString();
    }

    private String projectBlock(String name, String status, String text) {
        StatusStyle style = statusStyle(status);
        ProjectBody body = parseProjectBody(text);
        return """
                <tr>
                  <td style="padding:24px 32px 0 32px;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border:1px solid #eceef2; border-radius:10px; overflow:hidden;">
                      <tr>
                        <td style="background-color:#1a1f2e; padding:12px 18px;">
                          <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                            <tr>
                              <td style="color:#ffffff; font-size:14px; font-weight:700;">%s</td>
                              <td align="right"><span style="background-color:%s; color:%s; font-size:11px; font-weight:700; padding:4px 10px; border-radius:12px;">%s</span></td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:16px 18px 4px 18px;">
                          <div style="font-size:13.5px; line-height:1.65; color:#2a2f3a;">%s</div>
                        </td>
                      </tr>
                      %s
                      %s
                    </table>
                  </td>
                </tr>
                <tr><td style="height:16px; line-height:16px; font-size:0;">&nbsp;</td></tr>
                """.formatted(esc(name), style.bg(), style.fg(), esc(style.label()), renderInline(body.narrative()),
                anomalyCards(body.anomalies()), metricTable(body.metrics()));
    }

    private String genericProjectBlock(String name, String status, String text) {
        return projectBlock(name, status, "서술: " + text);
    }

    private ProjectBody parseProjectBody(String text) {
        String status = "";
        String narrative = "";
        List<String> anomalies = new ArrayList<>();
        List<String> metrics = new ArrayList<>();
        Mode mode = Mode.NONE;
        for (String raw : nz(text).split("\\R")) {
            String line = raw.strip();
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("상태:")) {
                status = line.substring(line.indexOf(':') + 1).strip();
            } else if (line.startsWith("서술:")) {
                narrative = line.substring(line.indexOf(':') + 1).strip();
                mode = Mode.NONE;
            } else if ("이상 징후:".equals(line)) {
                mode = Mode.ANOMALY;
            } else if ("메트릭:".equals(line)) {
                mode = Mode.METRIC;
            } else if (mode == Mode.ANOMALY) {
                anomalies.add(line);
            } else if (mode == Mode.METRIC) {
                metrics.add(line);
            } else if (narrative.isBlank()) {
                narrative = line;
            }
        }
        if (narrative.isBlank()) {
            narrative = status.isBlank() ? "상태 요약 데이터가 없습니다." : "현재 상태는 " + status + "입니다.";
        }
        return new ProjectBody(status, narrative, anomalies, metrics);
    }

    private String anomalyCards(List<String> anomalies) {
        List<String> filtered = anomalies.stream()
                .map(String::strip)
                .filter(s -> !s.isBlank() && !"없음".equals(s))
                .toList();
        if (filtered.isEmpty()) {
            return """
                    <tr>
                      <td style="padding:14px 18px 4px 18px;">
                        <div style="font-size:11px; font-weight:700; color:#9aa5b8; text-transform:uppercase; letter-spacing:0.4px; margin-bottom:8px;">탐지된 이상 징후</div>
                        <div style="font-size:12.5px; color:#5b6270; background-color:#f7f8fa; border-radius:6px; padding:10px 12px;">특이사항 없음</div>
                      </td>
                    </tr>
                    """;
        }
        StringBuilder rows = new StringBuilder();
        for (String item : filtered) {
            SeverityStyle style = severityStyle(item);
            String clean = item.startsWith("- ") ? item.substring(2).strip() : item;
            rows.append("""
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="margin-bottom:8px;">
                      <tr>
                        <td style="width:4px; background-color:%s; border-radius:4px 0 0 4px;"></td>
                        <td style="padding:10px 12px; background-color:#f7f8fa; border-radius:0 6px 6px 0;">
                          <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                            <tr>
                              <td style="font-size:12.5px; font-weight:600; color:#1a1f2e;">%s</td>
                              <td align="right" style="font-size:10.5px; font-weight:700; color:%s;">%s</td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                    """.formatted(style.color(), esc(clean), style.color(), style.label()));
        }
        return """
                <tr>
                  <td style="padding:14px 18px 4px 18px;">
                    <div style="font-size:11px; font-weight:700; color:#9aa5b8; text-transform:uppercase; letter-spacing:0.4px; margin-bottom:8px;">탐지된 이상 징후</div>
                    %s
                  </td>
                </tr>
                """.formatted(rows);
    }

    private String metricTable(List<String> metrics) {
        if (metrics.isEmpty()) {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        for (String metric : metrics) {
            String clean = metric.startsWith("- ") ? metric.substring(2).strip() : metric.strip();
            String[] cols = clean.split("\\|", -1);
            String name = cols.length > 0 ? cols[0].strip() : clean;
            String value = cols.length > 1 ? cols[1].strip() : "-";
            String delta = cols.length > 2 ? cols[2].strip() : "-";
            String state = cols.length > 3 ? cols[3].strip() : "-";
            String stateColor = state.contains("주의") ? "#92400e" : "#166534";
            rows.append("""
                    <tr>
                      <td style="padding:8px 10px; border-bottom:1px solid #f0f1f4; font-size:12.5px; color:#2a2f3a;">%s</td>
                      <td style="padding:8px 10px; border-bottom:1px solid #f0f1f4; font-size:12.5px; color:#2a2f3a;">%s</td>
                      <td style="padding:8px 10px; border-bottom:1px solid #f0f1f4; font-size:12.5px; color:#5b6270;">%s</td>
                      <td style="padding:8px 10px; border-bottom:1px solid #f0f1f4; font-size:12.5px; color:%s; font-weight:700;">%s</td>
                    </tr>
                    """.formatted(esc(name), esc(value), esc(delta), stateColor, esc(state)));
        }
        return """
                <tr>
                  <td style="padding:12px 18px 18px 18px;">
                    <div style="font-size:11px; font-weight:700; color:#9aa5b8; text-transform:uppercase; letter-spacing:0.4px; margin-bottom:8px;">메트릭 스냅샷</div>
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">
                      <tr>
                        <td style="padding:8px 10px; background-color:#f7f8fa; font-size:11px; color:#5b6270; border-radius:6px 0 0 6px;">지표</td>
                        <td style="padding:8px 10px; background-color:#f7f8fa; font-size:11px; color:#5b6270;">현재값</td>
                        <td style="padding:8px 10px; background-color:#f7f8fa; font-size:11px; color:#5b6270;">평시 대비</td>
                        <td style="padding:8px 10px; background-color:#f7f8fa; font-size:11px; color:#5b6270; border-radius:0 6px 6px 0;">상태</td>
                      </tr>
                      %s
                    </table>
                  </td>
                </tr>
                """.formatted(rows);
    }

    private SeverityStyle severityStyle(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("critical") || text.contains("위험")) {
            return new SeverityStyle("위험", "#dc2626");
        }
        return new SeverityStyle("주의", "#d97706");
    }

    private StatusStyle overallStatus(String body) {
        String lower = nz(body).toLowerCase();
        if (lower.contains("critical") || lower.contains("위험") || lower.contains("즉시") || lower.contains("장애")) {
            return new StatusStyle("즉시 확인 필요", "#fee2e2", "#991b1b");
        }
        if (lower.contains("warning") || lower.contains("주의") || lower.contains("anomaly")) {
            return new StatusStyle("주의 필요", "#fef3c7", "#92400e");
        }
        return new StatusStyle("전체 정상", "#dcfce7", "#166534");
    }

    private StatusStyle statusStyle(String status) {
        String s = nz(status);
        if (s.contains("위험") || s.contains("critical")) {
            return new StatusStyle("위험", "#fee2e2", "#991b1b");
        }
        if (s.contains("주의") || s.contains("warning")) {
            return new StatusStyle("주의", "#fef3c7", "#92400e");
        }
        if (s.contains("데이터")) {
            return new StatusStyle("데이터 부족", "#e5e7eb", "#374151");
        }
        return new StatusStyle("정상", "#dcfce7", "#166534");
    }

    private String sectionStatus(String text) {
        for (String line : nz(text).split("\\R")) {
            if (line.strip().startsWith("상태:")) {
                return line.substring(line.indexOf(':') + 1).strip();
            }
        }
        return text.contains("주의") ? "주의" : "정상";
    }

    private String section(String body, String title) {
        String marker = "[" + title + "]";
        String[] lines = nz(body).split("\\R");
        StringBuilder out = new StringBuilder();
        boolean capture = false;
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.equals(marker)) {
                capture = true;
                continue;
            }
            if (capture && stripped.startsWith("[") && stripped.endsWith("]")) {
                break;
            }
            if (capture) {
                out.append(line).append('\n');
            }
        }
        return out.toString().strip();
    }

    private String sectionUntil(String body, String title, String stopTitle) {
        String marker = "[" + title + "]";
        String stop = "[" + stopTitle + "]";
        String[] lines = nz(body).split("\\R");
        StringBuilder out = new StringBuilder();
        boolean capture = false;
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.equals(marker)) {
                capture = true;
                continue;
            }
            if (capture && stripped.equals(stop)) {
                break;
            }
            if (capture) {
                out.append(line).append('\n');
            }
        }
        return out.toString().strip();
    }

    private List<ProjectSection> projectSections(String body) {
        List<ProjectSection> out = new ArrayList<>();
        String[] lines = nz(body).split("\\R");
        String currentName = null;
        StringBuilder currentText = new StringBuilder();
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.startsWith("[프로젝트: ") && stripped.endsWith("]")) {
                if (currentName != null) {
                    out.add(new ProjectSection(currentName, currentText.toString().strip()));
                }
                currentName = stripped.substring("[프로젝트: ".length(), stripped.length() - 1);
                currentText = new StringBuilder();
                continue;
            }
            if (currentName != null) {
                if (stripped.equals("[전체 총평]")) {
                    break;
                }
                currentText.append(line).append('\n');
            }
        }
        if (currentName != null) {
            out.add(new ProjectSection(currentName, currentText.toString().strip()));
        }
        return out;
    }

    private String renderInline(String s) {
        return esc(nz(s)).replace("\n", "<br />");
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String displayProject(AlertMessage message) {
        return message.projectName() == null || message.projectName().isBlank() ? "all monitored projects" : message.projectName();
    }

    private String renderBody(String body) {
        String[] lines = nz(body).split("\\R");
        StringBuilder out = new StringBuilder();
        boolean openList = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isBlank()) {
                if (openList) {
                    out.append("</ul>");
                    openList = false;
                }
                out.append("<div style=\"height:10px;\"></div>");
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                if (openList) {
                    out.append("</ul>");
                    openList = false;
                }
                out.append("<h2 style=\"font-size:16px;line-height:1.4;margin:18px 0 8px;color:#172033;border-bottom:1px solid #e7edf6;padding-bottom:6px;\">")
                        .append(esc(line.substring(1, line.length() - 1)))
                        .append("</h2>");
            } else if (line.startsWith("- ")) {
                if (!openList) {
                    out.append("<ul style=\"margin:6px 0 12px 18px;padding:0;color:#263244;font-size:14px;line-height:1.65;\">");
                    openList = true;
                }
                out.append("<li>").append(esc(line.substring(2))).append("</li>");
            } else {
                if (openList) {
                    out.append("</ul>");
                    openList = false;
                }
                out.append("<p style=\"margin:6px 0;color:#263244;font-size:14px;line-height:1.65;white-space:pre-wrap;\">")
                        .append(esc(line))
                        .append("</p>");
            }
        }
        if (openList) {
            out.append("</ul>");
        }
        return out.toString();
    }

    private String esc(String s) {
        return nz(s)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private record ProjectSection(String name, String text) {
    }

    private record StatusStyle(String label, String bg, String fg) {
    }

    private record ProjectBody(String status, String narrative, List<String> anomalies, List<String> metrics) {
    }

    private record SeverityStyle(String label, String color) {
    }

    private enum Mode {
        NONE, ANOMALY, METRIC
    }
}
