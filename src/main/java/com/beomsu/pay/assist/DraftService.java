package com.beomsu.pay.assist;

import com.beomsu.pay.reconciliation.CauseSuggestion;
import com.beomsu.pay.reconciliation.ReconciliationAdminService;
import com.beomsu.pay.timeline.OrderTimeline;
import com.beomsu.pay.timeline.OrderTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 초안 생성의 전 과정을 잇는다 — 사실 조립 → 초안 → 검증.
 *
 * <p><b>순서가 설계다.</b> 검증을 마지막에 두는 게 아니라, 검증을 통과하지 못한 초안이
 * 이 메서드 밖으로 나가지 못하게 한다. 화면이 "검증 실패지만 참고용으로 보여주기"를
 * 고를 수 없어야 한다 — 사람은 눈앞의 문장에 끌려가고(앵커링), 그 문장이 틀렸다는 걸
 * 확인할 방법은 어차피 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftService {

    private final OrderTimelineService timelineService;
    private final ReconciliationAdminService reconciliationAdmin;
    private final DraftPort draftPort;
    private final NumberGuard numberGuard;
    private final CustomerGlossary glossary;
    private final DraftRubric rubric;

    /**
     * 주문 한 건의 상담 초안을 만든다.
     *
     * @param orderNo       대상 주문
     * @param reconResultId 대사 불일치 id. 주면 규칙 분류기의 원인 제안을 함께 싣는다.
     *                      없으면 null — <b>원인을 모르면 모르는 채로 둔다</b>
     */
    @Transactional(readOnly = true)
    public CsDraft draftFor(String orderNo, Long reconResultId) {
        OrderTimeline timeline = timelineService.assemble(orderNo);
        FactPack facts = FactPack.from(timeline, causeHint(reconResultId));

        if (facts.empty()) {
            return CsDraft.none(orderNo, draftPort.name(), timeline.complete());
        }

        Optional<String> text = draftPort.draft(facts);
        if (text.isEmpty()) {
            return CsDraft.none(orderNo, draftPort.name(), timeline.complete());
        }

        List<String> rejected = numberGuard.verify(text.get(), facts);
        if (!rejected.isEmpty()) {
            // 버리되 조용히 버리지 않는다. 어느 구현이 무슨 값을 지어냈는지가
            // 그 구현을 계속 쓸지 판단하는 유일한 근거다.
            log.warn("초안 검증 실패 — 버림. orderNo={} source={} 문제={}",
                    orderNo, draftPort.name(), rejected);
            return CsDraft.rejected(orderNo, draftPort.name(), rejected, timeline.complete());
        }
        // 용어 누출은 <버리지 않고> 표시한다. 틀린 게 아니라 다듬을 문제다.
        // 세어서 남기는 이유는 프롬프트를 고쳤을 때 나아졌는지 알기 위해서다.
        List<String> jargon = glossary.findJargon(text.get());
        if (!jargon.isEmpty()) {
            log.info("초안에 내부 용어 남음 order={} source={} 용어={}",
                    orderNo, draftPort.name(), jargon);
        }
        // 정답 없이 채점한다(reference-free). 버리지 않고 실어 보낸다 —
        // 상담원이 무엇이 빠졌는지 보고 판단할 근거가 된다.
        DraftRubric.Score score = rubric.score(text.get(), facts);
        if (!score.failed().isEmpty()) {
            log.info("초안 루브릭 {}/{} order={} 미충족={}",
                    score.passed(), score.total(), orderNo, score.failed());
        }
        return CsDraft.ok(orderNo, text.get(), draftPort.name(), timeline.complete(),
                jargon, score);
    }

    /**
     * 사실 묶음만 만든다 — <b>초안은 만들지 않는다.</b>
     *
     * <p>블라인드 리뷰 1단계가 이걸 쓴다. 사람이 사실만 보고 자기 답을 먼저 써야 하는데,
     * 초안을 만들어 두면 응답 어딘가에 실릴 유혹이 생긴다. <b>만들지 않는 것</b>이
     * 가장 확실한 차단이다.
     */
    @Transactional(readOnly = true)
    public FactPack factsFor(String orderNo, Long reconResultId) {
        return FactPack.from(timelineService.assemble(orderNo), causeHint(reconResultId));
    }

    /**
     * 규칙 분류기의 최상위 제안을 한 줄로. 실패해도 초안은 만든다 —
     * 원인 힌트는 있으면 좋은 것이지 없으면 못 만드는 것이 아니다.
     */
    private String causeHint(Long reconResultId) {
        if (reconResultId == null) {
            return null;
        }
        try {
            List<CauseSuggestion> suggestions = reconciliationAdmin.suggestCauses(reconResultId);
            if (suggestions.isEmpty()) {
                return null;
            }
            CauseSuggestion top = suggestions.get(0);
            return "%s (%s) — %s".formatted(top.cause(), top.confidence(), top.evidence());
        } catch (RuntimeException e) {
            log.warn("원인 제안 조회 실패 reconResultId={}", reconResultId, e);
            return null;
        }
    }
}
