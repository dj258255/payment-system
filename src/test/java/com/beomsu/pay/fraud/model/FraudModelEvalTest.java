package com.beomsu.pay.fraud.model;

import com.beomsu.pay.fraud.internal.CardBlocklist;
import com.beomsu.pay.fraud.internal.FdsDecision;
import com.beomsu.pay.fraud.internal.FraudCheckRequest;
import com.beomsu.pay.fraud.internal.FraudService;
import com.beomsu.pay.fraud.velocity.VelocityCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 규칙 · 모델 · 결합을 <b>같은 홀드아웃</b>에서 잰다.
 *
 * <p><b>이 하네스가 모델보다 먼저 있어야 한다.</b> 무엇을 맞다고 볼지 정해 두지 않으면 답이
 * 나온 뒤에 그 답에 맞춰 기준을 고치게 된다. 상황 3 에서 네 자리 전부에 같은 순서를 썼다.
 *
 * <p><b>학습과 평가를 가른다.</b> 가중치는 학습 절반에서만 뽑고, 성적은 그 절반을 한 번도
 * 안 본 홀드아웃에서 낸다. 같은 표본에서 맞추고 재면 늘 좋아 보인다.
 *
 * <p><b>이 수치로 할 수 있는 말은 하나다.</b> 규칙은 건 하나만 보므로 건들 사이의 관계로 만든
 * 패턴을 못 잡는다. <b>실 트래픽에서 이런 패턴이 얼마나 되는지는 모른다.</b>
 */
@DisplayName("이상거래 탐지 — 규칙이 못 보는 축을 모델이 얼마나 잡나")
class FraudModelEvalTest {

    /** 코퍼스 seed. 고정하지 않으면 회차마다 표본이 바뀌어 비교가 안 된다. */
    private static final long SEED = 20260909L;

    private static final int NORMALS = 600;
    private static final int PER_AXIS = 40;      // 부정 여섯 축 × 40 = 240

    /** 모델을 심사로 올릴 임계. 학습 절반에서 <b>정상 오탐 5%</b> 가 되는 지점으로 잡는다. */
    private static final double TARGET_FALSE_POSITIVE = 0.05;

    // ── 규칙 쪽 ─────────────────────────────────────────────────────────────

    /**
     * 실 {@link FraudService} 를 그대로 돌린다. 규칙을 테스트에 다시 구현하면 <b>거기서
     * 규칙을 약하게 써 놓고 모델이 이겼다고 말하게 된다.</b>
     *
     * <p>속도 카운터만 가짜로 둔다. 실제 창은 Redis 1 분인데, 여기서는 <b>같은 카드·기기·IP
     * 로 1 분 안에 몇 번 왔는지</b>를 창에서 직접 세어 넘긴다. 부정 패턴이 전부 간격을 벌려
     * 놓았으므로 이 값은 대개 1 이다. <b>규칙에 유리하게 세는 쪽으로 틀리지 않았는지</b>가
     * 중요해서, 아래 {@code rulesCatchNothingByDesign} 이 그것을 따로 고정한다.
     */
    private static FraudService rules(Map<String, Integer> velocity) {
        VelocityCounter counter = mock(VelocityCounter.class);
        when(counter.recordAndCount(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> velocity.getOrDefault(inv.getArgument(0, String.class), 1));

        CardBlocklist blocklist = mock(CardBlocklist.class);
        when(blocklist.contains(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        FraudService s = new FraudService(counter, blocklist);
        ReflectionTestUtils.setField(s, "velocityThreshold", 5);
        ReflectionTestUtils.setField(s, "velocityWeight", 40);
        ReflectionTestUtils.setField(s, "amountThreshold", FraudCorpus.AMOUNT_THRESHOLD);
        ReflectionTestUtils.setField(s, "amountWeight", 30);
        ReflectionTestUtils.setField(s, "blacklistWeight", 100);
        ReflectionTestUtils.setField(s, "deviceThreshold", 8);
        ReflectionTestUtils.setField(s, "deviceWeight", 25);
        ReflectionTestUtils.setField(s, "ipThreshold", 15);
        ReflectionTestUtils.setField(s, "ipWeight", 20);
        ReflectionTestUtils.setField(s, "longInstallmentMonths", 6);
        ReflectionTestUtils.setField(s, "installmentAmountThreshold", FraudCorpus.AMOUNT_THRESHOLD);
        ReflectionTestUtils.setField(s, "installmentWeight", 20);
        ReflectionTestUtils.setField(s, "blockThreshold", 100);
        ReflectionTestUtils.setField(s, "reviewThreshold", 60);
        ReflectionTestUtils.setField(s, "challengeThreshold", 40);
        return s;
    }

    /** 1 분 창 안의 건수를 창에서 직접 센다. 실제 Redis 카운터가 세는 것과 같은 뜻이다. */
    private static Map<String, Integer> velocityOf(FraudCorpus.Case c) {
        Map<String, Integer> counts = new HashMap<>();
        var now = c.current().at();
        int card = 0, device = 0, ip = 0;
        for (TxnRecord t : c.window()) {
            if (Duration.between(t.at(), now).abs().compareTo(Duration.ofMinutes(1)) > 0) {
                continue;
            }
            card++;
            if (java.util.Objects.equals(t.deviceId(), c.current().deviceId())) device++;
            if (java.util.Objects.equals(t.ip(), c.current().ip())) ip++;
        }
        counts.put("card:" + c.cardKey(), card);
        if (c.current().deviceId() != null) counts.put("device:" + c.current().deviceId(), device);
        if (c.current().ip() != null) counts.put("ip:" + c.current().ip(), ip);
        return counts;
    }

    /** 규칙이 이 건을 사람 앞에 올리는가. {@code CHALLENGE} 위부터 사람이 본다. */
    private static boolean ruleFlags(FraudCorpus.Case c) {
        var r = rules(velocityOf(c)).evaluate(new FraudCheckRequest(
                0L, c.cardKey(), c.current().ip(), c.current().deviceId(),
                c.current().amount(), c.current().installmentMonths()));
        return r.decision() != FdsDecision.ALLOW;
    }

    // ── 학습 ────────────────────────────────────────────────────────────────

    /**
     * 경사하강법. <b>라이브러리를 안 쓴다</b> — 여덟 개 피처에 로지스틱 하나라 스무 줄이면 되고,
     * 의존을 늘리면 CI 에서 이 평가가 도는 조건이 하나 더 생긴다.
     */
    private static LogisticFraudRiskModel train(List<FraudCorpus.Case> cases, int epochs, double lr) {
        int dim = SequenceFeatures.NAMES.size();
        double[] w = new double[dim];
        double b = 0;

        List<double[]> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (var c : cases) {
            xs.add(featuresOf(c).toArray());
            ys.add(c.fraud() ? 1.0 : 0.0);
        }

        for (int e = 0; e < epochs; e++) {
            double[] gw = new double[dim];
            double gb = 0;
            for (int i = 0; i < xs.size(); i++) {
                double[] x = xs.get(i);
                double z = b;
                for (int j = 0; j < dim; j++) z += w[j] * x[j];
                double p = 1.0 / (1.0 + Math.exp(-z));
                double err = p - ys.get(i);
                for (int j = 0; j < dim; j++) gw[j] += err * x[j];
                gb += err;
            }
            for (int j = 0; j < dim; j++) w[j] -= lr * gw[j] / xs.size();
            b -= lr * gb / xs.size();
        }
        return new LogisticFraudRiskModel(w, b);
    }

    private static SequenceFeatures featuresOf(FraudCorpus.Case c) {
        return SequenceFeatures.of(c.window(), c.current(), FraudCorpus.AMOUNT_THRESHOLD);
    }

    /** 학습 절반에서 정상 오탐이 {@code TARGET_FALSE_POSITIVE} 가 되는 임계를 고른다. */
    private static double thresholdAt(LogisticFraudRiskModel model, List<FraudCorpus.Case> train) {
        List<Double> normalScores = train.stream().filter(c -> !c.fraud())
                .map(c -> model.risk(featuresOf(c))).sorted().toList();
        if (normalScores.isEmpty()) {
            return 0.5;
        }
        int idx = (int) Math.floor(normalScores.size() * (1 - TARGET_FALSE_POSITIVE));
        return normalScores.get(Math.min(idx, normalScores.size() - 1));
    }

    // ── 채점 ────────────────────────────────────────────────────────────────

    private record Score(String name, long caught, long missed, long falseAlarm, long normals) {
        double recall() { return caught + missed == 0 ? 0 : (double) caught / (caught + missed); }
        double falseAlarmRate() { return normals == 0 ? 0 : (double) falseAlarm / normals; }
    }

    private static Score score(String name, List<FraudCorpus.Case> holdout,
                               java.util.function.Predicate<FraudCorpus.Case> flags) {
        long caught = 0, missed = 0, falseAlarm = 0, normals = 0;
        for (var c : holdout) {
            boolean f = flags.test(c);
            if (c.fraud()) {
                if (f) caught++; else missed++;
            } else {
                normals++;
                if (f) falseAlarm++;
            }
        }
        return new Score(name, caught, missed, falseAlarm, normals);
    }

    // ── 시험 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("규칙은 이 패턴들을 거의 못 잡는다 — 그래서 모델을 붙일 자리가 있다")
    void rulesCatchNothingByDesign() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        var frauds = corpus.stream().filter(FraudCorpus.Case::fraud).toList();

        long caught = frauds.stream().filter(FraudModelEvalTest::ruleFlags).count();
        double recall = (double) caught / frauds.size();

        System.out.printf("%n  [규칙만] 부정 %d건 중 %d건 탐지 (재현율 %.1f%%)%n",
                frauds.size(), caught, recall * 100);

        // 코퍼스를 규칙이 못 잡게 짠 것이 실제로 그런지 고정한다. 여기가 깨지면
        // 코퍼스가 규칙이 잡는 패턴으로 흘러간 것이고, 그때 모델 성적은 의미가 없다.
        assertThat(recall)
                .as("규칙이 이미 잡는 패턴이면 모델이 규칙을 다시 배우는 것을 재게 된다")
                .isLessThan(0.15);
    }

    @Test
    @DisplayName("규칙 · 모델 · 결합을 같은 홀드아웃에서 잰다")
    void compareOnHoldout() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        int cut = corpus.size() / 2;
        var train = corpus.subList(0, cut);
        var holdout = corpus.subList(cut, corpus.size());

        var model = train(train, 4_000, 3.0);
        double threshold = thresholdAt(model, train);

        java.util.function.Predicate<FraudCorpus.Case> byModel =
                c -> model.risk(featuresOf(c)) >= threshold;

        var results = List.of(
                score("규칙만", holdout, FraudModelEvalTest::ruleFlags),
                score("모델만", holdout, byModel),
                score("규칙 또는 모델", holdout, c -> ruleFlags(c) || byModel.test(c)));

        System.out.printf("%n  학습 %d건 · 홀드아웃 %d건 (부정 %d건) · 모델 임계 %.3f%n",
                train.size(), holdout.size(),
                holdout.stream().filter(FraudCorpus.Case::fraud).count(), threshold);
        System.out.printf("  %-14s %8s %8s %10s%n", "", "탐지", "놓침", "정상 오탐");
        for (var s : results) {
            System.out.printf("  %-14s %6d건 %6d건 %6d건 (%.1f%%)   재현율 %.1f%%%n",
                    s.name(), s.caught(), s.missed(), s.falseAlarm(),
                    s.falseAlarmRate() * 100, s.recall() * 100);
        }

        var rule = results.get(0);
        var m = results.get(1);
        var combined = results.get(2);

        assertThat(m.recall())
                .as("규칙이 못 보는 축으로 만든 패턴이라 모델이 더 잡아야 한다")
                .isGreaterThan(rule.recall());
        assertThat(combined.recall())
                .as("결합은 어느 한쪽보다 덜 잡을 수 없다")
                .isGreaterThanOrEqualTo(m.recall());
        assertThat(m.falseAlarmRate())
                .as("임계를 학습 절반의 정상 오탐 5%%에 맞췄다. 홀드아웃에서 크게 벌어지면 과적합이다")
                .isLessThan(0.15);
    }

    @Test
    @DisplayName("어느 축을 잡고 어느 축을 놓치는지 갈라 본다 — 전체 재현율 하나로는 못 고른다")
    void perAxisRecall() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        int cut = corpus.size() / 2;
        var model = train(corpus.subList(0, cut), 4_000, 3.0);
        double threshold = thresholdAt(model, corpus.subList(0, cut));
        var holdout = corpus.subList(cut, corpus.size());

        Map<String, long[]> byAxis = new LinkedHashMap<>();   // [탐지, 전체]
        for (var c : holdout) {
            if (!c.fraud()) continue;
            long[] t = byAxis.computeIfAbsent(c.axis(), k -> new long[2]);
            if (model.risk(featuresOf(c)) >= threshold) t[0]++;
            t[1]++;
        }

        System.out.printf("%n  [모델] 축별 재현율%n");
        byAxis.forEach((axis, t) -> System.out.printf("    %-16s %2d/%2d (%.0f%%)%n",
                axis, t[0], t[1], 100.0 * t[0] / t[1]));

        assertThat(byAxis).as("여섯 축이 홀드아웃에 다 있어야 한다").hasSize(6);
    }

    @Test
    @DisplayName("어느 피처가 점수를 올렸는지 사람이 대조할 수 있어야 한다")
    void contributorsAreReadable() {
        var corpus = new FraudCorpus(SEED).build(NORMALS, PER_AXIS);
        var model = train(corpus.subList(0, corpus.size() / 2), 4_000, 3.0);

        var testing = corpus.stream().filter(c -> c.axis().equals("card_testing")).findFirst().orElseThrow();
        String[] top = model.topContributors(featuresOf(testing), 3);

        System.out.printf("%n  카드 테스팅 건의 상위 기여 피처: %s%n", String.join(", ", top));
        assertThat(top).hasSize(3);
        // 모델이 낸 점수만 주면 심사자가 확인할 방법이 없다. 피처 이름이 함께 나가야 한다.
        assertThat(String.join(",", top))
                .containsAnyOf(SequenceFeatures.NAMES.toArray(String[]::new));
    }
}
