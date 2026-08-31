package com.beomsu.pay.assist;

import com.beomsu.pay.reconciliation.CauseSuggestion;
import com.beomsu.pay.reconciliation.ResolveCause;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResidualCauseService}의 가드 여섯 개를 각각 고정한다.
 *
 * <p><b>가드마다 테스트가 하나씩 있어야 한다.</b> 모델이 붙은 뒤에 검사를 만들면
 * 그 검사가 실제로 무언가를 막는지 확인할 방법이 없다. 상담 초안에서 같은 실수를
 * 한 번 했다. 손으로 만든 입력만 통과시켜 놓고 검증됐다고 적었다.
 */
class ResidualCauseServiceTest {

    private DraftService draftService;
    private ResidualCausePort port;
    private ResidualCauseService service;
    private MeterRegistry registry;

    private static final FactPack FACTS = new FactPack("ORD-1",
            List.of("2026-08-30 · PAYMENT · 결제 승인 100,000원"),
            Set.of(100_000L, 3_000L),
            Set.of(LocalDate.of(2026, 8, 30)),
            null, true);

    @BeforeEach
    void setUp() {
        draftService = mock(DraftService.class);
        port = mock(ResidualCausePort.class);
        registry = new SimpleMeterRegistry();
        when(draftService.factsFor(anyString(), anyLong())).thenReturn(FACTS);
        when(port.name()).thenReturn("stub");

        service = new ResidualCauseService(draftService, new NumericProvenanceGuard(),
                Optional.of(port), registry);
        ReflectionTestUtils.setField(service, "minConfidence", 70);
    }

    private void modelSays(ResolveCause cause, int confidence, String rationale) {
        when(port.suggest(any())).thenReturn(
                Optional.of(new ResidualSuggestion(cause, rationale, confidence, Set.of())));
    }

    private double counted(String outcome) {
        var c = registry.find("assist.residual.outcome").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("가드 1 — 규칙이 후보를 냈으면 모델을 아예 부르지 않는다")
    void skipsWhenRulesDecided() {
        List<CauseSuggestion> rules =
                List.of(CauseSuggestion.decisive(ResolveCause.FEE_CALCULATION_DIFF, "차액이 수수료와 일치", 270L));

        assertThat(service.suggest("ORD-1", 1L, rules)).isEmpty();

        verify(port, never()).suggest(any());
        assertThat(counted("skipped_rules_decided")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 3 — 위변조 의심은 모델이 골라도 버린다")
    void rejectsTampering() {
        modelSays(ResolveCause.SUSPECTED_TAMPERING, 99, "금액이 조작된 것으로 보입니다");

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("forbidden_cause")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 3 — OTHER 도 원인으로 받지 않는다")
    void rejectsOther() {
        modelSays(ResolveCause.OTHER, 95, "분류할 수 없습니다");

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("forbidden_cause")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 4 — 신뢰도가 임계 미만이면 기권으로 처리한다")
    void abstainsBelowThreshold() {
        modelSays(ResolveCause.PG_FILE_DELAY, 69, "파일이 늦게 온 것 같습니다");

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("below_threshold")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 5 — 근거에 출처 없는 금액이 있으면 제안을 통째로 버린다")
    void rejectsUnsourcedFigures() {
        modelSays(ResolveCause.PARTIAL_CANCEL_NOT_REFLECTED, 90, "차액 8,888원이 부분취소로 보입니다");

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("unsourced_figures")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 5 — 사실에 있는 금액만 쓰면 통과한다")
    void acceptsSourcedFigures() {
        modelSays(ResolveCause.PARTIAL_CANCEL_NOT_REFLECTED, 90, "차액 3,000원이 부분취소로 보입니다");

        Optional<ResidualSuggestion> out = service.suggest("ORD-1", 1L, List.of());

        assertThat(out).isPresent();
        assertThat(out.get().cause()).isEqualTo(ResolveCause.PARTIAL_CANCEL_NOT_REFLECTED);
        assertThat(counted("suggested")).isEqualTo(1);
    }

    @Test
    @DisplayName("모델이 스스로 기권하면 그대로 기권으로 집계한다")
    void recordsModelAbstention() {
        when(port.suggest(any())).thenReturn(Optional.empty());

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("abstained")).isEqualTo(1);
    }

    @Test
    @DisplayName("모델이 터져도 확정 경로를 막지 않는다")
    void survivesModelFailure() {
        when(port.suggest(any())).thenThrow(new IllegalStateException("모델 죽음"));

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("error")).isEqualTo(1);
    }

    @Test
    @DisplayName("포트가 없으면 사실 조회조차 하지 않는다")
    void noPortMeansNoWork() {
        var noPort = new ResidualCauseService(draftService, new NumericProvenanceGuard(),
                Optional.empty(), registry);

        assertThat(noPort.suggest("ORD-1", 1L, List.of())).isEmpty();
        verify(draftService, never()).factsFor(anyString(), anyLong());
    }

    @Test
    @DisplayName("템플릿 구현은 항상 기권한다 — 이것이 기본값이 곧 꺼짐인 이유다")
    void templateAlwaysAbstains() {
        var withTemplate = new ResidualCauseService(draftService, new NumericProvenanceGuard(),
                Optional.of(new TemplateResidualAdapter()), registry);
        ReflectionTestUtils.setField(withTemplate, "minConfidence", 70);

        assertThat(withTemplate.suggest("ORD-1", 1L, List.of())).isEmpty();
    }
}
