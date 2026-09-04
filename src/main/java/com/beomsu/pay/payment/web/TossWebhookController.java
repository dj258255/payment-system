package com.beomsu.pay.payment.web;

import com.beomsu.pay.payment.webhook.TossWebhookNormalizer;
import com.beomsu.pay.payment.webhook.WebhookException;
import com.beomsu.pay.payment.webhook.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 토스페이먼츠 웹훅 수신 컨트롤러.
 *
 * <p><b>왜 {@link WebhookController} 와 따로 두나</b>: 기존 엔드포인트는 {@code X-Signature}
 * 헤더를 <b>필수</b>로 받는다. 그건 자체 Mock PG의 규약이고, 토스는 그 헤더를 보내지 않는다.
 * 같은 경로에서 헤더를 선택으로 바꾸면 <b>Mock PG 쪽 검증까지 우회 가능</b>해진다 — 헤더를 빼고
 * 보내면 검증을 건너뛰게 된다. 그래서 경로를 나눠, 서명을 요구하는 문과 요구하지 않는 문을
 * 섞지 않았다.
 *
 * <p><b>항상 200</b>을 준다. 토스는 2xx를 못 받으면 재전송하므로, 우리 쪽 해석 실패를 5xx로
 * 돌려주면 같은 이벤트가 계속 다시 온다. 저장과 재처리 경로가 이미 있으므로 응답은 수신 사실만 알린다.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class TossWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TossWebhookController.class);

    private final WebhookService webhookService;
    private final TossWebhookNormalizer normalizer;

    public TossWebhookController(WebhookService webhookService, TossWebhookNormalizer normalizer) {
        this.webhookService = webhookService;
        this.normalizer = normalizer;
    }

    @PostMapping("/toss")
    public ResponseEntity<Map<String, Boolean>> receive(@RequestBody String rawBody) {
        try {
            webhookService.handleVerified(normalizer.normalize(rawBody));
        } catch (WebhookException e) {
            log.warn("토스 웹훅 처리 예외(200 반환): code={} {}", e.code(), e.getMessage());
        } catch (Exception e) {
            log.warn("토스 웹훅 처리 예외(200 반환): {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("received", true));
    }
}
