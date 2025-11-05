package com.beomsu.pay.subscription;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 빌링키 — 카드번호·유효기간·CVC를 암호화한 토큰. 최초 1회 인증 후 매 주기 무인증 결제에 쓴다.
 *
 * <p>{@code customerKey}와 빌링키의 <b>이중 키 구조</b>가 보안의 핵심이다. 빌링키 단독이 유출돼도
 * customerKey 없이는 결제할 수 없다.
 */
@Entity
@Table(name = "billing_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 빌링키 토큰. 단독 유출로는 결제 불가(customerKey와 이중 키). */
    @Column(nullable = false, unique = true, length = 200)
    private String billingKey;

    /**
     * 고객 식별자. <b>반드시 UUID 같은 무작위 값</b>을 저장한다.
     * 이메일·자동증가 ID는 금지 — 예측 가능한 값이면 이중 키 구조의 방어력이 무너진다.
     */
    @Column(nullable = false, length = 64)
    private String customerKey;

    @Column(nullable = false)
    private long userId;

    @Column(nullable = false)
    private Instant createdAt;

    private BillingKey(String billingKey, String customerKey, long userId) {
        this.billingKey = billingKey;
        this.customerKey = customerKey;
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public static BillingKey of(String billingKey, String customerKey, long userId) {
        return new BillingKey(billingKey, customerKey, userId);
    }
}
