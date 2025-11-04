package com.beomsu.pay.wallet;

/**
 * 월렛 거래 유형.
 *
 * <ul>
 *   <li>{@link #CHARGE} — 선불 충전(잔액 증가). 전금법 기명 200만원 한도 검증 대상.</li>
 *   <li>{@link #USE} — 결제 시 잔액 차감. 마이너스 잔액이 될 수 없다.</li>
 *   <li>{@link #REFUND} — 결제 취소 환불로 잔액을 되돌림.</li>
 * </ul>
 */
public enum WalletTransactionType {
    CHARGE,
    USE,
    REFUND
}
