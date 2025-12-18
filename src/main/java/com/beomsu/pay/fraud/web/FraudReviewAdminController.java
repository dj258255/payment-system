package com.beomsu.pay.fraud.web;

import com.beomsu.pay.fraud.FraudReviewAdminService;
import com.beomsu.pay.fraud.FraudReviewStatus;
import com.beomsu.pay.fraud.FraudReviewView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * FDS 심사 큐 백오피스 어드민 REST 컨트롤러.
 *
 * <p>인가는 {@code SecurityConfig}에서 {@code /api/v1/admin/**}에 ROLE_ADMIN을 요구해 강제한다.
 * 상태를 바꾸는 승인/거부는 호출자(principal)를 감사 로그로 남긴다(운영에선 maker-checker·감사
 * 테이블로 강화). 거부는 카드 블랙리스트 등록을 유발하므로 특히 추적이 중요하다.
 */
@RestController
@RequestMapping("/api/v1/admin/fraud-reviews")
@RequiredArgsConstructor
class FraudReviewAdminController {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final FraudReviewAdminService adminService;

    /** 상태별 심사 항목 목록(기본 PENDING = 미결 건). */
    @GetMapping
    List<FraudReviewView> list(@RequestParam(defaultValue = "PENDING") FraudReviewStatus status) {
        return adminService.list(status);
    }

    /** 승인(정상 거래로 확인). */
    @PostMapping("/{id}/approve")
    FraudReviewView approve(@PathVariable Long id, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("FDS 심사 승인 요청 by={} reviewId={}", who, id);
        FraudReviewView view = adminService.approve(id, who);
        audit.info("FDS 심사 승인 결과 by={} reviewId={} status={}", who, id, view.status());
        return view;
    }

    /** 거부(부정 거래로 확인 → 카드 블랙리스트 등록). */
    @PostMapping("/{id}/reject")
    FraudReviewView reject(@PathVariable Long id, Principal caller) {
        String who = caller != null ? caller.getName() : "unknown";
        audit.info("FDS 심사 거부 요청 by={} reviewId={}", who, id);
        FraudReviewView view = adminService.reject(id, who);
        audit.info("FDS 심사 거부 결과 by={} reviewId={} status={}", who, id, view.status());
        return view;
    }
}
