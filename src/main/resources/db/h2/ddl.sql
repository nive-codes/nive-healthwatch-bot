-- Health Watch Bot 스키마 (H2, MODE=MariaDB). 관측 데이터 5테이블.
-- 스키마는 여기서 수동 관리한다(ddl-auto=none). 컬럼 추가는 CREATE IF NOT EXISTS / ALTER ... ADD IF NOT EXISTS 멱등으로.

-- API 단위 health/detail 수집 스냅샷
create table if not exists health_snapshot
(
    id                      bigint auto_increment primary key,
    service_name            varchar(50)                          not null comment 'user-api / admin-api / driver-api',
    status                  varchar(20)                          not null comment 'ok / degraded / timeout / error',
    http_status             int                                  null comment 'HTTP status code',
    response_time_ms        bigint                               null comment 'endpoint 응답 시간',
    uptime_sec              bigint                               null comment 'runtime uptime',
    working_set_mb          double                               null comment 'process working set',
    gc_heap_mb              double                               null comment 'GC heap',
    thread_count            int                                  null comment 'thread count',
    request_count_1m        bigint                               null comment '최근 1분 요청 수',
    request_count_5m        bigint                               null comment '최근 5분 요청 수',
    error_count_5m          bigint                               null comment '최근 5분 오류 수',
    error_rate_5m           double                               null comment '최근 5분 오류율',
    avg_latency_ms_5m       double                               null comment '최근 5분 평균 latency',
    p95_latency_ms_5m       double                               null comment '최근 5분 p95 latency',
    in_flight_request_count int                                  null comment '처리 중 요청 수',
    raw_payload             text                                 null comment '원본 JSON',
    collected_at            datetime default current_timestamp() not null comment '수집 시각'
) comment 'API health/detail 수집 스냅샷';
create index if not exists ix_snapshot_svc_time on health_snapshot (service_name, collected_at);
alter table health_snapshot add column if not exists health_outcome           varchar(20) null comment '/health 호출 outcome';
alter table health_snapshot add column if not exists health_http_status       int         null comment '/health HTTP status';
alter table health_snapshot add column if not exists health_response_time_ms  bigint      null comment '/health 응답 시간';
alter table health_snapshot add column if not exists detail_outcome           varchar(20) null comment '/health/detail 호출 outcome';
alter table health_snapshot add column if not exists detail_http_status       int         null comment '/health/detail HTTP status';
alter table health_snapshot add column if not exists detail_response_time_ms  bigint      null comment '/health/detail 응답 시간';

-- DB별 상태
create table if not exists health_db_snapshot
(
    id                    bigint auto_increment primary key,
    snapshot_id           bigint                               not null comment 'health_snapshot FK',
    service_name          varchar(50)                          not null comment '대상 API',
    db_name               varchar(80)                          not null comment 'DB 이름',
    ok                    boolean                              null comment 'DB 정상 여부',
    last_query_latency_ms bigint                               null comment 'DB query latency',
    last_success_at       datetime                             null comment 'API 기록 최근 성공 시각',
    last_failure_at       datetime                             null comment 'API 기록 최근 실패 시각',
    detail                varchar(1000)                        null comment '실패 상세',
    collected_at          datetime default current_timestamp() not null comment '수집 시각'
) comment 'DB별 상태 스냅샷';
create index if not exists ix_dbsnapshot_svc_db_time on health_db_snapshot (service_name, db_name, collected_at);

-- Rule trigger 결과
create table if not exists anomaly_event
(
    id                bigint auto_increment primary key,
    service_name      varchar(50)                          not null comment '대상 API',
    severity          varchar(20)                          not null comment 'warning / critical',
    trigger_type      varchar(40)                          not null comment 'health_503 / timeout / db_fail / latency_spike / error_rate 등',
    db_name           varchar(80)                          null comment 'DB 관련 trigger 의 대상 DB',
    trigger_summary   varchar(1000)                        null comment '간단 요약',
    baseline_json     text                                 null comment '비교 기준',
    current_json      text                                 null comment '현재 상태',
    status            varchar(20)                          not null comment 'open / notified / resolved / suppressed',
    first_detected_at datetime default current_timestamp() not null comment '최초 감지 시각',
    last_detected_at  datetime default current_timestamp() not null comment '마지막 감지 시각',
    last_notified_at  datetime                             null comment '마지막 알림 발송 시각(cooldown 기준)'
) comment 'Rule trigger 이벤트';
create index if not exists ix_anomaly_svc_type_status on anomaly_event (service_name, trigger_type, status);

-- AI CLI 분석 결과
create table if not exists ai_report
(
    id               bigint auto_increment primary key,
    anomaly_event_id bigint                               not null comment 'anomaly_event FK',
    prompt           text                                 null comment 'AI 전달 prompt',
    response         text                                 null comment 'AI 응답',
    exit_code        int                                  null comment 'CLI exit code',
    timed_out        boolean  default false               not null comment 'timeout 여부',
    created_at       datetime default current_timestamp() not null comment '생성 시각'
) comment 'AI CLI 분석 결과';
create index if not exists ix_aireport_anomaly on ai_report (anomaly_event_id);

-- 알림 발송 결과
create table if not exists notification_log
(
    id               bigint auto_increment primary key,
    anomaly_event_id bigint                               null comment 'anomaly_event FK(정기 리포트는 null)',
    channel          varchar(20)                          not null comment 'MAIL / SLACK / DISCORD / TELEGRAM',
    success          boolean                              not null comment '성공 여부',
    response_code    int                                  null comment 'webhook/mail response code',
    error_message    varchar(1000)                        null comment '실패 메시지',
    sent_at          datetime default current_timestamp() not null comment '발송 시각'
) comment '알림 발송 로그';
create index if not exists ix_notif_anomaly on notification_log (anomaly_event_id);
-- 발송 추적 보강(멱등): 어느 프로젝트/어느 sender 로 나갔는지
alter table notification_log add column if not exists service_id bigint null comment 'monitored_service FK';
alter table notification_log add column if not exists sender_id  bigint null comment 'notification_sender FK';

-- ============================================================
-- 감시 대상(프로젝트) 레지스트리 — "무엇을 감시할지"의 SSoT. yml 대신 DB 가 진실 원천.
-- ============================================================
create table if not exists monitored_service
(
    id         bigint auto_increment primary key,
    name       varchar(50)                          not null comment 'user-api / admin-api / driver-api',
    base_url   varchar(300)                         not null comment 'health endpoint base url',
    profile    varchar(30)                          null comment 'USER_API / ADMIN_API / DRIVER_API (프롬프트 컨텍스트)',
    enabled    boolean  default true                not null comment '감시 활성 여부',
    created_at datetime default current_timestamp() not null,
    updated_at datetime default current_timestamp() null,
    unique (name)
) comment '감시 대상 서버 레지스트리';

-- ============================================================
-- 알림 sender — supertype/subtype(class-table inheritance).
-- 1차(base): 프로젝트 × 채널. 2차(detail): 채널별 실제 발송 정보.
-- 프로젝트당 여러 행 → 다중 채널·다중 수신자 발송.
-- ============================================================
create table if not exists notification_sender
(
    id         bigint auto_increment primary key,
    service_id bigint                               not null comment 'monitored_service FK(프로젝트)',
    channel    varchar(20)                          not null comment 'MAIL / SLACK / DISCORD / TELEGRAM',
    enabled    boolean  default true                not null comment '이 sender 활성 여부',
    created_at datetime default current_timestamp() not null,
    updated_at datetime default current_timestamp() null
) comment '알림 sender base(프로젝트×채널)';
create index if not exists ix_sender_service on notification_sender (service_id, enabled);

-- 2차: 메일 — MAIN 담당자 플래그 보유(프로젝트당 1명 권장). 18시 요약이 MAIN 에게 발송됨.
create table if not exists notification_sender_mail
(
    id           bigint auto_increment primary key,
    sender_id    bigint                not null comment 'notification_sender FK(1:1)',
    email        varchar(200)          not null comment '수신 메일 주소',
    display_name varchar(100)          null comment '표시명',
    is_main      boolean default false not null comment 'MAIN 담당자 여부(프로젝트 요약 수신)',
    unique (sender_id)
) comment '메일 sender detail';

-- 2차: 디스코드 — 웹훅 URL 은 AES-GCM 암호화 저장(준시크릿).
create table if not exists notification_sender_discord
(
    id          bigint auto_increment primary key,
    sender_id   bigint       not null comment 'notification_sender FK(1:1)',
    webhook_url varchar(600) not null comment '디스코드 webhook URL(AES-GCM 암호문)',
    thread_id   varchar(50)  null comment '스레드 타깃(옵션)',
    unique (sender_id)
) comment '디스코드 sender detail';

-- 2차: Slack — 웹훅 URL 은 AES-GCM 암호화 저장(준시크릿).
create table if not exists notification_sender_slack
(
    id           bigint auto_increment primary key,
    sender_id    bigint       not null comment 'notification_sender FK(1:1)',
    webhook_url  varchar(600) not null comment 'Slack incoming webhook URL(AES-GCM 암호문)',
    channel_name varchar(100) null comment 'Slack channel override(옵션, webhook 정책에 따라 무시될 수 있음)',
    unique (sender_id)
) comment 'Slack sender detail';

-- 2차: 텔레그램 — bot token 은 AES-GCM 암호화 저장(준시크릿).
create table if not exists notification_sender_telegram
(
    id        bigint auto_increment primary key,
    sender_id bigint      not null comment 'notification_sender FK(1:1)',
    bot_token varchar(300) null comment '텔레그램 bot token(AES-GCM 암호문)',
    chat_id   varchar(60) not null comment '텔레그램 chat id',
    unique (sender_id)
) comment '텔레그램 sender detail';
alter table notification_sender_telegram add column if not exists bot_token varchar(300) null comment '텔레그램 bot token(AES-GCM 암호문)';
