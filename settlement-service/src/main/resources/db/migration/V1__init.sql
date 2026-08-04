-- 정산 서비스 전용 스키마(pay_settlement) 초기화.
--
-- pay 모놀리스의 V1/V9/V11이 진화시킨 최종 형태를 신규 서비스의 V1으로 통합한다
-- (분리된 서비스는 자기 스키마 이력을 처음부터 소유한다). 모놀리스와 달리 status를
-- MySQL enum이 아니라 varchar(20)로 둔다 — 값 추가에 DDL 변경이 필요 없고
-- @Enumerated(STRING) validate와 그대로 맞는다.

create table settlement_items (
    id             bigint       not null auto_increment,
    payment_id     bigint       not null,
    order_no       varchar(64)  not null,
    amount         bigint       not null,
    confirmed_date date         not null,
    status         varchar(20)  not null,
    primary key (id),
    constraint uk_settlement_item_payment unique (payment_id)
) engine=InnoDB;

-- 배치 집계 조회(findByStatusAndConfirmedDate) 경로
create index idx_settlement_items_status_date on settlement_items (status, confirmed_date);

create table settlements (
    id              bigint       not null auto_increment,
    settlement_date date         not null,
    gross_amount    bigint       not null,
    fee_amount      bigint       not null,
    fee_vat_amount  bigint       not null,
    net_amount      bigint       not null,
    item_count      integer      not null,
    payout_date     date         not null,
    status          varchar(20)  not null,
    created_at      datetime(6)  not null,
    paid_out_at     datetime(6)  null,
    primary key (id),
    -- 배치 재실행 멱등의 물리적 최후 방어선 — 같은 날짜 정산은 하나만 존재한다
    constraint uk_settlement_date unique (settlement_date)
) engine=InnoDB;
