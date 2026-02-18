package com.beomsu.pay.dispute;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface DisputeRepository extends JpaRepository<Dispute, Long> {

    /** 멱등 개시 판정 — 이미 받은 차지백인지. */
    boolean existsByChargebackId(String chargebackId);

    Optional<Dispute> findByChargebackId(String chargebackId);

    /** 최근 분쟁 목록 — 어드민 감사용. Top50으로 상한. */
    List<Dispute> findTop50ByOrderByIdDesc();
}
