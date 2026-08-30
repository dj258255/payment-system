package com.beomsu.pay.assist.review;

import java.util.List;

/**
 * 표본 집계. <b>표본 수를 항상 같이 낸다</b> — 4건짜리 평균을 근거로 쓰면 안 된다.
 *
 * @param samples          3단계까지 끝난 표본 수
 * @param medianEditRatio  모델 초안 → 상담원 수정본. <b>0이면 그대로, 1이면 통째로 새로 씀</b>
 * @param medianDivergence 블라인드 답 ↔ 모델 초안. 사람과 모델이 얼마나 다르게 썼나
 * @param usedAsIs         전혀 안 고친 건수 (편집률 0.05 미만)
 * @param rewritten        절반 이상 새로 쓴 건수 (편집률 0.5 이상)
 * @param caveat           이 수치를 읽을 때의 한계. <b>숫자와 항상 붙여 다닌다</b>
 */
public record BlindReviewStats(int samples,
                               double medianEditRatio,
                               double medianDivergence,
                               int usedAsIs,
                               int rewritten,
                               List<String> caveat) {
}
