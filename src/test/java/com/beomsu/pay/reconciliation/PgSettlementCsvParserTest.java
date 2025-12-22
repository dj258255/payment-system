package com.beomsu.pay.reconciliation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgSettlementCsvParserTest {

    private final PgSettlementCsvParser parser = new PgSettlementCsvParser();

    private static InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("정상 파싱: orderNo/amount 헤더에서 컬럼을 찾아 외부 기록을 만든다")
    void parsesOrderNoAndAmount() {
        var result = parser.parse(csv("""
                orderNo,amount
                ord-1,10000
                ord-2,20000
                """));

        assertThat(result.records()).containsExactly(
                new ExternalRecord("ord-1", 10_000),
                new ExternalRecord("ord-2", 20_000));
        assertThat(result.skipped()).isZero();
    }

    @Test
    @DisplayName("헤더 별칭·컬럼 순서·부가 컬럼 무관: order_no/결제금액, 승인번호 같은 부가 컬럼 무시")
    void handlesHeaderAliasesAndExtraColumns() {
        var result = parser.parse(csv("""
                거래일시,order_no,승인번호,결제금액
                2026-07-01,ord-9,APPROVAL-1,15000
                """));

        assertThat(result.records()).containsExactly(new ExternalRecord("ord-9", 15_000));
        assertThat(result.skipped()).isZero();
    }

    @Test
    @DisplayName("한글 헤더 별칭: 주문번호/결제금액도 인식한다")
    void handlesKoreanHeaderAliases() {
        var result = parser.parse(csv("""
                주문번호,결제금액
                ord-k,30000
                """));

        assertThat(result.records()).containsExactly(new ExternalRecord("ord-k", 30_000));
    }

    @Test
    @DisplayName("공백·대소문자 혼용 헤더와 값 앞뒤 공백도 견고하게 처리")
    void handlesMessyHeaderAndWhitespace() {
        var result = parser.parse(csv("""
                 OrderNo , Settle_Amount
                ord-1, 1234567
                """));

        assertThat(result.records()).containsExactly(new ExternalRecord("ord-1", 1_234_567));
    }

    @Test
    @DisplayName("불량 행 skip + 스킵 카운트: 잘못된 amount, 빈 orderNo, 컬럼 부족 행")
    void skipsBadRowsAndCounts() {
        var result = parser.parse(csv("""
                orderNo,amount
                ord-1,10000
                ord-bad,not-a-number
                ,5000
                ord-2,20000

                ord-short
                합계,,,999
                """));

        // 유효: ord-1, ord-2 (공백행은 카운트하지 않음)
        assertThat(result.records()).containsExactly(
                new ExternalRecord("ord-1", 10_000),
                new ExternalRecord("ord-2", 20_000));
        // 스킵: not-a-number, 빈 orderNo, ord-short(컬럼 부족), 합계행(amount 빈 값) = 4
        assertThat(result.skipped()).isEqualTo(4);
    }

    @Test
    @DisplayName("음수 amount는 skip한다(금액 음수 금지)")
    void skipsNegativeAmount() {
        var result = parser.parse(csv("""
                orderNo,amount
                ord-neg,-100
                ord-ok,100
                """));

        assertThat(result.records()).containsExactly(new ExternalRecord("ord-ok", 100));
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("필수 컬럼(orderNo/amount)이 없는 헤더 → INVALID_SETTLEMENT_FILE")
    void rejectsHeaderMissingRequiredColumns() {
        assertThatThrownBy(() -> parser.parse(csv("""
                거래일시,승인번호
                2026-07-01,APPROVAL-1
                """)))
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("INVALID_SETTLEMENT_FILE"));
    }

    @Test
    @DisplayName("빈 파일 → INVALID_SETTLEMENT_FILE")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> parser.parse(csv("")))
                .isInstanceOf(ReconciliationException.class)
                .satisfies(e -> assertThat(((ReconciliationException) e).code())
                        .isEqualTo("INVALID_SETTLEMENT_FILE"));
    }

    @Test
    @DisplayName("UTF-8 BOM이 붙은 헤더도 첫 컬럼명을 올바로 인식")
    void handlesUtf8Bom() {
        var result = parser.parse(csv("﻿orderNo,amount\nord-1,100\n"));

        assertThat(result.records()).containsExactly(new ExternalRecord("ord-1", 100));
    }
}
