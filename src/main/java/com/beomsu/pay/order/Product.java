package com.beomsu.pay.order;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 카탈로그 — <b>가격의 서버 측 원천(source of truth)</b>.
 *
 * <p>가격은 절대 클라이언트에서 받지 않는다. 클라이언트가 보낸 가격으로 주문 금액을 계산하면,
 * 금액 위변조 검증({@link Order#verifyAmount})의 기준값 자체가 조작 가능해져 검증이 무의미해진다.
 * 주문 생성 시 서버가 이 카탈로그에서 가격을 조회해 {@link OrderItem} 스냅샷을 만든다.
 */
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    /** 상품 식별자(외부에서 지정). 재고({@link Stock})와 같은 키 공간을 쓴다. */
    @Id
    private long productId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private long price;

    private Product(long productId, String name, long price) {
        if (price < 0) {
            throw new OrderException("INVALID_REQUEST", "상품 가격은 음수일 수 없습니다: " + price);
        }
        this.productId = productId;
        this.name = name;
        this.price = price;
    }

    public static Product of(long productId, String name, long price) {
        return new Product(productId, name, price);
    }
}
