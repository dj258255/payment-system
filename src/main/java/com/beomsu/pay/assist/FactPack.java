package com.beomsu.pay.assist;

import com.beomsu.pay.timeline.OrderTimeline;
import com.beomsu.pay.timeline.TimelineEntry;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 초안이 쓸 수 있는 <b>사실의 전부</b>. 여기 없는 숫자는 초안에 나오면 안 된다.
 *
 * <p>이게 단순한 DTO가 아니라 <b>계약</b>인 이유: {@link NumberGuard}가 초안에서 뽑은
 * 숫자를 이 목록과 대조해 하나라도 없으면 초안을 버린다. 즉 이 레코드가
 * "모델에게 허용된 어휘"를 정의한다.
 *
 * @param orderNo   주문번호. 문장에 그대로 나가도 되는 유일한 식별자다
 * @param facts     사람이 읽을 사실 문장들. <b>코드가 만든다</b> — 타임라인 요약을 그대로 옮긴다
 * @param amounts   등장이 허용된 금액(원). 타임라인과 대사 결과에서만 나온다
 * @param dates     등장이 허용된 날짜
 * @param causeHint 규칙 분류기가 제안한 원인. 없으면 null — <b>모르면 모른다고 둔다</b>
 * @param complete  타임라인이 완전한가. false면 초안에 "일부 확인 불가"를 명시해야 한다
 */
public record FactPack(String orderNo,
                       List<String> facts,
                       Set<Long> amounts,
                       Set<LocalDate> dates,
                       String causeHint,
                       boolean complete) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 근거 문장에 섞인 금액 — {@code 8,888원} / {@code 270 원}. */
    private static final Pattern HINT_AMOUNT = Pattern.compile("([0-9][0-9,]*)\\s*원");

    /**
     * 사실 문장 안의 자릿수 구분된 숫자 — {@code 내부 10,000 / 외부 9,730}.
     *
     * <p>쉼표가 있는 것만 잡는다. 코드가 {@code %,d} 로 찍은 값이라는 뜻이고,
     * 날짜({@code 2026-08-30})나 식별자와 섞이지 않는다.
     */
    private static final Pattern FACT_AMOUNT = Pattern.compile("\\b([0-9]{1,3}(?:,[0-9]{3})+)\\b");

    /**
     * 사실 문장 안의 날짜 — {@code 자동해제 예정 2026-09-06T11:06:29Z}.
     *
     * <p>{@code entry.at()} 만 모으면 <b>사건이 일어난 시각</b>만 들어가고,
     * 문장이 말하는 <b>다른 날짜</b>(에스크로 해제 예정일, 정산 확정일)가 빠진다.
     * 실측에서 반려의 100%가 이 한 값 때문이었다.
     *
     * <p>뒤에 {@code \\b} 를 붙이면 안 된다 — {@code 2026-09-06T11:06} 의 {@code T} 는
     * 단어 문자라 경계가 성립하지 않아 ISO 시각이 통째로 안 잡힌다. 실제로 그렇게 짰다가
     * 테스트가 잡았다. 숫자만 이어지지 않으면 된다.
     */
    private static final Pattern FACT_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})(?!\\d)");

    /**
     * 타임라인과 <b>분류기 근거</b>를 사실 묶음으로 옮긴다. <b>스스로 계산하지 않는다.</b>
     *
     * <p>여기서 차액을 구하거나 합계를 내지 않는 이유: 그 숫자는 이미 대사 엔진과
     * 분류기가 근거와 함께 냈다. 같은 값을 두 곳에서 따로 구하면 언젠가 갈라지고,
     * 갈라진 순간 어느 쪽이 맞는지 알 방법이 없다.
     *
     * <p><b>분류기가 계산한 금액도 사실로 받는다.</b> 이게 이 메서드의 요점이다.
     * 실측에서 이걸 빠뜨려 <b>4건 중 2건이 오반려</b>됐다 — 근거 문장의
     * "차액 8,888원이 수수료(270원)로도 설명되지 않는다"에서 8,888과 270이
     * 타임라인 금액이 아니라는 이유로 걸렸다. 하필 확신이 가장 높은 규칙
     * (수수료 차이, DECISIVE)과 초안이 가장 필요한 건(위변조 의심)이었다.
     *
     * <p>{@link NumberGuard}가 지키려는 것은 "타임라인에 있는 숫자만 쓰라"가 아니라
     * <b>"코드가 낸 숫자만 쓰고 모델이 지어낸 숫자는 쓰지 말라"</b>다. 분류기는 코드이므로
     * 그 근거의 숫자는 검증을 통과해야 맞다. {@code causeHint} 는 항상
     * {@code CauseClassifier} 가 만든 문자열이고, 모델 출력이 여기로 들어오는 경로는 없다.
     * (더 깔끔한 방법은 {@code CauseSuggestion} 이 계산한 값을 구조화해 들고 오는 것이다.
     *  산문을 파싱하면 근거 문구를 고칠 때 허용 범위가 조용히 바뀐다. 그건 다음 과제로 둔다.)
     */
    public static FactPack from(OrderTimeline timeline, String causeHint) {
        List<String> facts = new ArrayList<>();
        Set<Long> amounts = new LinkedHashSet<>();
        Set<LocalDate> dates = new LinkedHashSet<>();

        for (TimelineEntry e : timeline.entries()) {
            LocalDate d = e.at().atZone(KST).toLocalDate();
            dates.add(d);
            if (e.amount() != null) {
                amounts.add(Math.abs(e.amount()));
            }
            facts.add("%s · %s · %s".formatted(d, e.source(), e.summary()));
        }
        // 사실 문장 안의 금액도 넣는다. 실측에서 모델이 "외부 9,730원"이라고 썼다가 반려됐는데,
        // 그 값은 타임라인 요약 문장("내부 10,000 / 외부 9,730")에 있는 <코드가 만든> 숫자였다.
        // 템플릿은 amounts() 만 찍으므로 이걸 못 잡는다 — 사실을 읽고 인용하는
        // 실제 모델을 붙여야만 드러난다.
        for (String fact : facts) {
            Matcher fm = FACT_AMOUNT.matcher(fact);
            while (fm.find()) {
                try {
                    amounts.add(Long.parseLong(fm.group(1).replace(",", "")));
                } catch (NumberFormatException ignored) {
                    // 자릿수가 넘치는 값. 허용 목록만 못 넓힌다.
                }
            }
            Matcher fd = FACT_DATE.matcher(fact);
            while (fd.find()) {
                try {
                    dates.add(LocalDate.parse(fd.group(1)));
                } catch (java.time.DateTimeException ignored) {
                    // 날짜꼴이지만 실재하지 않는 값. 넣지 않는다.
                }
            }
        }
        amounts.addAll(amountsIn(causeHint));

        return new FactPack(timeline.orderNo(), List.copyOf(facts),
                Set.copyOf(amounts), Set.copyOf(dates), causeHint, timeline.complete());
    }

    /** 분류기 근거에 등장한 금액들. 코드가 산출한 값이므로 사실로 취급한다. */
    private static Set<Long> amountsIn(String causeHint) {
        if (causeHint == null || causeHint.isBlank()) {
            return Set.of();
        }
        Set<Long> found = new LinkedHashSet<>();
        Matcher m = HINT_AMOUNT.matcher(causeHint);
        while (m.find()) {
            try {
                found.add(Long.parseLong(m.group(1).replace(",", "")));
            } catch (NumberFormatException ignored) {
                // 근거 문장이 금액처럼 안 생긴 값을 담았을 뿐이다. 허용 목록만 못 넓힌다.
            }
        }
        return found;
    }

    /** 사실이 하나도 없으면 초안을 만들 게 없다. 억지로 만들면 그게 곧 지어내기다. */
    public boolean empty() {
        return facts.isEmpty();
    }
}
