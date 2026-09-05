package com.beomsu.pay.assist.narrative;

import com.beomsu.pay.assist.draft.DraftService;
import com.beomsu.pay.assist.draft.FactPack;
import com.beomsu.pay.assist.draft.NumericProvenanceGuard;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 운영자용 서술을 만들고 <b>숫자 출처를 검증</b>한다.
 *
 * <p><b>가드가 하나뿐인 이유</b>: 이 글은 사내 화면에만 뜬다. 고객에게 나가지 않으므로 내부 용어
 * 누출도, 말투도 문제가 아니다. 상태를 바꾸지도 않는다. <b>남는 위험은 없는 숫자를 만드는 것
 * 하나</b>라, 가드도 그 하나만 둔다. 가드를 필요 없는 곳에 늘리면 무엇이 왜 있는지 흐려진다.
 *
 * <p><b>버린다, 고치지 않는다.</b> 출처 없는 숫자가 하나라도 있으면 문단을 통째로 버린다.
 * 틀린 숫자가 섞인 서술은 <b>없는 것보다 나쁘다</b> — 운영자가 그걸 근거로 확정하면 틀린 판단이
 * 장부에 남는다(상황 13에서 같은 이유로 조회 실패를 조용히 넘기지 않기로 했다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineNarrativeService {

    private final DraftService draftService;
    private final TimelineNarrativePort port;
    private final NumericProvenanceGuard numberGuard;
    private final MeterRegistry registry;

    /**
     * 주문 하나를 한 문단으로 엮는다.
     *
     * @return 서술. 사실이 없거나 모델이 기권했거나 <b>출처 없는 숫자가 섞였으면</b> empty
     */
    public Optional<Narrative> narrate(String orderNo) {
        FactPack facts = draftService.factsFor(orderNo, null);
        if (facts.facts().isEmpty()) {
            count("no_facts");
            return Optional.empty();
        }

        Optional<String> raw = port.narrate(facts);
        if (raw.isEmpty()) {
            count("abstained");
            return Optional.empty();
        }

        List<String> unsourced = numberGuard.verify(raw.get(), facts);
        if (!unsourced.isEmpty()) {
            count("unsourced_figures");
            log.info("[narrative] 출처 없는 숫자가 있어 서술을 버림 order={} figures={}",
                    orderNo, unsourced);
            return Optional.empty();
        }

        count("narrated");
        // 무엇이 쓴 문장인지 함께 낸다 — 사람이 템플릿과 모델을 구별할 수 있어야 한다.
        return Optional.of(new Narrative(raw.get(), port.name(), facts.complete()));
    }

    private void count(String outcome) {
        registry.counter("assist.narrative", "outcome", outcome).increment();
    }

    /**
     * @param text     운영자가 읽을 문단
     * @param source   무엇이 썼는가(템플릿/모델). 화면에 함께 띄운다
     * @param complete 타임라인이 완전했는가. false 면 화면이 그 사실을 같이 알려야 한다
     */
    public record Narrative(String text, String source, boolean complete) {
    }
}
