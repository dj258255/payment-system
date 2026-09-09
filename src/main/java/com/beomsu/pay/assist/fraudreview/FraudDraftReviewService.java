package com.beomsu.pay.assist.fraudreview;

import com.beomsu.pay.assist.review.TextDistance;
import com.beomsu.pay.fraud.FraudReviewFacts;
import com.beomsu.pay.fraud.FraudReviewFactsPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * 심사 초안을 <b>같은 사실에서 둘 만들어 익명으로 비교</b>한다.
 *
 * <p><b>왜 필요한가</b>: 상황 3.2 의 상담 초안은 블라인드 비교로 템플릿보다 수정량이 적은
 * 것을 보고 켰다. 심사 초안에는 그 근거가 없다. 근거 없이 켜면 이 프로젝트가 상황 3.3 에서
 * "규칙 대비 개선 0 이라 껐다" 고 적은 기준에 자기가 걸린다.
 *
 * <p><b>한 번에 둘을 뽑는다.</b> provider 를 바꿔 두 번 돌리면 두 번째 회차는 심사자가 이미
 * 답을 아는 상태라 표본이 오염된다. V41 이 상담 초안에서 같은 이유로 한 행에 둘을 붙였다.
 *
 * <p><b>표시 순서를 심사 id 로 정한다.</b> 난수를 쓰면 다시 열 때 순서가 바뀌어 채점이 화면과
 * 안 맞는다. 엔티티가 공개 시점에 그 값을 굳힌다.
 */
@Service
public class FraudDraftReviewService {

    private final FraudReviewFactsPort facts;
    private final FraudReviewDraftPort model;
    private final TemplateFraudReviewAdapter baseline;
    private final FraudDraftReviewRepository repository;

    /**
     * <b>모델 구현을 명시적으로 고른다.</b> 주입에 맡기면 안 된다.
     *
     * <p>여기서 {@code FraudReviewDraftPort} 하나를 받게 두면 모델이 안 떠 있을 때 템플릿이
     * 주입돼 <b>템플릿 대 템플릿</b>을 비교하게 된다. 편집률 차이가 0 으로 수렴하고, 그
     * 수치를 보고 "모델이 템플릿보다 낫지 않다" 고 읽게 된다. 실제로는 비교를 안 한 것이다.
     *
     * <p>모델이 없으면 {@code null} 로 둔다. 그러면 {@link #reveal} 이 공개를 거절하고,
     * 표본이 안 쌓이는 이유가 <b>모델이 꺼져 있다</b>로 드러난다.
     */
    public FraudDraftReviewService(FraudReviewFactsPort facts,
                                   List<FraudReviewDraftPort> ports,
                                   TemplateFraudReviewAdapter baseline,
                                   FraudDraftReviewRepository repository) {
        this.facts = facts;
        this.baseline = baseline;
        this.repository = repository;
        this.model = ports.stream()
                .filter(p -> !p.name().equals(baseline.name()))
                .findFirst()
                .orElse(null);
    }

    /** 비교할 모델이 떠 있는가. 화면이 "표본 0건" 과 "모델이 꺼져 있음" 을 구별해야 한다. */
    public boolean modelAvailable() {
        return model != null;
    }

    /** 심사자가 자기 메모를 쓸 자리를 연다. 초안은 아직 안 준다. */
    @Transactional
    public Optional<FraudDraftReview> open(long fraudReviewId, String reviewer) {
        if (facts.factsOf(fraudReviewId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(repository.findByFraudReviewIdAndReviewer(fraudReviewId, reviewer)
                .orElseGet(() -> repository.save(FraudDraftReview.open(fraudReviewId, reviewer))));
    }

    /** 1단계. 초안을 보기 전에 쓴다. */
    @Transactional
    public void blind(long fraudReviewId, String reviewer, String reply) {
        var row = require(fraudReviewId, reviewer);
        row.blind(reply);
    }

    /**
     * 2단계. 같은 사실에서 초안 둘을 뽑아 고정한다.
     *
     * <p>모델 쪽이 비면 <b>공개하지 않는다.</b> 한쪽만 있는 화면을 비교라고 부르면 안 된다.
     */
    @Transactional
    public Optional<FraudDraftReview> reveal(long fraudReviewId, String reviewer) {
        if (model == null) {
            // 모델이 안 떠 있으면 비교가 아니다. 템플릿 둘을 놓고 편집률을 재면
            // 차이가 0 으로 나오고, 그 0 을 "모델이 낫지 않다" 로 읽게 된다.
            return Optional.empty();
        }
        var row = require(fraudReviewId, reviewer);
        var f = facts.factsOf(fraudReviewId).orElse(null);
        if (f == null) {
            return Optional.empty();
        }
        String modelText = model.draft(f).orElse(null);
        String baselineText = baseline.draft(f).orElse(null);
        if (modelText == null || baselineText == null) {
            return Optional.empty();
        }
        row.reveal(modelText, model.name(), baselineText, baseline.name(), showBaselineFirst(fraudReviewId, reviewer));
        return Optional.of(row);
    }

    /** 3단계. 각각을 쓸 만하게 고친 결과를 받는다. */
    @Transactional
    public void edit(long fraudReviewId, String reviewer, String editedModel, String editedBaseline) {
        require(fraudReviewId, reviewer).edit(editedModel, editedBaseline);
    }

    /**
     * 지금까지의 성적.
     *
     * <p><b>중앙값을 쓴다.</b> 표본이 얇을 때 평균은 한 건이 통째로 끌고 간다. 상황 3.2 에서
     * 편집률이 회차마다 두 배 벌어진 것을 보고 정한 방식이다.
     */
    @Transactional(readOnly = true)
    public Stats stats() {
        List<Double> modelRates = new ArrayList<>();
        List<Double> baselineRates = new ArrayList<>();
        for (var row : repository.findAll()) {
            if (!row.complete()) {
                continue;   // 한쪽만 고친 건은 비교가 안 된다
            }
            modelRates.add(TextDistance.editRatio(row.getModelDraft(), row.getEditedDraft()));
            baselineRates.add(TextDistance.editRatio(row.getBaselineDraft(), row.getEditedBaseline()));
        }
        return new Stats(modelRates.size(), median(modelRates), median(baselineRates));
    }

    /**
     * 블라인드 비교 결과.
     *
     * @param judged        두 쪽 다 고친 건수
     * @param modelMedian   모델 초안의 편집률 중앙값. 표본이 없으면 {@code null}
     * @param baselineMedian 템플릿 초안의 편집률 중앙값
     */
    public record Stats(int judged, Double modelMedian, Double baselineMedian) {

        /** 이 밑으로는 중앙값을 근거로 쓰지 않는다. 상담 초안 실험이 12건이었다. */
        public static final int MIN_JUDGED = 12;

        /**
         * 켤 조건을 넘겼나.
         *
         * <p><b>표본이 얇으면 무조건 아니다.</b> 3건에서 모델이 낮게 나온 것은 우연과 구별되지
         * 않는다. 그리고 이것이 참이어도 <b>수치를 성능으로 인용하면 안 된다.</b> 상황 3.2 에서
         * 같은 12건을 두 번 돌려 회차마다 두 배 벌어진 것을 봤다. 여기서 얻는 것은 방향뿐이다.
         */
        public boolean modelBeatsBaseline() {
            return judged >= MIN_JUDGED && modelMedian != null && baselineMedian != null
                    && modelMedian < baselineMedian;
        }
    }

    /**
     * 템플릿을 먼저 보여줄지. <b>심사 id 와 사람으로 정한다.</b>
     *
     * <p>난수면 다시 열 때 순서가 바뀌고, 그러면 채점이 그 화면과 안 맞는다. 사람까지 넣는
     * 이유는 한 사람이 여러 건을 볼 때 늘 같은 쪽이 먼저 오면 그 자체가 기준이 되기 때문이다.
     */
    private boolean showBaselineFirst(long fraudReviewId, String reviewer) {
        CRC32 crc = new CRC32();
        crc.update((fraudReviewId + ":" + reviewer).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc.getValue() % 2 == 0;
    }

    private FraudDraftReview require(long fraudReviewId, String reviewer) {
        return repository.findByFraudReviewIdAndReviewer(fraudReviewId, reviewer)
                .orElseThrow(() -> new IllegalStateException(
                        "먼저 열어야 한다 review=%d reviewer=%s".formatted(fraudReviewId, reviewer)));
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> v = new ArrayList<>(values);
        v.sort(Double::compare);
        int m = v.size() / 2;
        return v.size() % 2 == 1 ? v.get(m) : (v.get(m - 1) + v.get(m)) / 2.0;
    }
}
