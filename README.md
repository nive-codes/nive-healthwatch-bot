# Health Watch Bot

등록된 여러 백엔드 서비스의 `/health`, `/health/detail` 을 주기 수집하고, 최근 추세와 비교해
장애 전조/조건을 rule 로 판단한 뒤 **필요할 때만** AI CLI 분석을 붙여 알림(메일 주력)으로 리포트한다.

- 판단 주체는 **rule**. AI 는 원인 추정/영향/확인항목 제안만 한다.
- 정상 상태에서는 조용히 수집만. rule trigger 시에만 알림 + (선택)AI.
- 매일 17:00 **통합 서버 리포트**를 각 프로젝트의 **MAIN 담당자 메일** 기준으로 dedupe 하여 발송한다(LLM 내러티브 + rule 폴백).
- anomaly/복구 알림은 MAIL/SLACK/DISCORD/TELEGRAM sender 를 DB 에 등록하면 채널별 구현체로 동시에 라우팅된다.

`nive-ai-local-agent` 를 벤치마킹한 형제 아키텍처: 비웹 Spring Boot + 내장 H2(수동 DDL) +
`java.net.http` 수집/webhook + `ProcessBuilder` 기반 AI subprocess + AES-GCM 준시크릿 암호화.

## 감시 대상 & 알림 라우팅 (DB 기반)

- **감시 대상**은 `monitored_service` 테이블(SSoT). 스케줄러가 `enabled=true` 대상을 순회한다.
  최초 부팅 시 테이블이 비어있으면 `application.yml` 의 `health-watch.services` 로 시딩한다(이후 DB 가 진실 원천).
- **알림 sender**는 supertype/subtype 구조:
  - `notification_sender` (base): 프로젝트 × 채널(MAIL/SLACK/DISCORD/TELEGRAM) × enabled
  - `notification_sender_mail` (detail): 수신 메일 + `is_main`(MAIN 담당자)
  - `notification_sender_slack` (detail): incoming webhook(AES-GCM 암호화) + channel override
  - `notification_sender_discord` (detail): webhook(AES-GCM 암호화) + thread
- `notification_sender_telegram` (detail): bot token(AES-GCM 암호화) + chat id
- 한 프로젝트에 여러 sender 를 두어 **다중 채널·다중 수신자** 발송. anomaly/복구는 전 채널, 17시 정기 리포트는 MAIN 메일로.
- MAIN 메일이 여러 프로젝트에서 같으면 정기 리포트는 같은 수신자에게 1통만 발송한다.

## 구성

```
collect/   HealthClient, HealthCollector        — /health, /health/detail 수집·파싱·저장
rule/      RuleAnalyzer                          — 임계치/추세 trigger + cooldown + resolved
ai/        AiAnalyzer(port), CliAiAnalyzer       — CLI subprocess(codex/claude 교체), PromptBuilder
notify/    ChannelSender(port), Mail/Slack/Discord/Telegram senders, NotificationRouter
watch/     WatchOrchestrator, AlertFormatter     — collect→rule→AI→notify 파이프라인
schedule/  CollectScheduler, DailyReportScheduler(+Service), RetentionScheduler
bootstrap/ RegistrySeeder                        — 최초 부팅 yml→DB 시딩(+MAIN 메일)
domain/    엔티티/리포지토리(H2, ddl-auto=none), crypto/AesGcmStringConverter
```

운영 관점의 상세 설명은 [Health Watch Bot 운영 관점 정리](docs/health-watch-operational-guide.md)를 참고한다.

## 실행

```bash
# 메일(주력) — SMTP
export SMTP_HOST=smtp.example.com SMTP_PORT=587 SMTP_USERNAME=... SMTP_PASSWORD=... \
       SMTP_AUTH=true SMTP_STARTTLS=true MAIL_FROM="health-watch@example.com"

# 감시 대상 base url + 시딩용 MAIN 담당자 메일(프로젝트별)
export USER_API_BASE_URL=http://service-a-host USER_API_ALERT_EMAIL=service-a-team@example.com
export ADMIN_API_BASE_URL=http://service-b-host ADMIN_API_ALERT_EMAIL=service-b-team@example.com
export DRIVER_API_BASE_URL=http://service-c-host DRIVER_API_ALERT_EMAIL=service-c-team@example.com

# (선택) 준시크릿 암호화 키(base64 32B). 미설정 시 디스코드 webhook 등 평문 저장.
export HW_CRYPTO_KEY="$(openssl rand -base64 32)"

./gradlew bootRun
# 또는  ./gradlew bootJar && java -jar build/libs/health-watch-bot-0.0.1-SNAPSHOT.jar
```

AI CLI(`codex` 또는 `claude`)는 실행 PC 에 로그인되어 있어야 한다. 엔진은 `health-watch.ai.command`
로 교체하며(기본 `codex exec --json`), command 뒤에 prompt 가 마지막 인자로 붙는다.

```yaml
# Codex CLI 사용 예시
health-watch:
  ai:
    command:
      - codex
      - exec
      - --json

# Claude CLI 사용 예시
health-watch:
  ai:
    command:
      - claude
      - -p
```

배포 서버에서 systemd 등으로 실행할 경우, 실제 실행 계정에 해당 CLI 인증이 되어 있어야 한다.

## 서버 배포

현재 기준 권장 배포 방식은 Docker 보다 **bootJar + 서버 로컬 실행**이다. 이유는 LLM 호출이
`codex exec --json` 같은 로컬 CLI subprocess 에 의존하므로, 실행 서버에 해당 CLI 로그인 세션이
살아 있어야 하기 때문이다.

배포 zip 기준 구성:

```text
health-watch-bot/
  health-watch-bot.jar          # 실행 JAR
  application.yml               # 실제 런타임 설정. SMTP/토큰 포함.
  application-sample.yml        # 운영자가 참고할 설정 샘플.
  README.md                     # 배포/운영 가이드.
```

### 1. 빌드

```bash
./gradlew clean bootJar
```

생성물:

```text
build/libs/health-watch-bot-0.0.1-SNAPSHOT.jar
```

### 2. 서버 파일 배치 예시

```text
/opt/health-watch-bot/
  health-watch-bot.jar
  application.yml              # 운영 시크릿 포함, git 에 올리지 않음
  logs/                        # 로그 파일
```

`application-sample.yml` 을 서버의 `/opt/health-watch-bot/application.yml` 로 복사한 뒤
SMTP, 토큰, 감시 대상 URL, 수신자 메일을 실제 값으로 채운다.
H2 DB 는 기본값 기준으로 실행 사용자 홈의 `~/health-watch-bot.mv.db` 에 생성된다.

공개/공유 repo 에는 실제 `application.yml` 을 포함하지 않는다. 배포 서버에는 `application-sample.yml` 을
복사한 뒤 SMTP, `health-watch.services[*].base-url`, `alert-email`, `healthcheck-token` 을 실제 값으로 채운다.

### 3. 직접 실행

```bash
cd /opt/health-watch-bot
java -jar health-watch-bot.jar --spring.config.additional-location=file:./application.yml
```

### 4. systemd 예시

```ini
[Unit]
Description=Health Watch Bot
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/health-watch-bot
ExecStart=/usr/bin/java -jar /opt/health-watch-bot/health-watch-bot.jar --spring.config.additional-location=file:/opt/health-watch-bot/application.yml
Restart=always
RestartSec=10
User=healthwatch
Environment=TZ=Asia/Seoul

[Install]
WantedBy=multi-user.target
```

LLM CLI 를 사용할 경우 `User=healthwatch` 계정으로 서버에 로그인해서 `codex` 또는 사용할 CLI 인증을
먼저 완료해야 한다. systemd 로 실행되는 계정과 CLI 로그인 계정이 다르면 인증 정보를 못 읽는다.

## 설정 (application.yml)

`spring.mail.*`(SMTP) + `health-watch.*`(수집 주기/window/cooldown/서비스 시드/AI/메일 notifier/retention).
시크릿은 `${SMTP_PASSWORD}`, `${HW_CRYPTO_KEY}` 등 env 로 주입. 배포 JAR 에는 yml 미포함.

H2 DB 기본 위치:

```yaml
spring:
  datasource:
    url: jdbc:h2:file:${HW_DB_PATH:~/health-watch-bot};MODE=MariaDB;AUTO_SERVER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
```

기본값은 `~/health-watch-bot.mv.db` 이다. 다른 위치를 쓰려면 `HW_DB_PATH=/path/to/healthwatch` 를 지정한다.

운영 검증용 1회성 메일 옵션:

```yaml
health-watch:
  startup-notification-enabled: true    # 앱 기동 완료 후 MAIN 메일 수신자에게 1회 발송
  first-collect-report-enabled: true    # 첫 수집 루프 완료 후 수집 상태 요약을 1회 발송
  daily-report-send-on-start: false     # 17:00 정기 리포트 형식을 기동 직후 1회 강제 발송
```

`startup-notification-enabled` 는 실제 health 수집 결과가 아니라, 앱이 정상 기동했고 DB 기준 감시 대상과
메일 라우팅이 로드됐는지 확인하는 용도다. 실제 `/health`, `/health/detail` 결과는
`first-collect-report-enabled` 메일에서 확인한다.

메일 시간 제한:

```yaml
health-watch:
  notifier:
    mail:
      suppress-after-hour: 18           # KST 기준 18:00:00부터 메일 발송 차단
```

기본값 `18`은 18시 이후 anomaly/복구/검증/정기 메일을 보내지 않는다. 제한 없이 보내야 하는 배포에서는
`-1`로 둔다. 이 제한은 MAIL 채널에만 적용되고 Slack/Discord/Telegram sender 는 각 채널 enabled 값과
DB 등록 여부에 따라 발송된다.

채팅 채널:

```yaml
health-watch:
  notifier:
    slack:
      enabled: true
    discord:
      enabled: true
    telegram:
      enabled: true
```

채팅 채널은 설정값만으로 수신자가 생기지 않는다. 각 프로젝트에 `notification_sender` 행을 만들고,
채널별 detail 테이블(`notification_sender_slack`, `notification_sender_discord`, `notification_sender_telegram`)에
webhook/token/chat id 를 등록해야 발송된다.

Rule 임계치:

```yaml
health-watch:
  collect-interval-seconds: 60          # 수집 주기
  request-timeout-seconds: 10           # /health/detail 순간 지연 흡수를 위해 10초
  daily-report-cron: "0 0 17 * * *"     # 매일 17:00 KST 정기 리포트
  rule:
    error-rate-threshold-percent: 5.0   # api.errorRate5m 값은 percent 단위(1.32 = 1.32%)
    p95-latency-threshold-ms: 1000
    db-latency-spike-mult: 3.0
    db-latency-min-ms: 100
    timeout-consecutive-threshold: 2    # timeout 이 연속 N회일 때만 critical 알림
```

timeout 알림은 `/health`와 `/health/detail` 중 어느 endpoint가 실패했는지 구분해 기록한다.
예를 들어 `/health`는 timeout 이고 `/health/detail`은 200이면 종합 상태는 `timeout`이지만,
메일의 현재 지표에는 `health`와 `detail` 블록이 각각 표시된다.

정기 리포트 범위:

- 일반 일간 리포트: 금일 집계 중심.
- 금요일 주간 리포트: 금일 + 금주 집계.
- 월말 월간 리포트: 금일 + 금주 + 월간 집계.
- 프로젝트 카드의 “금주 평균”은 현재값 비교 기준으로 항상 표시될 수 있다.

### yml 과 DB 의 관계

`application.yml` 의 `health-watch.services` 는 **초기 시드용**이다.

- 앱 첫 실행 시 `monitored_service` 테이블이 비어있으면 `health-watch.services` 값으로 감시 대상을 만든다.
- 각 service 의 `alert-email` 이 있으면 `notification_sender` + `notification_sender_mail` 에 MAIN 메일 수신자를 함께 만든다.
- 한 번이라도 DB 에 감시 대상이 생긴 뒤에는 yml 을 수정해도 기존 DB 값은 자동으로 덮어쓰지 않는다.
- 이후 운영 중 감시 대상 URL, enabled 여부, 수신자는 DB 가 진실 원천이다.
- `healthcheck-token` 은 DB 에 저장하지 않고, 런타임 `application.yml` 값으로 요청 헤더에만 사용한다.

즉, 운영 중 URL 또는 수신자를 바꾸려면 다음 둘 중 하나를 선택한다.

1. DB 를 직접 수정한다.
2. DB 를 초기화한 뒤 앱을 다시 시작해서 yml 기준으로 재시딩한다.

### 초기화 / 재시딩

로컬 H2 파일 DB 를 완전히 초기화하려면 앱을 종료한 뒤 홈 디렉터리의 H2 파일을 삭제하고 다시 실행한다.

```bash
rm -f ~/health-watch-bot.mv.db ~/health-watch-bot.trace.db
java -jar health-watch-bot.jar --spring.config.additional-location=file:./application.yml
```

이 방식은 snapshot, anomaly, AI report, notification log 까지 모두 삭제한다. 운영 데이터가 필요하면
삭제 전에 `~/health-watch-bot.mv.db` 를 백업한다.

감시 대상과 알림 수신자만 yml 기준으로 다시 만들고, 이력 데이터는 가능하면 유지하고 싶다면 H2 Shell 등으로
registry 테이블만 비운 뒤 재시작한다.

```sql
delete from notification_sender_mail;
delete from notification_sender_slack;
delete from notification_sender_discord;
delete from notification_sender_telegram;
delete from notification_sender;
delete from monitored_service;
```

재시작 시 `monitored_service` 가 비어 있으므로 `RegistrySeeder` 가 다시 `health-watch.services` 를 읽어 시딩한다.
다만 기존 snapshot/anomaly 이력의 `service_name` 문자열은 남기 때문에, 서비스명을 바꿀 때는 리포트 해석에 주의한다.

## 테스트

```bash
./gradlew test   # 컨텍스트 로드 + rule(503/cooldown/복구) + 라우팅(다중발송/MAIN선택)
```

## 확장 여지

- 채널 추가: `ChannelSender` 구현체 + detail 테이블을 추가하면 라우터가 자동 편입.
- AI 엔진 교체: `AiAnalyzer` 포트 유지, `health-watch.ai.command` 변경.
- 임계치(P95/errorRate/DB latency 배수)는 `health-watch.rule.*` 설정으로 조정.
- 감시 대상/수신자 런타임 관리: `monitored_service`, `notification_sender*` 를 DB 에서 직접 CRUD(관리 UI 는 후속).
