package com.beomsu.pay.subscription;

import com.beomsu.pay.shared.crypto.EncryptedStringConverter;
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

    /**
     * 빌링키 토큰. 단독 유출로는 결제 불가(customerKey와 이중 키). 민감정보라 저장 시 envelope
     * 암호화(@Convert)한다. 암호문은 매번 달라 유니크·동등검색이 불가능하므로 유니크는 아래
     * {@link #billingKeyIndex}(블라인드 인덱스)로 이전했고, 컬럼 길이도 암호문에 맞춰 600으로 넓혔다.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 600)
    private String billingKey;

    /**
     * 빌링키의 블라인드 인덱스(HMAC-SHA256, 결정적). 암호화로 잃은 조회·유니크를 대체한다 —
     * 같은 빌링키는 항상 같은 인덱스가 되어 {@code findByBillingKeyIndex}로 찾고, 여기에 유니크 제약을 둔다.
     * 서비스가 {@code BlindIndexer.index(billingKey)}로 계산해 넘긴다(엔티티는 secret을 몰라야 한다).
     */
    @Column(nullable = false, unique = true, length = 64)
    private String billingKeyIndex;

    /**
     * 고객 식별자. <b>반드시 UUID 같은 무작위 값</b>을 저장한다.
     * 이메일·자동증가 ID는 금지 — 예측 가능한 값이면 이중 키 구조의 방어력이 무너진다.
     * 비밀이 아닌 공개 식별자(이중 키의 공개 절반)라 암호화하지 않는다.
     */
    @Column(nullable = false, length = 64)
    private String customerKey;

    @Column(nullable = false)
    private long userId;

    @Column(nullable = false)
    private Instant createdAt;

    private BillingKey(String billingKey, String billingKeyIndex, String customerKey, long userId) {
        this.billingKey = billingKey;
        this.billingKeyIndex = billingKeyIndex;
        this.customerKey = customerKey;
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    /**
     * 빌링키 발급. {@code billingKeyIndex}는 서비스가 {@code BlindIndexer.index(billingKey)}로 미리
     * 계산해 넘긴다(엔티티가 blind-index secret 빈을 주입받지 않게 하기 위함).
     */
    public static BillingKey of(String billingKey, String billingKeyIndex, String customerKey, long userId) {
        return new BillingKey(billingKey, billingKeyIndex, customerKey, userId);
    }
}
