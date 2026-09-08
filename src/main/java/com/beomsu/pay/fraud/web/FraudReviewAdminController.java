package com.beomsu.pay.fraud.web;

import com.beomsu.pay.SecurityConfig;
import com.beomsu.pay.fraud.review.FraudReviewAdminService;
import com.beomsu.pay.fraud.review.FraudReviewStatus;
import com.beomsu.pay.fraud.review.FraudReviewView;
import com.beomsu.pay.fraud.review.RuleFalsePositive;
import com.beomsu.pay.fraud.review.RuleFalsePositiveService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
    private final RuleFalsePositiveService ruleFalsePositiveService;

    /** 상태별 심사 항목 목록(기본 PENDING = 미결 건). */
    @GetMapping
    Page<FraudReviewView> list(@RequestParam(defaultValue = "PENDING") FraudReviewStatus status,
                               @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminService.list(status, pageable);
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

    /**
     * 규칙별 오탐률. <b>어느 규칙을 조일지</b>를 고르는 화면이 쓴다.
     *
     * <p>전체 오탐률 알림이 울렸을 때 여는 자리다. 알림은 "정상 거래를 잡고 있다"까지만
     * 말하고, 다섯 규칙 중 어느 것인지는 여기서 본다.
     *
     * <p>기준을 넘긴 것만 주지 않는다. 목록이 비어 있는 것과 다 멀쩡한 것은 다르다.
     */
    @GetMapping("/rule-false-positives")
    List<RuleFalsePositive> ruleFalsePositives() {
        return ruleFalsePositiveService.byRule();
    }
}
