package com.beomsu.paycontracts;

/**
 * Kafka 토픽명 상수 — 발행({@code @Externalized})과 소비({@code @KafkaListener})가
 * 같은 상수를 참조해 토픽명이 컴파일 타임에 묶이게 한다.
 *
 * <p>명명 규칙: {@code <도메인>.<과거형 사건>}. 토픽당 이벤트 1종(스키마 단일화)이며,
 * 라우팅 키는 전 토픽 공통으로 {@code orderNo}다 — 같은 주문의 이벤트가 각 토픽의
 * 같은 파티션에 들어가 순서가 보존된다. 토픽 사이의 순서는 보장되지 않으므로
 * 소비자는 순서 역전을 상태 가드로 방어해야 한다(예: 정산의 릴리스 선착 대기).
 */
public final class PayTopics {

    /** 결제 승인 완료 — {@link PaymentConfirmedEvent} */
    public static final String PAYMENT_CONFIRMED = "payment.confirmed";

    /** 결제 취소(전액/부분) — {@link PaymentCanceledEvent} */
    public static final String PAYMENT_CANCELED = "payment.canceled";

    /** 에스크로 릴리스(구매확정) — {@link EscrowReleasedEvent} */
    public static final String ESCROW_RELEASED = "escrow.released";

    private PayTopics() {
    }
}
