package com.beomsu.pay.order;

import com.beomsu.pay.order.RefundAllocator.RefundAllocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundAllocatorTest {

    @Test
    @DisplayName("취소액 ≤ 포인트결제분: 전액 포인트에서 환불 (카드 0)")
    void refundsFromPointFirst() {
        // 결제: 포인트 3,000 + 카드 7,000. 취소 2,000 → 전부 포인트에서.
        RefundAllocation a = RefundAllocator.allocate(2_000, 3_000, 7_000);

        assertThat(a.fromPoint()).isEqualTo(2_000);
        assertThat(a.fromCard()).isEqualTo(0);
        assertThat(a.fromPoint() + a.fromCard()).isEqualTo(2_000); // 불변식
    }

    @Test
    @DisplayName("취소액 > 포인트결제분: 포인트를 먼저 소진하고 나머지를 카드에서 (어뷰징 방지)")
    void refundsPointThenCard() {
        // 결제: 포인트 3,000 + 카드 7,000. 취소 5,000 → 포인트 3,000 + 카드 2,000.
        RefundAllocation a = RefundAllocator.allocate(5_000, 3_000, 7_000);

        assertThat(a.fromPoint()).isEqualTo(3_000); // 포인트 우선 전액
        assertThat(a.fromCard()).isEqualTo(2_000);
        assertThat(a.fromPoint() + a.fromCard()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("전액 취소: 포인트 전액 + 카드 전액")
    void refundsFullAmount() {
        RefundAllocation a = RefundAllocator.allocate(10_000, 3_000, 7_000);

        assertThat(a.fromPoint()).isEqualTo(3_000);
        assertThat(a.fromCard()).isEqualTo(7_000);
        assertThat(a.fromPoint()).isLessThanOrEqualTo(3_000); // 포인트 결제분 이하
        assertThat(a.fromCard()).isLessThanOrEqualTo(7_000);  // 카드 결제분 이하
    }
}
