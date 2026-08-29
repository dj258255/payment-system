package com.beomsu.pay.timeline;

import java.time.Instant;

/**
 * 타임라인의 한 줄. 어느 모듈에서 왔든 같은 모양으로 정규화한다.
 *
 * @param at      일어난 시각. 정렬 기준이다
 * @param source  어느 도메인에서 온 사실인가 (ORDER, PAYMENT, LEDGER ...)
 * @param event   무슨 일이 있었나 (짧은 식별자. 화면·검색에 쓴다)
 * @param summary 사람이 읽을 한 줄. <b>코드가 만든다.</b> 숫자를 여기서 계산하지 않는다
 * @param amount  금액이 있으면 원 단위. 없으면 null (모든 사건에 금액이 있진 않다)
 */
public record TimelineEntry(Instant at, Source source, String event, String summary, Long amount) {

    /** 사실의 출처 도메인. 화면에서 색으로 구분하고, 필터에도 쓴다. */
    public enum Source {
        ORDER, PAYMENT, LEDGER, ESCROW, SETTLEMENT,
        POINT, WALLET, DISPUTE, RECONCILIATION, COMPENSATION, AUDIT
    }

    public static TimelineEntry of(Instant at, Source source, String event, String summary) {
        return new TimelineEntry(at, source, event, summary, null);
    }

    public static TimelineEntry of(Instant at, Source source, String event, String summary, long amount) {
        return new TimelineEntry(at, source, event, summary, amount);
    }
}
