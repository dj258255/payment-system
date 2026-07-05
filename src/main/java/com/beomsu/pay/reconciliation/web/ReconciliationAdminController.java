package com.beomsu.pay.reconciliation.web;

import com.beomsu.pay.reconciliation.ReconMismatchView;
import com.beomsu.pay.reconciliation.ReconciliationAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 정산 대사 운영 어드민 REST 컨트롤러(관측 전용).
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 조회만 하므로 감사 로그·상태변경 엔드포인트는 두지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliations")
@RequiredArgsConstructor
class ReconciliationAdminController {

    private final ReconciliationAdminService adminService;

    @GetMapping("/mismatches")
    List<ReconMismatchView> mismatches() {
        return adminService.listMismatches();
    }
}
