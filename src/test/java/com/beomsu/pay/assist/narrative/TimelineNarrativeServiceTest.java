package com.beomsu.pay.assist.narrative;

import com.beomsu.pay.assist.draft.DraftService;
import com.beomsu.pay.assist.draft.FactPack;
import com.beomsu.pay.assist.draft.AmountCoverageGuard;
import com.beomsu.pay.assist.draft.NumericProvenanceGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 서술은 <b>사내 화면에만</b> 뜨고 상태를 바꾸지 않는다. 그래서 가드가 하나뿐이다 —
 * 없는 숫자를 만드는 것. 그 하나를 여기서 고정한다.
 */
@DisplayName("운영자용 타임라인 서술")
class TimelineNarrativeServiceTest {

    private static final FactPack FACTS = new FactPack("ORD-1",
            List.of("2026-08-30 · PAYMENT · 결제 승인 100,000원",
                    "2026-08-31 · RECONCILIATION · 대사 AMOUNT_MISMATCH (거래일 2026-08-31) — 내부 100,000 / 외부 97,000"),
            Set.of(100_000L, 97_000L),
            Set.of(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 31)),
            null, true);

    private DraftService draftService;
    private TimelineNarrativePort port;
    private MeterRegistry registry;
    private TimelineNarrativeService service;

    @BeforeEach
    void setUp() {
        draftService = mock(DraftService.class);
        port = mock(TimelineNarrativePort.class);
        registry = new SimpleMeterRegistry();
        when(port.name()).thenReturn("test-port");
        when(draftService.factsFor(anyString(), any())).thenReturn(FACTS);
        service = new TimelineNarrativeService(
                draftService, port, new NumericProvenanceGuard(), new AmountCoverageGuard(),
                registry, mock(NarrativeAuditRepository.class));
    }

    private double counted(String outcome) {
        var c = registry.find("assist.narrative").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("사실에 있는 숫자만 쓰면 통과하고, 무엇이 썼는지 함께 낸다")
    void passesWhenEveryFigureIsSourced() {
        when(port.narrate(FACTS)).thenReturn(Optional.of(
                "2026-08-30 결제 100,000원이 승인됐고, 다음 날 대사에서 외부 97,000원과 어긋났다."));

        Optional<TimelineNarrativeService.Narrative> out = service.narrate("ORD-1");

        assertThat(out).isPresent();
        assertThat(out.get().source()).isEqualTo("test-port");
        assertThat(out.get().complete()).isTrue();
        assertThat(counted("narrated")).isEqualTo(1);
    }

    @Test
    @DisplayName("출처 없는 숫자가 하나라도 있으면 문단을 통째로 버린다")
    void dropsWhenAnyFigureIsUnsourced() {
        // 3,000원은 사실 목록에 없다. 차액을 모델이 <계산>한 값이다.
        when(port.narrate(FACTS)).thenReturn(Optional.of(
                "결제 100,000원 중 3,000원이 비어 대사가 어긋났다."));

        assertThat(service.narrate("ORD-1")).isEmpty();
        assertThat(counted("unsourced_figures")).isEqualTo(1);
    }

    @Test
    @DisplayName("모델이 기권하면 그대로 비운다 — 억지로 만든 문장은 일을 늘린다")
    void respectsAbstention() {
        when(port.narrate(FACTS)).thenReturn(Optional.empty());

        assertThat(service.narrate("ORD-1")).isEmpty();
        assertThat(counted("abstained")).isEqualTo(1);
    }

    @Test
    @DisplayName("사실이 없으면 모델을 부르지 않는다")
    void skipsWhenNoFacts() {
        when(draftService.factsFor(anyString(), any()))
                .thenReturn(new FactPack("ORD-2", List.of(), Set.of(), Set.of(), null, true));

        assertThat(service.narrate("ORD-2")).isEmpty();
        assertThat(counted("no_facts")).isEqualTo(1);
        org.mockito.Mockito.verify(port, org.mockito.Mockito.never()).narrate(any());
    }
}
