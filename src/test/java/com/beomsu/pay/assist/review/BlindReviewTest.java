package com.beomsu.pay.assist.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 블라인드 리뷰의 <b>순서</b>를 지킨다. 이게 이 실험의 유일한 방법론적 근거다.
 *
 * <p>문서나 관례로 두면 사람이 급할 때 건너뛴다. 그리고 건너뛴 표본은
 * <b>조용히 무의미해진다</b> — 겉보기엔 데이터가 쌓이는데 앵커링에 오염돼 있다.
 * 그래서 엔티티가 막는다.
 */
class BlindReviewTest {

    private BlindReview review() {
        return BlindReview.start(1L, "ORD-1", "beomsu");
    }

    @Test
    @DisplayName("블라인드 답변 전에는 초안을 공개할 수 없다")
    void cannotRevealBeforeBlindReply() {
        assertThatThrownBy(() -> review().reveal("모델 초안", "ollama"))
                .isInstanceOf(BlindReviewException.class)
                .hasMessageContaining("먼저 제출");
    }

    @Test
    @DisplayName("블라인드 답변은 한 번만 — 고쳐 쓰면 이미 본 셈이 된다")
    void blindReplyIsWriteOnce() {
        BlindReview r = review();
        r.submitBlind("첫 답변");
        assertThatThrownBy(() -> r.submitBlind("고친 답변"))
                .isInstanceOf(BlindReviewException.class)
                .hasMessageContaining("다시 쓸 수 없");
    }

    @Test
    @DisplayName("빈 답변은 표본이 되지 않는다")
    void blankBlindReplyRejected() {
        assertThatThrownBy(() -> review().submitBlind("   "))
                .isInstanceOf(BlindReviewException.class);
    }

    @Test
    @DisplayName("공개는 멱등 — 다시 불러도 처음 본 초안이 유지된다")
    void revealIsIdempotent() {
        BlindReview r = review();
        r.submitBlind("내 답변");
        r.reveal("첫 초안", "ollama");
        r.reveal("두 번째 초안", "ollama");     // 모델은 매번 다르게 쓴다

        assertThat(r.getModelDraft())
                .as("사람이 실제로 본 것을 채점해야 한다")
                .isEqualTo("첫 초안");
    }

    @Test
    @DisplayName("공개 전에는 수정본을 받을 수 없다")
    void cannotEditBeforeReveal() {
        BlindReview r = review();
        r.submitBlind("내 답변");
        assertThatThrownBy(() -> r.submitEdited("수정본"))
                .isInstanceOf(BlindReviewException.class);
    }

    @Test
    @DisplayName("정상 순서는 통과한다")
    void happyPath() {
        BlindReview r = review();
        r.submitBlind("사실만 보고 쓴 답");
        r.reveal("모델 초안", "ollama:qwen3:8b");
        r.submitEdited("초안을 고친 것");

        assertThat(r.blindDone()).isTrue();
        assertThat(r.revealed()).isTrue();
        assertThat(r.editDone()).isTrue();
    }

    @Test
    @DisplayName("순서 위반은 오류가 아니라 <설계된 거절>이다 — 코드로 구분된다")
    void outOfOrderHasItsOwnCode() {
        assertThatThrownBy(() -> review().reveal("초안", "ollama"))
                .isInstanceOf(BlindReviewException.class)
                .extracting(e -> ((BlindReviewException) e).code())
                .as("500이 아니라 409로 나가야 호출자가 버그와 구분한다")
                .isEqualTo("REVIEW_OUT_OF_ORDER");
    }

    @Test
    @DisplayName("빈 답변은 순서 위반이 아니라 입력 오류 — 400과 409를 구분한다")
    void blankInputIsNotAnOrderViolation() {
        assertThatThrownBy(() -> review().submitBlind(" "))
                .isInstanceOf(BlindReviewException.class)
                .extracting(e -> ((BlindReviewException) e).code())
                .isEqualTo("INVALID_REVIEW_INPUT");
    }
}
