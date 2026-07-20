# Health Watch Bot 운영 관점 정리

> **작성일**: 2026-07-09  
> **작성자**: chs  
> **대상**: UserAPI / AdminAPI / DriverAPI health endpoint + Health Watch Bot  
> **변경 범위**: `/health`, `/health/detail` 수집 체계 + LLM 기반 알림/정기 리포트 + 개발팀/인프라팀 운영 대응 방식

---

## Summary

| 측면 | 기존 상태 | Health Watch Bot 적용 후 |
|---|---|---|
| 상태 확인 방식 | 장애 발생 후 수동 확인 | `/health`, `/health/detail` 주기 수집 |
| Health endpoint 활용 | 인프라팀 단순 alive 체크 중심 | 개발팀/인프라팀이 함께 쓰는 운영 신호 |
| 장애 감지 기준 | 사용자 제보, 로그 직접 확인 | rule 기반 anomaly 감지 + cooldown |
| 장애 알림 | 사람이 직접 판단 후 공유 | 메일 알림 + LLM 원인/영향/확인 항목 보조 |
| 정기 보고 | 별도 수동 정리 필요 | 매일 17:00 통합 서버 리포트 자동 발송 |
| 장기 추이 | Grafana/Zabbix 등 별도 구성 전까지 제한적 | H2 snapshot 기반 일간/주간/월간 흐름 축적 |
| 책임 분리 | 개발/인프라 확인 경계 불명확 | endpoint 성격별 대응 역할 명확화 |

_Health Watch Bot은 Grafana/Zabbix를 대체하기보다, 개발팀이 바로 읽고 대응할 수 있는 알림/리포트 계층을 우선 구축하는 프로젝트다._

---

## 1. Endpoint 역할 구분

### 1.1 `/health`

`/health`는 서비스가 외부 요청을 받을 수 있는지 빠르게 확인하는 endpoint다.

| 항목 | 내용 |
|---|---|
| 목적 | 서비스 alive / 기본 의존성 확인 |
| 응답 성격 | 가볍고 빠른 상태 응답 |
| 주요 사용자 | 인프라팀, 로드밸런서, uptime checker |
| 장애 판단 | HTTP status, `status`, 주요 DB ping 결과 |
| 기대 응답 | 수백 ms 이내 |

예시:

```json
{
  "status": "ok",
  "app": "ok",
  "db": [
    {
      "name": "MainDB",
      "status": "ok",
      "queryResult": "1",
      "detail": null
    }
  ],
  "timestamp": "2026-07-06T09:59:36.299708+09:00"
}
```

### 1.2 `/health/detail`

`/health/detail`은 개발팀이 장애 원인과 성능 추이를 확인하기 위한 상세 endpoint다.

| 항목 | 내용 |
|---|---|
| 목적 | runtime / API / DB 상세 metric 수집 |
| 응답 성격 | `/health`보다 무겁고 분석용 데이터가 많음 |
| 주요 사용자 | 개발팀, Health Watch Bot, LLM 리포트 |
| 장애 판단 | timeout, error rate, p95 latency, DB latency, thread/runtime 정보 |
| 기대 응답 | `/health`보다 느릴 수 있으므로 timeout 10초 기준 |

예시:

```json
{
  "status": "ok",
  "runtime": {
    "uptimeSec": 30,
    "workingSetMb": 136,
    "gcHeapMb": 97,
    "threadCount": 31
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
      "name": "MainDB",
      "ok": true,
      "lastQueryLatencyMs": 117,
      "lastSuccessAt": "2026-07-06T09:59:36.301909+09:00",
      "lastFailureAt": null,
      "detail": null
    }
  ],
  "checkedAt": "2026-07-06T09:59:37.800573+09:00"
}
```

### 1.3 차이 요약

| 구분 | `/health` | `/health/detail` |
|---|---|---|
| 핵심 목적 | 살아 있는가 | 왜 느리거나 불안정한가 |
| 무게 | 가벼움 | 상대적으로 무거움 |
| 인프라 사용 | 적합 | 보조 지표로 적합 |
| 개발팀 사용 | 기본 장애 여부 | 원인 분석/추세 확인 |
| Watch Bot 수집 | 항상 수집 | 항상 수집 |
| timeout 처리 | endpoint별 outcome 저장 | endpoint별 outcome 저장 |

> `/health`가 200이어도 `/health/detail`이 timeout이면 종합 상태는 `timeout`으로 기록된다.  
> 이 경우 메일의 현재 지표에는 `health`, `detail` 블록이 분리되어 어떤 endpoint가 느렸는지 표시된다.

---

## 2. Watch Bot 판단 모델

### 2.1 Rule이 1차 판단, LLM은 보조

Health Watch Bot의 장애 판단 주체는 LLM이 아니다.

| 계층 | 역할 |
|---|---|
| RuleAnalyzer | 숫자/상태 기반으로 anomaly 여부를 결정 |
| LLM | rule이 잡은 이벤트에 대해 원인 추정, 영향 범위, 확인 항목을 정리 |
| MailChannelSender | HTML 메일 템플릿으로 개발팀이 읽기 쉬운 형태로 전달 |
| H2 DB | snapshot, anomaly, AI report, notification log 보관 |

이 구조를 택한 이유:

- LLM이 임의로 장애 여부를 판단하지 않게 하기 위함
- 장애 판단은 재현 가능한 rule로 고정
- LLM은 사람이 읽는 문장과 확인 순서를 정리하는 데 집중

현재 LLM 호출은 API Key를 직접 사용하는 구조가 아니라, 서버 실행 계정에 로그인된 CLI(`codex`, `claude` 등)를
subprocess로 호출하는 방식이다. 따라서 별도 API Key 과금 체계를 붙이지 않고, 기존 로그인 세션을 사용하는 운영 형태로
구성할 수 있다. 단, 실행 서버의 서비스 계정과 CLI 로그인 계정이 같아야 인증 정보를 정상적으로 읽을 수 있다.

### 2.2 현재 rule 기준

| Trigger | 조건 | Severity | 비고 |
|---|---|---|---|
| `timeout` | `/health` 또는 `/health/detail` timeout 연속 N회 | critical | 기본 `timeout-consecutive-threshold=2` |
| `health_503` | HTTP 503 | critical | 서비스 장애로 간주 |
| `degraded` | payload `status=degraded` | warning | API가 자체적으로 성능 저하 보고 |
| `collect_error` | 수집/파싱 실패 | warning | token 오류, JSON 구조 오류 등 |
| `error_rate` | `errorRate5m >= 5.0%` | warning | `1.32`는 1.32%로 해석 |
| `latency_spike` | `p95LatencyMs5m >= 1000ms` | warning | API 응답 p95 기준 |
| `db_fail` | DB item `ok=false` | critical | DB 의존성 장애 |
| `db_latency_spike` | DB latency가 baseline 대비 N배 이상 | warning | 기본 3배, baseline 최소 100ms |

### 2.3 timeout 오탐 완화

`/health/detail`은 DB와 runtime metric을 포함하므로 순간적으로 무거울 수 있다. 따라서 현재 기본 정책은 다음과 같다.

```yaml
health-watch:
  request-timeout-seconds: 10
  rule:
    timeout-consecutive-threshold: 2
```

효과:

- 1회성 순간 지연은 흡수
- 연속 2회 timeout일 때 critical 알림
- timeout 발생 시 `/health`, `/health/detail` 중 어느 endpoint가 문제인지 메일에 표시

---

## 3. 개발팀 대응 방식

### 3.1 장애 알림을 받았을 때

| 메일 항목 | 개발팀이 볼 내용 | 대응 |
|---|---|---|
| 감지 조건 | 어떤 rule이 발동했는지 | timeout/error_rate/p95/db_fail 구분 |
| 현재 지표 | endpoint별 HTTP/outcome/latency, API metric | 실제 장애인지 오탐인지 1차 판단 |
| 장애 원인 | LLM이 정리한 가능성 | 로그/배포/DB 상태 확인 순서 참고 |
| 영향 범위 | 사용자/API/DB 영향 추정 | 서비스 공지 또는 내부 공유 판단 |
| 확인 항목 | 체크리스트 | 담당자가 순서대로 확인 |

예시 대응:

```text
[critical] timeout
1. 메일의 health/detail 블록에서 timeout endpoint 확인
2. 해당 API 서버 로그에서 같은 시간대 request timeout 확인
3. DB latency, threadCount, workingSetMb 확인
4. 최근 배포/쿼리 변경/외부 연동 지연 여부 확인
5. 단발이면 resolved 여부 확인, 반복이면 원인 조치
```

### 3.2 정기 리포트 활용

정기 리포트는 장애 알림과 목적이 다르다.

| 리포트 | 목적 | 포함 범위 |
|---|---|---|
| 일간 서버 보고서 | 당일 상태 빠른 확인 | 금일 집계 중심 |
| 주간 서버 보고서 | 한 주간 반복/추세 확인 | 금일 + 금주 |
| 월간 서버 보고서 | 월간 안정성/성과 정리 | 금일 + 금주 + 월간 |

개발팀 관점에서 활용 포인트:

- 반복 anomaly가 있는 API를 기술부채/성능개선 후보로 등록
- p95 latency나 DB latency가 꾸준히 증가하는 구간 확인
- 특정 배포 이후 error rate나 timeout 증가 여부 확인
- 월간 리포트를 장애 예방/운영 개선 성과 자료로 활용

### 3.3 개발팀 관리 방향

| 관리 항목 | 관리 방식 |
|---|---|
| timeout 반복 | endpoint별 timeout 원인 분리 후 API/DB/외부연동 확인 |
| p95 latency 증가 | 느린 API, DB query, lock, 외부 API 호출 확인 |
| DB latency spike | 대상 DB별 baseline 대비 변화 확인 |
| error rate 증가 | 5xx/비즈니스 예외/인증 오류 로그와 매칭 |
| memory/thread 증가 | runtime metric 추세를 장기적으로 확인 |

> 핵심은 “장애가 난 뒤 보는 도구”가 아니라 “장애 전조와 반복 패턴을 backlog로 전환하는 도구”로 쓰는 것이다.

---

## 4. 인프라팀 대응 방식

### 4.1 인프라팀에서 가져갈 수 있는 신호

| 신호 | 의미 | 인프라 대응 |
|---|---|---|
| `/health` HTTP status | 서비스 alive | 로드밸런서/uptime monitor 연결 |
| `/health` DB ping | 주요 DB 연결 가능 여부 | DB 연결/네트워크/방화벽 확인 |
| `/health/detail` runtime | process 상태 | CPU/memory/thread 관측과 비교 |
| timeout | API 서버 또는 네트워크 지연 | 서버 리소스, 네트워크, DNS, 방화벽 점검 |
| repeated anomaly | 반복 장애 후보 | 인프라 metric과 cross-check |

### 4.2 Grafana / Zabbix 연계 방향

Health Watch Bot은 직접 Grafana/Zabbix를 대체하지 않는다. 다만 같은 health endpoint를 기반으로 연계할 수 있다.

| 도구 | 연계 방식 |
|---|---|
| Zabbix | `/health` HTTP agent item으로 status code / body status 확인 |
| Grafana | 별도 exporter 또는 log/DB 연계로 snapshot metric 시각화 |
| Prometheus | 향후 `/metrics` exporter 추가 시 직접 scrape 가능 |
| Email/LLM bot | 현재 구현된 개발팀 알림/리포트 계층 |

권장 역할 분리:

- 인프라팀: 서버/네트워크/프로세스 alive, 리소스, DB 연결성, 알림 escalation
- 개발팀: API metric, DB query latency, error rate, 배포 영향, 코드/쿼리 개선
- Watch Bot: 두 영역 사이에서 개발팀이 읽기 쉬운 요약과 대응 체크리스트 제공

### 4.3 인프라팀 운영 시나리오

| 시나리오 | 인프라팀 1차 확인 | 개발팀 전달 기준 |
|---|---|---|
| `/health` 503 | 프로세스/포트/로드밸런서 상태 | 앱 로그 확인 필요 시 전달 |
| `/health` timeout | 서버 리소스, 네트워크 경로 | 서버 정상인데 API만 지연 시 전달 |
| DB ping 실패 | DB 서버, 네트워크, 계정/방화벽 | 쿼리/커넥션풀 이슈 의심 시 전달 |
| `/health/detail`만 timeout | 서버 리소스 + DB latency | detail 내부 metric 수집이 무거운지 개발팀 확인 |
| 반복 p95 latency warning | CPU/memory/network 추세 | 애플리케이션/DB query 분석 요청 |

---

## 5. 운영 데이터와 책임 경계

### 5.1 DB에 남는 데이터

| 테이블 | 내용 | 활용 |
|---|---|---|
| `monitored_service` | 감시 대상 API | 운영 중 감시 대상 관리 |
| `health_snapshot` | API 단위 수집 결과 | 일간/주간/월간 리포트 |
| `health_db_snapshot` | DB별 상세 상태 | DB latency/fail 분석 |
| `anomaly_event` | rule이 감지한 이벤트 | 장애/복구 이력 |
| `ai_report` | LLM 분석 결과 | 원인/영향/확인 항목 기록 |
| `notification_log` | 메일/채널 발송 결과 | 알림 성공 여부 감사 |

### 5.2 yml과 DB의 관계

`application.yml`의 `health-watch.services`는 최초 시딩용이다.

| 상태 | 기준 |
|---|---|
| DB가 비어 있음 | yml services로 `monitored_service`와 MAIN mail sender 생성 |
| DB에 감시 대상 존재 | DB가 진실 원천 |
| URL/수신자 변경 | DB 직접 수정 또는 registry 초기화 후 재시딩 |
| healthcheck token | DB에 저장하지 않고 yml/env에서만 사용 |

이 구조의 의도:

- token 같은 민감값은 DB에 남기지 않음
- 운영 중 감시 대상은 DB로 관리 가능
- 초기 배포는 yml만으로 빠르게 시작 가능

---

## 6. 기대 효과

| 관점 | 효과 |
|---|---|
| 개발팀 | 인프라팀 알림을 기다리지 않아도 API, DB, runtime 상태를 메일/채널 알림으로 즉시 확인 |
| 인프라팀 | 기존 healthcheck endpoint를 그대로 유지하면서 개발팀 운영 리포트에도 같은 신호를 제공 |
| 운영 리포트 | 일간/주간/월간 안정성 흐름을 자동 축적해 반복 확인과 수동 정리 부담을 낮춤 |
| 이슈 관리 | timeout, latency, DB 지연, error rate 반복 패턴을 anomaly 이력으로 남겨 개선 후보로 전환 |
| 안정성 | 장애 판정은 rule로 고정하고, LLM은 원인 추정·영향 범위·확인 항목 정리에만 사용 |
| 확장성 | 메일 외 Slack/Discord/Telegram 발송과 Grafana/Zabbix/Prometheus 연계 가능성 유지 |

### 6.1 운영 효과 정리

Health Watch Bot은 “메일 자동 발송 도구”라기보다 health endpoint를 개발팀이 계속 읽을 수 있는 운영 신호로
바꾸는 역할에 가깝다. 인프라팀은 기존처럼 `/health`를 alive 체크로 사용하고, 개발팀은 같은 데이터에
`/health/detail`의 API/DB/runtime metric을 더해 애플리케이션 상태를 빠르게 파악한다.

| 항목 | 기대되는 변화 |
|---|---|
| 이상 신호 인지 | `/health`, `/health/detail` 이상을 rule이 먼저 감지하고 알림으로 전달 |
| 초기 확인 속도 | 메일 본문에서 프로젝트, 감지 조건, 현재 지표, 확인 항목을 바로 확인 |
| 담당 범위 분리 | 인프라 이슈인지 애플리케이션/API/DB 지표 이슈인지 판단할 단서 제공 |
| 반복 이슈 관리 | 동일한 timeout, p95 latency, DB latency, error rate 패턴을 이력으로 남김 |
| LLM 활용 | 장애 여부 판단이 아니라 사람이 읽을 설명과 점검 순서를 정리하는 보조 역할 |

이 방식은 운영 자동화를 도입하되 판단 책임을 LLM에 넘기지 않는다. 알림 기준은 재현 가능한 rule로 유지하고,
LLM은 사람이 읽기 쉬운 설명과 확인 순서를 정리한다. 따라서 개발팀은 장기적으로 반복되는 장애와 성능 저하를
개별 이벤트로 흘려보내지 않고, 추세와 함께 개선 항목으로 관리할 수 있다.

---

## 7. 향후 개발 방향

현재 구현은 bootJar를 서버에서 직접 실행하고, 로그인된 LLM CLI를 subprocess로 호출하는 방식이다. 초기 운영과 검증에는
단순하지만, 운영 환경을 넓히려면 아래 방향으로 확장할 수 있다.

| 방향 | 내용 | 기대 효과 |
|---|---|---|
| Docker 배포 | JAR, 설정, 로그 경로를 컨테이너 기준으로 정리 | 서버 Java 환경 차이를 줄이고 배포 단위 표준화 |
| docker-compose 구성 | Watch Bot + MariaDB/PostgreSQL/시계열 DB를 함께 구성 | 로컬 H2를 넘어 운영 DB에 이력 저장 |
| 외부 DB 전환 | H2 file DB 대신 관계형 DB 또는 시계열 DB 사용 | 장기 보관, 백업, 조회, 대시보드 연계가 쉬워짐 |
| LLM SDK/API 연동 | CLI subprocess 대신 OpenAI/Anthropic 등 SDK 기반 Analyzer 구현체 추가 | 컨테이너 환경에서도 인증/호출 구성이 명확해짐 |
| 인프라 도구 연계 | Grafana/Zabbix/Prometheus와 snapshot/anomaly 데이터를 연결 | 인프라 지표와 개발팀 LLM 리포트를 같은 운영 흐름에서 확인 |
| 리포트 고도화 | 주간/월간 리포트에 장기 추세, 반복 anomaly, 개선 후보를 더 구조화 | 장애 예방과 개선 backlog 관리에 활용 |

Docker 형태로 전환할 경우 가장 큰 검토 지점은 LLM 인증 방식이다. 현재처럼 서버 계정에 로그인된 CLI를 그대로 쓰면
컨테이너 내부 인증과 호스트 인증이 분리될 수 있다. 이 경우에는 API Key/SDK 기반 Analyzer를 별도 구현체로 추가하고,
운영 환경에서는 `AiAnalyzer` 포트 뒤에서 CLI 방식과 SDK 방식을 선택하도록 구성하는 편이 적합하다.

DB 역시 현재는 파일 H2로 단순하게 시작하지만, 여러 서버에서 장기 운영하거나 인프라팀 도구와 연결하려면
MariaDB/PostgreSQL 같은 관계형 DB 또는 시계열 DB로 분리하는 것이 자연스럽다. 이때 Health Watch Bot은 알림/리포트
계층을 담당하고, Grafana/Zabbix/Prometheus는 시각화와 인프라 관측 계층을 담당하는 식으로 역할을 나눌 수 있다.

---

## 8. 핵심 메시지

> `/health`는 “서비스가 살아 있는가”를 빠르게 확인하는 endpoint이고,  
> `/health/detail`은 “왜 느리거나 불안정한가”를 개발팀이 분석하기 위한 endpoint다.
>
> Health Watch Bot은 이 두 endpoint를 주기 수집해 rule로 장애 신호를 판단하고,  
> LLM을 사용해 개발팀이 바로 대응할 수 있는 형태의 알림과 정기 리포트로 변환한다.
>
> 인프라팀은 같은 endpoint를 alive/네트워크/리소스 관점에서 활용하고,  
> 개발팀은 API/DB/runtime metric을 기반으로 원인 분석과 개선 backlog를 만든다.
