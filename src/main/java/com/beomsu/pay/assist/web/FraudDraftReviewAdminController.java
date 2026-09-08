package com.beomsu.pay.assist.web;

import com.beomsu.pay.assist.fraudreview.FraudDraftReview;
import com.beomsu.pay.assist.fraudreview.FraudDraftReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 심사 초안 블라인드 비교 창구.
 *
 * <p><b>단계가 셋이고 순서가 강제된다.</b> 자기 메모를 쓰기 전에 초안을 못 보고, 초안을 본
 * 뒤에는 메모를 못 쓴다. 그 순서가 이 실험의 유일한 방법론적 근거다.
 *
 * <p><b>어느 쪽이 모델인지 응답에 안 싣는다.</b> {@code baselineFirst} 만 주고 화면은 그
 * 순서대로 A·B 로 띄운다. 알고 고치면 두 편집률의 차이가 방식 차이가 아니게 된다.
 */
@RestController
@RequestMapping("/api/v1/admin/fraud-reviews/{id}/draft-review")
class FraudDraftReviewAdminController {

    private final FraudDraftReviewService service;

    FraudDraftReviewAdminController(FraudDraftReviewService service) {
        this.service = service;
    }

    /** 자리를 연다. 초안은 아직 안 준다. */
    @PostMapping("/open")
    ResponseEntity<Void> open(@PathVariable long id, @RequestBody ReviewerRequest req) {
        return service.open(id, req.reviewer()).isPresent()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** 1단계. 초안을 보기 전에 쓴다. */
    @PostMapping("/blind")
    ResponseEntity<Void> blind(@PathVariable long id, @RequestBody BlindRequest req) {
        service.blind(id, req.reviewer(), req.reply());
        return ResponseEntity.noContent().build();
    }

    /** 2단계. 초안 둘을 고정해 A·B 로 준다. */
    @PostMapping("/reveal")
    ResponseEntity<RevealedPair> reveal(@PathVariable long id, @RequestBody ReviewerRequest req) {
        return service.reveal(id, req.reviewer())
                .map(RevealedPair::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 3단계. 고친 결과를 A·B 자리로 받는다. */
    @PostMapping("/edit")
    ResponseEntity<Void> edit(@PathVariable long id, @RequestBody EditRequest req) {
        service.edit(id, req.reviewer(), req.editedModel(), req.editedBaseline());
        return ResponseEntity.noContent().build();
    }

    /** 지금까지의 성적. 켤 조건을 넘겼는지가 여기 있다. */
    @GetMapping("/stats")
    ResponseEntity<FraudDraftReviewService.Stats> stats(@PathVariable long id) {
        return ResponseEntity.ok(service.stats());
    }

    record ReviewerRequest(String reviewer) {}
    record BlindRequest(String reviewer, String reply) {}
    record EditRequest(String reviewer, String editedModel, String editedBaseline) {}

    /**
     * 공개된 초안 둘. <b>어느 쪽이 모델인지 안 싣는다.</b>
     *
     * <p>{@code a}·{@code b} 는 화면에 뜰 순서 그대로다. 채점할 때 서버가 그 순서를 알고
     * 있으므로 어느 쪽을 고친 것인지 되짚을 수 있다.
     */
    record RevealedPair(long fraudReviewId, String a, String b) {
        static RevealedPair of(FraudDraftReview r) {
            return r.isBaselineFirst()
                    ? new RevealedPair(r.getFraudReviewId(), r.getBaselineDraft(), r.getModelDraft())
                    : new RevealedPair(r.getFraudReviewId(), r.getModelDraft(), r.getBaselineDraft());
        }
    }
}
