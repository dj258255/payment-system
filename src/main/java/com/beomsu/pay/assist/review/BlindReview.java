package com.beomsu.pay.assist.review;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 블라인드 리뷰 한 건 — 초안이 <b>쓸 만한지</b>를 재는 표본 (ADR-014).
 *
 * <p><b>순서가 이 실험의 전부다.</b> 사람이 모델 초안을 보기 <b>전에</b> 자기 답을 먼저 쓴다.
 * 보고 나서 쓰면 그 문장에 끌려가(앵커링) "고칠 게 없었다"와 "고칠 생각이 안 났다"가
 * 구분되지 않는다. 그래서 상태 전이를 엔티티가 강제한다 —
 * {@code blindReply} 없이는 {@link #reveal} 을 부를 수 없다.
 *
 * <p>모델 초안을 <b>공개 시점에 고정</b>하는 이유: 모델은 같은 입력에도 매번 다르게 쓴다.
 * 나중에 다시 뽑으면 사람이 실제로 본 것과 다른 문장을 채점하게 된다.
 */
@Entity
@Table(name = "blind_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BlindReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long reconResultId;
    private String orderNo;
    private String reviewer;

    @Column(columnDefinition = "TEXT")
    private String blindReply;
    private Instant blindAt;

    @Column(columnDefinition = "TEXT")
    private String modelDraft;
    private String modelSource;
    private Instant revealedAt;

    @Column(columnDefinition = "TEXT")
    private String editedDraft;
    private Instant editedAt;

    private Instant createdAt;

    private BlindReview(Long reconResultId, String orderNo, String reviewer) {
        this.reconResultId = reconResultId;
        this.orderNo = orderNo;
        this.reviewer = reviewer;
        this.createdAt = Instant.now();
    }

    static BlindReview start(Long reconResultId, String orderNo, String reviewer) {
        return new BlindReview(reconResultId, orderNo, reviewer);
    }

    /** 1단계 — 사실만 보고 쓴 답. 한 번만 받는다(고쳐 쓰면 이미 본 셈이 된다). */
    void submitBlind(String reply) {
        if (blindReply != null) {
            throw BlindReviewException.outOfOrder(
                    "이미 블라인드 답변을 제출했습니다. 다시 쓸 수 없습니다.");
        }
        if (reply == null || reply.isBlank()) {
            throw BlindReviewException.invalid("빈 답변은 표본이 되지 않습니다.");
        }
        this.blindReply = reply.trim();
        this.blindAt = Instant.now();
    }

    /** 2단계 — 모델 초안 공개. <b>1단계 전에는 못 부른다.</b> */
    void reveal(String draft, String source) {
        if (blindReply == null) {
            throw BlindReviewException.outOfOrder(
                    "블라인드 답변을 먼저 제출해야 합니다. 초안을 먼저 보면 표본이 오염됩니다.");
        }
        if (modelDraft != null) {
            return;              // 멱등 — 이미 본 것을 그대로 돌려준다
        }
        this.modelDraft = draft;
        this.modelSource = source;
        this.revealedAt = Instant.now();
    }

    /** 3단계 — 모델 초안을 발송 가능하게 고친 결과. */
    void submitEdited(String edited) {
        if (modelDraft == null) {
            throw BlindReviewException.outOfOrder("초안을 공개하기 전에는 수정본을 받을 수 없습니다.");
        }
        this.editedDraft = edited == null ? "" : edited.trim();
        this.editedAt = Instant.now();
    }

    public boolean blindDone()  { return blindReply != null; }
    public boolean revealed()   { return modelDraft != null; }
    public boolean editDone()   { return editedAt != null; }
}
