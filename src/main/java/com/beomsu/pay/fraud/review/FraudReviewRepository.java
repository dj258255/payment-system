package com.beomsu.pay.fraud.review;

import com.beomsu.pay.fraud.internal.FraudBlacklistReloader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface FraudReviewRepository extends JpaRepository<FraudReview, Long> {

    /** 상태별 심사 항목 전건 — 기동 시 블랙리스트 재적재(FraudBlacklistReloader)가 REJECTED 전건을 되읽는다. */
    List<FraudReview> findByStatus(FraudReviewStatus status);

    /** 어드민 관측용 — 상태별 심사 항목 페이지(기본 PENDING = 미결 건). 전건 로딩 방지 위해 페이지 단위. */
    Page<FraudReview> findByStatus(FraudReviewStatus status, Pageable pageable);
}
