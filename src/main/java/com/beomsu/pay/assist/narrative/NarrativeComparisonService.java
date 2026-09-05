package com.beomsu.pay.assist.narrative;

import com.beomsu.pay.assist.draft.FactPack;
import com.beomsu.pay.assist.draft.DraftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 같은 사실에 대한 두 서술을 <b>출처를 가린 채</b> 내놓고, 사람이 고른 것을 모은다.
 *
 * <p>이 기능을 켤지 말지는 이 표가 쌓인 다음에 정한다. 그 전에는 기본값을 바꾸지 않는다 —
 * 잔여 후보에서 그 순서를 어겼다가 되돌렸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NarrativeComparisonService {

    private final DraftService draftService;
    private final List<TimelineNarrativePort> ports;
    private final NarrativePreferenceRepository repository;

    /**
     * 비교 한 건을 만든다. <b>제시 순서를 무작위로 섞는다</b> — 사람이 앞쪽을 고르는 경향이
     * 있으므로, 순서를 고정하면 그 경향이 결과에 그대로 실린다.
     *
     * @return 구현이 둘 미만이거나 어느 한쪽이 못 만들면 empty — 비교할 것이 없다
     */
    @Transactional
    public Optional<Comparison> open(String orderNo) {
        if (ports.size() < 2) {
            return Optional.empty();
        }
        FactPack facts = draftService.factsFor(orderNo, null);
        if (facts.facts().isEmpty()) {
            return Optional.empty();
        }

        TimelineNarrativePort first = ports.get(0);
        TimelineNarrativePort second = ports.get(1);
        Optional<String> t1 = first.narrate(facts);
        Optional<String> t2 = second.narrate(facts);
        if (t1.isEmpty() || t2.isEmpty()) {
            return Optional.empty();
        }

        boolean flip = ThreadLocalRandom.current().nextBoolean();
        TimelineNarrativePort a = flip ? second : first;
        TimelineNarrativePort b = flip ? first : second;
        String textA = flip ? t2.get() : t1.get();
        String textB = flip ? t1.get() : t2.get();

        NarrativePreference saved = repository.save(
                NarrativePreference.of(orderNo, a.name(), textA, b.name(), textB));
        // 출처를 내보내지 않는다. 이름이 보이면 블라인드가 아니다.
        return Optional.of(new Comparison(saved.getId(), orderNo, textA, textB));
    }

    /** 고른다. 고르고 나서야 어느 쪽이 무엇이었는지 알려준다. */
    @Transactional
    public Optional<Revealed> choose(long comparisonId, String choice, String reviewer) {
        return repository.findById(comparisonId).map(p -> {
            p.choose(choice, reviewer);
            return new Revealed(p.getId(), p.getSourceA(), p.getSourceB(), p.chosenSource());
        });
    }

    /** 집계 — 무엇이 몇 번 선택됐나. 동점은 따로 센다. */
    @Transactional(readOnly = true)
    public Stats stats() {
        List<NarrativePreference> chosen = repository.findByChoiceIsNotNull();
        long ties = chosen.stream().filter(p -> "TIE".equals(p.getChoice())).count();
        long template = chosen.stream().filter(p -> "template".equals(p.chosenSource())).count();
        long model = chosen.size() - ties - template;
        return new Stats(chosen.size(), template, model, ties);
    }

    /** 사람에게 보이는 것 — <b>출처가 없다</b>. */
    public record Comparison(Long id, String orderNo, String textA, String textB) {
    }

    /** 고른 뒤에 공개되는 것. */
    public record Revealed(Long id, String sourceA, String sourceB, String chosenSource) {
    }

    /**
     * @param total    고른 건수
     * @param template 모델 없는 쪽이 선택된 수
     * @param model    모델 쪽이 선택된 수
     * @param ties     차이 없음
     */
    public record Stats(long total, long template, long model, long ties) {
    }
}
