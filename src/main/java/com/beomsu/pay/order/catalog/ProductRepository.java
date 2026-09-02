package com.beomsu.pay.order.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface ProductRepository extends JpaRepository<Product, Long> {
}
