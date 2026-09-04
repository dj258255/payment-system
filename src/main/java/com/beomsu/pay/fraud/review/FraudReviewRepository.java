package com.beomsu.pay.fraud.review;

import com.beomsu.pay.fraud.internal.FraudBlacklistReloader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface FraudReviewRepository extends JpaRepository<FraudReview, Long> {

    /** 상태별 심사 항목 전건 — 기동 시 블랙리스트 재적재(FraudBlacklistReloader)가 REJECTED 전건을 되읽는다. */
    List<FraudReview> findByStatus(FraudReviewStatus status);

    /** 어드민 관측용 — 상태별 심사 항목 페이지(기본 PENDING = 미결 건). 전건 로딩 방지 위해 페이지 단위. */
    Page<FraudReview> findByStatus(FraudReviewStatus status, Pageable pageable);

    /** 상태별 건수 — 심사 큐 깊이 게이지가 쓴다. 스크레이프마다 count 한 번만 돈다. */
    long countByStatus(FraudReviewStatus status);

    /**
     * 해당 상태에서 <b>가장 오래 기다린</b> 항목의 생성 시각. 큐 깊이만으로는 적체를 못 본다.
     * 열 건이 방금 들어온 것과 한 건이 이틀 묵은 것은 위험이 다르다.
     */
    @Query("select min(r.createdAt) from FraudReview r where r.status = :status")
    Optional<Instant> findOldestCreatedAt(@Param("status") FraudReviewStatus status);
}
