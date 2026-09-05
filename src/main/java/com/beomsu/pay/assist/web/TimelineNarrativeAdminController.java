package com.beomsu.pay.assist.web;

import com.beomsu.pay.assist.narrative.TimelineNarrativeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    TimelineNarrativeAdminController(TimelineNarrativeService service) {
        this.service = service;
    }

    @GetMapping("/{orderNo}/narrative")
    ResponseEntity<TimelineNarrativeService.Narrative> narrative(@PathVariable String orderNo) {
        return service.narrate(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
