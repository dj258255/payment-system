package com.beomsu.pay.dispute.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    /** 멱등 개시 판정 — 이미 받은 차지백인지. */
    boolean existsByChargebackId(String chargebackId);

    Optional<Dispute> findByChargebackId(String chargebackId);

    /** 최근 분쟁 목록 — 어드민 감사용. Top50으로 상한. */
    List<Dispute> findTop50ByOrderByIdDesc();

    /** 타임라인 조립용(ADR-011). 한 주문에 분쟁이 여러 번 걸릴 수 있어 목록이다. */
    List<Dispute> findByOrderNoOrderByIdAsc(String orderNo);
}
