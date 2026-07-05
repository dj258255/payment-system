package com.beomsu.pay.point;

/**
 * 포인트 이력 유형.
 *
 * <ul>
 *   <li>{@link #USE} — 결제 시 포인트 차감(복합결제의 선점).</li>
 *   <li>{@link #RESTORE} — 카드 승인 실패 시 차감분을 되돌리는 보상.</li>
 *   <li>{@link #REFUND} — 부분취소 환불로 포인트를 되돌림.</li>
 * </ul>
 */
public enum PointHistoryType {
    USE,
    RESTORE,
    REFUND
}
