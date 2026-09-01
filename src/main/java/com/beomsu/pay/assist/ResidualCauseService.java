package com.beomsu.pay.assist;

import com.beomsu.pay.reconciliation.CauseSuggestion;
import com.beomsu.pay.reconciliation.ResolveCause;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * 규칙 분류기가 못 가른 건에만 모델을 붙인다. 여섯 개의 가드를 순서대로 건다.
 *
 * <p><b>1. 규칙이 아무것도 못 냈을 때만 부른다.</b> 원인 아홉 종 가운데 여섯은 산수와
 * 조회로 결정된다. 이미 답이 있는 것을 모델에게 추측시키면 나아지는 게 없고 틀릴 자리만 는다.
 *
 * <p><b>2. 출력은 {@link ResolveCause} 안의 값만 받는다.</b> Ramp가 거래 재분류
 * 에이전트에 건 후처리 가드레일과 같다. 목록 밖의 값을 고르면 그 응답을 버린다.
 *
 * <p><b>3. {@link ResolveCause#SUSPECTED_TAMPERING}은 제안 자체를 금지한다.</b>
 * 위변조 의심은 유형으로 배제한다. 이 자리에서 추측이 제일 비싸다.
 *
 * <p><b>4. 신뢰도가 임계 미만이면 기권으로 처리한다.</b> 고위험 분류의 표준 장치인
 * selective prediction 이다. 커버리지를 정확도와 맞바꾼다. {@code OTHER}도 기권으로 친다 —
 * "모르겠다"를 원인 코드로 남기면 집계가 오염된다.
 *
 * <p><b>5. 근거 문장의 숫자는 {@link NumericProvenanceGuard}로 대조한다.</b> 상담 초안과
 * 같은 계약이다. 코드가 낸 값이 아닌 금액·날짜가 하나라도 있으면 제안을 통째로 버린다.
 *
 * <p><b>6. 사실이 불완전하면 아예 부르지 않는다.</b> 타임라인 조회가 한 곳이라도 실패하면
 * 그 사실 묶음으로는 원인을 가릴 수 없다. 프롬프트에 "불완전합니다"라고 적어 줘도
 * <b>세 모델 모두 그걸 무시하고 단정하는 것을 홀드아웃 15건에서 확인했다.</b>
 * 이 가드를 넣자 qwen3:14b 는 4건 중 4건을 기권했고(전 15/15), 8b 와 llama 도
 * 단정이 2건·3건에서 각각 1건으로 줄었다. <b>모델도 프롬프트도 안 바꿨다. 코드가 막았다.</b>
 *
 * <p>이게 이 분야의 표준이기도 하다. 프롬프트로 시키는 기권은 맥락이 부족할 때 실패한다는
 * 것이 보고돼 있고("Prompt-Based Abstention Fails Under Misleading Context"),
 * 가드레일 설계에서도 <b>프롬프트는 행동에 영향을 줄 뿐 강제 가능한 통제 경계를 만들지
 * 못한다</b>고 본다. 모델 가중치는 드리프트하거나 파인튜닝으로 덮이지만 코드의 조건문은
 * 그렇지 않다. 그래서 LLM 호출 <b>전에</b> 도는 결정적 검사를 둔다.
 *
 * <p><b>7. {@code resolve}는 하지 않는다.</b> 이 서비스는 후보를 돌려줄 뿐이고, 확정은
 * 사람이 화면에서 한다. 자동 확정은 그 유형의 실측 오류율이 쌓인 뒤에 따로 결정할 문제다.
 *
 * <p><b>스위치는 {@code app.assist.residual-provider} 하나다.</b> 기본값 {@code template}은
 * 항상 기권하므로 켜 두어도 아무 일이 일어나지 않는다. 상담 초안이 {@code draft-provider}
 * 하나로 같은 일을 한다. 스위치를 둘 두면 어느 쪽이 껐는지 헷갈리고, 켰다고 생각한 채
 * 안 도는 상태가 생긴다.
 *
 * <p><b>{@code ollama}로 바꾸면 즉시 산다.</b> 다만 화면 응답에 모델 왕복이 붙는다 —
 * 실측으로 qwen3:8b 가 2~8초, 14b 가 4~17초였다. 그래서 이 창구를 대사 어드민과
 * 분리해 뒀다. 화면은 규칙 제안을 먼저 그리고 이 후보는 나중에 채우면 된다.
 */
@Service
@RequiredArgsConstructor
public class ResidualCauseService {

    private static final Logger log = LoggerFactory.getLogger(ResidualCauseService.class);

    /** 가드 3 — 모델이 절대 고를 수 없는 원인. */
    static final EnumSet<ResolveCause> FORBIDDEN =
            EnumSet.of(ResolveCause.SUSPECTED_TAMPERING, ResolveCause.OTHER);

    private final DraftService draftService;
    private final NumericProvenanceGuard numberGuard;
    private final Optional<ResidualCausePort> port;
    private final MeterRegistry registry;

    /** 가드 4 — 이 값 미만이면 기권. */
    @Value("${app.assist.residual.min-confidence:70}")
    private int minConfidence;

    /**
     * 규칙이 못 가른 건에 원인 후보를 제안한다.
     *
     * @param rules 규칙 분류기가 낸 제안. <b>비어 있지 않으면 아무것도 하지 않는다</b>
     * @return 후보. 기권했거나 가드에 걸렸으면 {@link Optional#empty()}
     */
    public Optional<ResidualSuggestion> suggest(String orderNo, Long reconResultId,
                                                List<CauseSuggestion> rules) {
        if (port.isEmpty()) {
            return Optional.empty();
        }
        if (rules != null && !rules.isEmpty()) {
            count("skipped_rules_decided");     // 가드 1
            return Optional.empty();
        }

        FactPack facts = draftService.factsFor(orderNo, reconResultId);
        if (facts.facts().isEmpty()) {
            count("no_facts");
            return Optional.empty();
        }
        if (!facts.complete()) {                // 가드 6
            count("incomplete_facts");
            log.info("[residual] 사실이 불완전해 모델을 부르지 않음 order={}", orderNo);
            return Optional.empty();
        }

        Optional<ResidualSuggestion> raw;
        try {
            raw = port.get().suggest(facts);
        } catch (RuntimeException e) {
            count("error");
            log.warn("[residual] 후보 생성 실패 order={} recon={}", orderNo, reconResultId, e);
            return Optional.empty();
        }

        if (raw.isEmpty()) {
            count("abstained");                 // 모델이 스스로 기권
            return Optional.empty();
        }
        ResidualSuggestion s = raw.get();

        if (s.cause() == null || FORBIDDEN.contains(s.cause())) {   // 가드 2·3
            count("forbidden_cause");
            log.info("[residual] 금지된 원인 제안을 버림 order={} cause={}", orderNo, s.cause());
            return Optional.empty();
        }
        if (s.confidence() < minConfidence) {                       // 가드 4
            count("below_threshold");
            return Optional.empty();
        }

        List<String> unsourced = numberGuard.verify(s.rationale(), facts);   // 가드 5
        if (!unsourced.isEmpty()) {
            count("unsourced_figures");
            log.info("[residual] 출처 없는 숫자로 제안을 버림 order={} rejected={}", orderNo, unsourced);
            return Optional.empty();
        }

        count("suggested");
        log.info("[residual] 후보 order={} cause={} confidence={} source={}",
                orderNo, s.cause(), s.confidence(), port.get().name());
        return Optional.of(s);
    }

    private void count(String outcome) {
        registry.counter("assist.residual.outcome", "outcome", outcome).increment();
    }
}
