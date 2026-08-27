package com.beomsu.pay.reconciliation;

import com.beomsu.pay.reconciliation.PgSettlementCsvParser.ParseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.beomsu.pay.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

/**
 * 정산 대사 운영 어드민 — PG 정산 파일로 대사를 실행하고, 사람 확인이 필요한 불일치(예외 큐)를 조회·확정한다.
 *
 * <p>대사 엔진이 불일치 3분류(내부만/외부만/금액불일치)를 {@link ReconStatus#PENDING}으로 남기지만
 * (1) 외부 기록을 실제로 넣을 경로가 없었고 (2) 남은 예외 큐를 조회할 방법도 없었다. 이 어드민이
 * <b>업로드 → 대사 → 예외 큐 → 수기 확정</b> 루프를 닫는다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationAdminService {

    private final ReconciliationResultRepository repository;
    private final AuditService auditService;
    private final PgSettlementCsvParser parser;
    private final ReconciliationService reconciliationService;

    /**
     * PG 정산 파일(CSV)을 파싱해 대사 매칭 엔진을 돌리고 결과를 분류별로 집계한다.
     *
     * <p>파싱은 헤더 기반이라 컬럼 순서·부가 컬럼에 강하고, 불량/요약 행은 건너뛴다(스킵 수 집계).
     * <p>거래일을 받는 이유는 대사가 날짜 단위 작업이기 때문이다. PG 정산 파일은 하루치로 끊겨 오고,
     * 그 하루의 내부 기록과만 대조해야 한다. 전체와 비교하면 지난 날짜가 전부 불일치로 잡힌다.
     *
     * <p>매칭 엔진({@link ReconciliationService#reconcile})이 결과를 이미 영속하므로,
     * 여기서는 반환된 결과를 타입별로 세어 요약만 만든다. 불일치는 PENDING 예외 큐로 남아 수기 확정을 기다린다.
     */
    @Transactional
    public ReconRunSummary run(LocalDate tradeDate, InputStream file) {
        ParseResult parsed = parser.parse(file);
        List<ReconciliationResult> results =
                reconciliationService.reconcile(tradeDate, parsed.records());

        int matched = 0, internalOnly = 0, externalOnly = 0, amountMismatch = 0;
        for (ReconciliationResult r : results) {
            switch (r.getResult()) {
                case MATCHED -> matched++;
                case INTERNAL_ONLY -> internalOnly++;
                case EXTERNAL_ONLY -> externalOnly++;
                case AMOUNT_MISMATCH -> amountMismatch++;
            }
        }
        int pending = internalOnly + externalOnly + amountMismatch;
        return new ReconRunSummary(parsed.records().size(), parsed.skipped(),
                matched, internalOnly, externalOnly, amountMismatch, pending);
    }

    /** 사람 확인이 필요한 불일치(PENDING) 페이지. */
    @Transactional(readOnly = true)
    public Page<ReconMismatchView> listMismatches(Pageable pageable) {
        return repository.findByStatus(ReconStatus.PENDING, pageable)
                .map(ReconciliationAdminService::toView);
    }

    /**
     * PENDING 대사 불일치 1건을 <b>사유와 함께</b> 수기 확정한다(사람 확인 후, ADR-008).
     *
     * <p>확정과 감사 기록을 <b>같은 트랜잭션</b>에 둔다. 나누면 확정은 됐는데 기록만 빠지는
     * 상태가 생기고, 그건 "누가 왜 확정했는지 모르는 종결"이라 감사 관점에서 최악이다.
     *
     * <p>상태 전이는 {@link ReconciliationResultRepository#saveAndFlush(Object)}로 <b>명시 영속</b>한다.
     * dirty-check 자동 flush는 readOnly 조회로 세션 FlushMode가 MANUAL이 되거나 엔티티가 detached인
     * 경우 신뢰할 수 없어(pay-26 사건 교훈), 상태 확정을 명시적으로 강제한다.
     */
    @Transactional
    public ReconMismatchView resolve(long id, String actor, ResolveCause cause, String note) {
        ReconciliationResult result = repository.findById(id)
                .orElseThrow(() -> new ReconciliationException("RECON_RESULT_NOT_FOUND",
                        "대사 결과를 찾을 수 없습니다: " + id));
        result.resolveManually(actor, cause, note);
        repository.saveAndFlush(result);

        // 감사 기록 — 같은 트랜잭션에 남긴다. 확정은 됐는데 기록만 빠지는 상태를 만들지 않는다.
        auditService.record(actor, "RECON_RESOLVE", "RECONCILIATION_RESULT", String.valueOf(id),
                "cause=" + cause + (note == null || note.isBlank() ? "" : " note=" + note));
        return toView(result);
    }

    private static ReconMismatchView toView(ReconciliationResult r) {
        return new ReconMismatchView(r.getId(), r.getOrderNo(), r.getResult(),
                r.getInternalAmount(), r.getExternalAmount(), r.getReconciledAt(),
                r.getResolvedBy(), r.getResolveCause(), r.getResolveNote(), r.getResolvedAt());
    }
}
