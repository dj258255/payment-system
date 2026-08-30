package com.beomsu.pay.assist;

import com.beomsu.pay.timeline.OrderTimeline;
import com.beomsu.pay.timeline.TimelineEntry;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 타임라인을 사실 묶음으로 옮긴다. <b>계산하지 않는다.</b>
     *
     * <p>여기서 차액을 구하거나 합계를 내지 않는 이유: 그 숫자는 이미 대사 엔진과
     * 분류기가 근거와 함께 냈다. 같은 값을 두 곳에서 따로 구하면 언젠가 갈라지고,
     * 갈라진 순간 어느 쪽이 맞는지 알 방법이 없다.
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
        return new FactPack(timeline.orderNo(), List.copyOf(facts),
                Set.copyOf(amounts), Set.copyOf(dates), causeHint, timeline.complete());
    }

    /** 사실이 하나도 없으면 초안을 만들 게 없다. 억지로 만들면 그게 곧 지어내기다. */
    public boolean empty() {
        return facts.isEmpty();
    }
}
