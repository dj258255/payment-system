package com.beomsu.pay.assist.review;

import java.util.List;

/**
 * 리뷰 화면에 줄 것. <b>단계에 따라 보여줄 것이 다르다.</b>
 *
 * <p>{@code modelDraft} 는 1단계를 마치기 전까지 <b>null 로 나간다.</b> 화면이 고를 수
 * 있게 두면 언젠가 누군가 "먼저 참고만" 하고, 그 순간 표본이 오염된다.
 *
 * @param stage       BLIND(사실만 보고 쓰는 중) / REVEALED(초안 공개됨) / DONE(수정까지 끝)
 * @param facts       사람에게 보여줄 사실들. <b>1단계에서 이것만 본다</b>
 * @param blindReply  사람이 사실만 보고 쓴 답
 * @param modelDraft  모델 초안. 1단계 전에는 null
 * @param editedDraft 사람이 초안을 고친 결과
 */
public record BlindReviewView(long id, long reconResultId, String orderNo, String stage,
                              List<String> facts, String causeHint,
                              String blindReply, String modelDraft, String modelSource,
                              String editedDraft) {
}
