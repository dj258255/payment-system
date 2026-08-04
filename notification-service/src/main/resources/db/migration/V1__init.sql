-- 알림 서비스 전용 스키마(pay_notification) 초기화.
-- pay 모놀리스 V1의 두 테이블을 신규 서비스의 V1으로 가져온다.

-- 멱등 소비 이력 — (event_key, consumer) 유니크가 at-least-once 재배달의 방어선
create table processed_events (
    id           bigint       not null auto_increment,
    event_key    varchar(200) not null,
    consumer     varchar(100) not null,
    processed_at datetime(6)  not null,
    primary key (id),
    constraint uk_processed unique (event_key, consumer)
) engine=InnoDB;

-- 발송 실패 격리(DLQ) — 어드민이 조회·재처리한다
create table dead_letters (
    id          bigint        not null auto_increment,
    event_type  varchar(100)  not null,
    event_key   varchar(200)  not null,
    order_no    varchar(64)   not null,
    payment_id  bigint        not null,
    amount      bigint        not null,
    fail_reason varchar(1000) null,
    retry_count integer       not null,
    created_at  datetime(6)   not null,
    primary key (id)
) engine=InnoDB;

create index idx_dead_letters_created on dead_letters (created_at);
