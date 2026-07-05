package com.beomsu.pay.shared;

import java.util.Objects;

/**
 * 금액 값 객체.
 *
 * <p>KRW는 소수점이 없으므로 최소 단위를 {@code long}(원)으로 다룬다. 부동소수점을 쓰지 않는다.
 * 금액은 음수가 될 수 없으며(차/대변의 방향은 별도로 표현), 불변(immutable)이다.
 */
public record Money(long amount) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (amount < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다: " + amount);
        }
    }

    public static Money of(long amount) {
        return new Money(amount);
    }

    public Money plus(Money other) {
        return new Money(this.amount + other.amount);
    }

    /**
     * 차감. 결과가 음수면 예외 — 잔액 부족/과다 취소를 값 타입 수준에서 차단한다.
     */
    public Money minus(Money other) {
        if (this.amount < other.amount) {
            throw new IllegalArgumentException(
                    "차감 결과가 음수입니다: %d - %d".formatted(this.amount, other.amount));
        }
        return new Money(this.amount - other.amount);
    }

    public boolean isGreaterThan(Money other) {
        return this.amount > other.amount;
    }

    public boolean isZero() {
        return this.amount == 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.amount, other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amount == money.amount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount);
    }
}
