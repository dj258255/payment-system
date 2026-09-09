package com.beomsu.pay.assist.fraudreview;

import com.beomsu.pay.fraud.FraudReviewFacts;

import java.util.Optional;

/**
 * FDS 심사 초안 생성기. <b>구현이 템플릿이든 모델이든 이 뒤에 숨는다.</b>
 *
 * <p>{@code assist.draft.DraftPort} 와 같은 이유로 포트를 먼저 둔다. 무엇이 좋은 초안인지를
 * 먼저 재고 나서 구현을 고른다. 포트가 있으면 {@code FraudReviewDraftEvalTest} 가 어떤 구현이
 * 오든 같은 잣대로 잰다.
 *
 * <p><b>여기서 판정을 내지 않는다.</b> 초안은 심사자가 읽는 글이고, 승인·거부는 사람이 누른다.
 * 포트가 {@code approve} 같은 것을 돌려주게 만들면 그 순간 초안이 판정이 된다.
 */
public interface FraudReviewDraftPort {

    /**
     * 사실 묶음으로 심사자용 초안을 만든다.
     *
     * @param facts 쓸 수 있는 사실의 전부. <b>여기 없는 숫자를 만들면 안 된다</b>
     * @return 초안. 만들 수 없으면 {@link Optional#empty()} —
     *         <b>빈 값이 지어낸 문장보다 낫다</b>
     */
    Optional<String> draft(FraudReviewFacts facts);

    /** 어느 구현이 만들었는지. 초안에 실어 심사자가 출처를 알게 한다. */
    String name();
}
