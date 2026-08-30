package com.beomsu.pay.assist;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 로컬 모델 어댑터 — <b>데이터가 이 기계 밖으로 나가지 않는다</b> (ADR-014).
 *
 * <p><b>왜 로컬이 먼저인가</b>: 2026-04-20 시행세칙 개정으로 금융회사 내부망의 SaaS 이용은
 * 열렸지만 <b>생성형 AI 는 아직 망분리 예외가 아니다</b>(당국 "향후 추진" 단계).
 * 결제 금액과 취소 이력을 외부 API 로 보내는 것은 지금 열려 있지 않다.
 * 로컬은 그 제약을 받지 않는다.
 *
 * <p><b>품질이 낮아도 실험에는 문제가 안 된다.</b> 지금 알고 싶은 것은
 * "모델이 규칙보다 나은가"가 아니라 <b>"모델을 붙일 자리가 있는가"</b>다.
 * 규칙이 못 가르는 자리(OTHER, 위변조 의심)에서 쓸 만한 서술이 나오는지를 보는 것이고,
 * 그건 작은 모델로도 방향은 보인다. 좋은 모델로 갈지는 그 다음 결정이다.
 *
 * <p><b>실패하면 빈 값을 준다.</b> 모델이 죽어도 확정 업무는 흘러야 하고,
 * {@link DraftService} 가 템플릿으로 폴백할 수 있어야 한다.
 *
 * <pre>
 * ollama serve &amp;&amp; ollama pull qwen2.5:7b
 * APP_ASSIST_DRAFT_PROVIDER=ollama ./gradlew bootRun
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.assist.draft-provider", havingValue = "ollama")
public class OllamaDraftAdapter implements DraftPort {

    private final RestClient client;
    private final PromptBuilder prompts;
    private final String model;

    OllamaDraftAdapter(PromptBuilder prompts,
                       @Value("${app.assist.ollama.base-url:http://localhost:11434}") String baseUrl,
                       @Value("${app.assist.ollama.model:qwen2.5:7b}") String model,
                       @Value("${app.assist.ollama.timeout-seconds:60}") long timeoutSeconds) {
        this.prompts = prompts;
        this.model = model;
        // 타임아웃을 명시한다. 로컬 모델은 첫 호출에서 가중치를 올리느라 오래 걸리는데,
        // 기본 무한 대기면 그 사이 확정 응답이 매달린다.
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<String> draft(FactPack facts) {
        if (facts.empty()) {
            return Optional.empty();
        }
        try {
            Map<?, ?> res = client.post().uri("/api/chat")
                    .body(Map.of(
                            "model", model,
                            "stream", false,
                            // 서술만 시키므로 낮게 둔다. 높으면 표현이 다양해지는 게 아니라
                            // 사실에서 벗어날 여지가 늘어난다.
                            "options", Map.of("temperature", 0.2),
                            "messages", java.util.List.of(
                                    Map.of("role", "system", "content", prompts.system()),
                                    Map.of("role", "user", "content", prompts.user(facts)))))
                    .retrieve().body(Map.class);

            String text = Optional.ofNullable(res)
                    .map(r -> r.get("message"))
                    .filter(Map.class::isInstance).map(Map.class::cast)
                    .map(m -> m.get("content"))
                    .map(Object::toString).map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .orElse(null);

            return Optional.ofNullable(text);
        } catch (RuntimeException e) {
            // 죽어도 업무는 흘러야 한다. 예외를 올리면 섀도 기록이 확정을 오염시킨다.
            log.warn("[ollama] 초안 생성 실패 model={} order={}", model, facts.orderNo(), e);
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return "ollama:" + model;
    }
}
