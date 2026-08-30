package com.beomsu.pay.assist;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 외부 모델 어댑터 — <b>BYOK(Bring Your Own Key)</b> (ADR-014).
 *
 * <p><b>키는 운영자가 넣는다.</b> 만든 사람의 키를 심어 두지 않는다. 이유가 두 가지다.
 * 하나는 비용 — 쓰는 사람이 늘어도 만든 사람에게 요금이 튀지 않는다.
 * 다른 하나는 데이터 — "당신 키, 당신 계정, 당신 데이터"가 결제 도메인에서는
 * 편의가 아니라 <b>요건</b>이다. GitHub Copilot CLI·VS Code 가 같은 방식을 쓴다.
 *
 * <p><b>구독으로는 안 된다.</b> Claude Pro·Max 구독은 Claude 앱과 Claude Code 를 커버하지만
 * <b>API 접근을 포함하지 않고 별도 과금</b>이다(Anthropic 안내). 그래서 여기 필요한 것은
 * 구독 계정이 아니라 콘솔에서 발급한 API 키다.
 *
 * <p><b>기본은 꺼져 있다.</b> 생성형 AI 는 아직 금융권 망분리 예외가 아니므로
 * (2026-04-20 시행세칙은 SaaS 까지), 이 어댑터를 켜는 것은 그 제약을 아는 사람이
 * 명시적으로 하는 선택이어야 한다. 키가 없으면 기동 시점에 막는다 —
 * 호출 시점에 실패하면 그때야 알게 되고, 그 사이 초안이 조용히 비어 나간다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.assist.draft-provider", havingValue = "anthropic")
public class AnthropicDraftAdapter implements DraftPort {

    private static final String VERSION = "2023-06-01";

    private final RestClient client;
    private final PromptBuilder prompts;
    private final String model;
    private final int maxTokens;

    AnthropicDraftAdapter(PromptBuilder prompts,
                          @Value("${app.assist.anthropic.api-key:}") String apiKey,
                          @Value("${app.assist.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
                          @Value("${app.assist.anthropic.model:claude-sonnet-5}") String model,
                          @Value("${app.assist.anthropic.max-tokens:600}") int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("""
                    app.assist.draft-provider=anthropic 인데 API 키가 없습니다.
                      app.assist.anthropic.api-key (또는 APP_ASSIST_ANTHROPIC_API_KEY) 를 설정하십시오.
                      키는 console.anthropic.com 에서 발급합니다. Claude 구독은 API 접근을 포함하지 않습니다.
                      주의: 이 어댑터를 켜면 결제 데이터가 외부로 나갑니다(ADR-014).""");
        }
        this.prompts = prompts;
        this.model = model;
        this.maxTokens = maxTokens;

        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", VERSION)
                .defaultHeader("content-type", "application/json")
                .build();
    }

    @Override
    public Optional<String> draft(FactPack facts) {
        if (facts.empty()) {
            return Optional.empty();
        }
        try {
            Map<?, ?> res = client.post().uri("/v1/messages")
                    .body(Map.of(
                            "model", model,
                            "max_tokens", maxTokens,
                            "temperature", 0.2,
                            "system", prompts.system(),
                            "messages", List.of(
                                    Map.of("role", "user", "content", prompts.user(facts)))))
                    .retrieve().body(Map.class);

            // content 는 블록 배열이다. text 블록만 이어 붙인다.
            Object content = res == null ? null : res.get("content");
            if (!(content instanceof List<?> blocks)) {
                return Optional.empty();
            }
            String text = blocks.stream()
                    .filter(Map.class::isInstance).map(Map.class::cast)
                    .filter(b -> "text".equals(b.get("type")))
                    .map(b -> String.valueOf(b.get("text")))
                    .reduce("", String::concat).trim();

            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (RuntimeException e) {
            log.warn("[anthropic] 초안 생성 실패 model={} order={}", model, facts.orderNo(), e);
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return "anthropic:" + model;
    }
}
