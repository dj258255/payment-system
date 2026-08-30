package com.beomsu.pay.assist.web;

import com.beomsu.pay.assist.CsDraft;
import com.beomsu.pay.assist.DraftService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상담 답변 초안 조회 (ADR-014).
 *
 * <p><b>어드민 전용</b>이고 <b>읽기 전용</b>이다. 초안은 고객에게 직접 나가지 않는다 —
 * 상담원이 받아서 확인·수정한 뒤 보낸다. 이 컨트롤러에 발송을 붙이면
 * 그 전제가 깨진다.
 *
 * <p>GET인 이유: 초안 생성이 아무 상태도 바꾸지 않기 때문이다. POST로 두면
 * "생성해서 저장한다"는 오해를 준다.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
class CsDraftAdminController {

    private final DraftService service;

    CsDraftAdminController(DraftService service) {
        this.service = service;
    }

    /**
     * 주문 한 건의 상담 초안.
     *
     * <p>검증에 실패해도 <b>200에 {@code verified=false}</b>로 준다. 500을 주면 화면이
     * "오류"만 보여주고, 사람은 초안이 왜 없는지 — 구현이 없는 숫자를 만들었기 때문인지 —
     * 알 수 없다. 그 정보가 그 구현을 계속 쓸지 판단하는 근거다.
     *
     * @param reconResultId 대사 불일치에서 넘어온 경우 그 id. 원인 제안이 함께 실린다
     */
    @GetMapping("/{orderNo}/cs-draft")
    CsDraft draft(@PathVariable String orderNo,
                  @RequestParam(required = false) Long reconResultId) {
        return service.draftFor(orderNo, reconResultId);
    }
}
