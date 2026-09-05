package com.beomsu.pay.assist.web;

import com.beomsu.pay.assist.narrative.NarrativeComparisonService;
import com.beomsu.pay.assist.narrative.TimelineNarrativeService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자용 서술 창구. <b>읽기 전용</b>이다.
 *
 * <p>어드민 화면이 20개인데 "이 주문에 무슨 일이 있었나"를 한 번에 묻는 곳이 없었다.
 * 조회는 상황 13에서 만들었고, 여기서는 그 결과를 <b>문장</b>으로 돌려준다.
 *
 * <p>못 만들면 <b>204</b>다. 빈 문자열이나 "생성 실패" 같은 문장을 돌려주면 화면이 그걸
 * 서술로 띄운다. 없으면 없다고 해야 화면이 원래 목록만 보여준다.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
class TimelineNarrativeAdminController {

    private final TimelineNarrativeService service;
    private final NarrativeComparisonService comparison;

    TimelineNarrativeAdminController(TimelineNarrativeService service,
                                     NarrativeComparisonService comparison) {
        this.service = service;
        this.comparison = comparison;
    }

    @GetMapping("/{orderNo}/narrative")
    ResponseEntity<TimelineNarrativeService.Narrative> narrative(@PathVariable String orderNo) {
        return service.narrate(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 같은 사실에 대한 두 서술을 <b>출처를 가린 채</b> 내놓는다. 어느 쪽이 모델인지 안 알려준다.
     * 비교할 구현이 둘 미만이거나 어느 한쪽이 못 만들면 204.
     */
    @PostMapping("/{orderNo}/narrative/compare")
    ResponseEntity<NarrativeComparisonService.Comparison> compare(@PathVariable String orderNo) {
        return comparison.open(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 고른다. 고르고 나서야 어느 쪽이 무엇이었는지 공개된다. */
    @PostMapping("/narrative/compare/{id}")
    ResponseEntity<NarrativeComparisonService.Revealed> choose(
            @PathVariable long id,
            @RequestBody ChoiceRequest request,
            @RequestHeader(value = "X-Admin-Id", required = false) String reviewer) {
        return comparison.choose(id, request.choice(), reviewer)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 집계 — 무엇이 몇 번 선택됐나. <b>이 표가 쌓인 다음에</b> 기본값을 정한다. */
    @GetMapping("/narrative/compare/stats")
    NarrativeComparisonService.Stats stats() {
        return comparison.stats();
    }

    record ChoiceRequest(String choice) {
    }
}
