package com.beomsu.pay.reconciliation.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, Long> {

    /** 어드민 관측용 — 상태별 대사 결과 페이지(운영이 PENDING 예외 큐를 조회). 전건 로딩 방지 위해 페이지 단위. */
    Page<ReconciliationResult> findByStatus(ReconStatus status, Pageable pageable);

    /** 타임라인 조립용(ADR-011). 같은 주문이 여러 날 대사에 걸릴 수 있어 목록이다 — 시간대 경계 판단의 재료. */
    java.util.List<ReconciliationResult> findByOrderNoOrderByIdAsc(String orderNo);

    /** SLO 게이지용 — 상태별 대사 결과 건수(운영이 PENDING 적체를 관측). */
    long countByStatus(ReconStatus status);

    /** 재실행 멱등: 그 거래일 판정을 지우고 다시 쓴다. 같은 파일을 두 번 올려도 큐가 늘지 않는다. */
    void deleteByTradeDate(LocalDate tradeDate);
}
