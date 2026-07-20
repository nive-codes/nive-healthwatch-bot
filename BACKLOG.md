# Health Watch Bot — 백로그 / 나중에 고려할 결정

MVP 이후 검토할 항목. 지금 구현하지 않되, 결정 맥락을 잃지 않게 기록한다.

## 1. 메일 "발송자(SMTP 계정)" DB화

### 배경
현재 알림 **수신자**는 DB(`notification_sender_mail`, MAIN 담당자 포함)이지만,
**발송자**(SMTP host/port/username/password/from)는 `application.yml` + env 에 있다.
즉 "누구에게 보내나"는 DB, "뭘로 보내나"는 yml — 축이 갈려 있다.

부모 레포(nive-ai-local-agent)의 철학(설정=DB가 진실 원천, 첫 실행 마법사, 시크릿 AES-GCM,
`AiAgentSettings` 테이블)과 맞추려면 발송자도 DB 로 올리는 게 일관적이다.

### 옵션
- **A. yml/env 유지 (현재)** — SMTP 계정 1개면 단순. MVP 충분.
- **B. 설정 테이블 (권장)** — `smtp_account`(또는 key-value `app_setting`) 에
  host/port/username/**password(AES-GCM)**/from 저장. 재배포 없이 발송 메일함 교체·시크릿 암호화.
  이미 만든 `AesGcmStringConverter` 재사용. **글로벌 1개**로 시작.
- **C. 프로젝트별 발송 계정** — 도메인마다 다른 메일함에서 보낼 때만. 현재는 과함.

### 결정(잠정)
B(글로벌 1개, AES-GCM). 구현 시 `MailChannelSender` 가 yml 대신 이 설정을 읽고
`JavaMailSenderImpl` 를 런타임 구성하도록 바꾼다. 없으면 yml 폴백.

### 주의
- 수신자(`notification_sender_mail`)와 발송자(SMTP 계정)는 다른 개념 — 이름이 둘 다 "mail"이라 혼동 주의.
- password 는 반드시 AES-GCM. 키(`HW_CRYPTO_KEY`) 미설정 시 평문 폴백 경고는 유지.

## 2. 기타
- rule 임계치(P95/errorRate/DB latency 배수) 설정화 — 현재 `RuleAnalyzer` 상수.
- 감시 대상/수신자 관리 UI 또는 명령 — 현재 DB 직접 CRUD.
- CLI stream-json 파서 정교화 — 엔진(codex/claude) 확정 후 `ObjectMapper` 기반으로.
