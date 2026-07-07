package com.beomsu.pay.fraud;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FDS 심사 큐 운영 어드민 서비스 — 심사 항목 조회·승인·거부를 오케스트레이션한다.
 *
 * <p>사후 탐지가 적재한 PENDING 항목을 어드민이 검토해 종결한다. 승인은 정상 거래로 확인만 하고,
 * 거부는 부정 거래로 확정하며 그 카드({@code cardKey})를 {@link FraudService#blacklistCard}로
 * 블랙리스트에 등록한다 — 이후 같은 카드의 결제는 판정 엔진이 BLOCK으로 막는다(사후→사전 피드백 루프).
 * 상태 가드(PENDING만 처리)는 {@link FraudReview} 엔티티가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class FraudReviewAdminService {

    private final FraudReviewRepository repository;
    private final FraudService fraudService;

    /** 상태별 심사 항목 페이지(기본 PENDING = 미결 건). */
    @Transactional(readOnly = true)
    public Page<FraudReviewView> list(FraudReviewStatus status, Pageable pageable) {
        return repository.findByStatus(status, pageable)
                .map(FraudReviewView::from);
    }

    /** 승인 — PENDING → APPROVED(정상 거래로 확인). */
    @Transactional
    public FraudReviewView approve(long id, String reviewer) {
        FraudReview review = load(id);
        review.approve(reviewer);
        repository.saveAndFlush(review);
        return FraudReviewView.from(review);
    }

    /**
     * 거부 — PENDING → REJECTED(부정 거래로 확인). 상태 전이 후 그 카드를 블랙리스트에 등록해,
     * 향후 같은 카드의 결제가 판정 엔진에서 BLOCK되도록 한다.
     */
    @Transactional
    public FraudReviewView reject(long id, String reviewer) {
        FraudReview review = load(id);
        review.reject(reviewer);
        fraudService.blacklistCard(review.getCardKey());
        repository.saveAndFlush(review);
        return FraudReviewView.from(review);
    }

    private FraudReview load(long id) {
        return repository.findById(id).orElseThrow(() -> FraudException.notFound(id));
    }
}
