package com.beomsu.pay.assist.incident;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장애 로그로 원인을 고르는 일에서 <b>규칙 대비 모델이 나은가</b>를 잰다.
 *
 * <p><b>로그가 진짜다.</b> 지어낸 문장이 아니라 이 프로젝트에서 <b>실제로 찍힌</b> 것이다.
 * <ul>
 *   <li>{@code DB_TIMEOUT} — Toxiproxy 로 MySQL 을 끊고 돌린 카오스 실행에서 나온 것</li>
 *   <li>{@code TIME_SKEW} — 시계가 1시간 틀어진 웹훅을 실제로 넣어 401 을 받은 것</li>
 *   <li>{@code QUEUE_BACKLOG} — 보류 웹훅이 재시도를 소진하며 쌓인 것</li>
 * </ul>
 *
 * <p><b>정답은 우리가 안다.</b> 무엇을 주입했는지 알고 넣었으므로 채점이 프록시가 아니다.
 * 서술(18 문서)에서 "읽기 좋은가"를 못 재 사람 손을 빌려야 했던 것과 다른 점이다.
 *
 * <p><b>기준선은 규칙이다.</b> 절대 정확도가 아니라 <b>규칙 대비</b>로 본다 — 잔여 후보에서
 * 그걸 안 보고 켰다가 껐다.
 */
@Tag("eval")
@DisplayName("장애 로그 원인 분석 — 규칙 대비")
class IncidentAnalysisEvalTest {

    private static final Path DIR = Path.of("src/test/resources/incident-logs");

    /** 파일 이름이 곧 정답이다. 실제로 그 장애를 넣고 받은 로그이기 때문이다. */
    private static Map<IncidentCause, String> realLogs() {
        Map<IncidentCause, String> out = new LinkedHashMap<>();
        try (var files = Files.list(DIR)) {
            files.sorted().forEach(p -> {
                String name = p.getFileName().toString().replace(".log", "");
                try {
                    out.put(IncidentCause.valueOf(name), Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private record Score(String name, int correct, int abstained, int wrong) {
        String line() {
            return "  %-14s 맞음 %d · 기권 %d · 틀림 %d".formatted(name, correct, abstained, wrong);
        }
    }

    private Score run(IncidentAnalysisPort port, Map<IncidentCause, String> cases) {
        int correct = 0, abstained = 0, wrong = 0;
        List<String> detail = new ArrayList<>();
        for (var e : cases.entrySet()) {
            Optional<IncidentDiagnosis> out = port.diagnose(e.getValue());
            String got;
            if (out.isEmpty() || out.get().cause() == IncidentCause.UNKNOWN) {
                abstained++;
                got = "기권";
            } else if (out.get().cause() == e.getKey()) {
                correct++;
                got = "맞음";
            } else {
                wrong++;
                got = "틀림(" + out.get().cause() + ")";
            }
            detail.add("      %-16s → %s".formatted(e.getKey(), got));
        }
        detail.forEach(System.out::println);
        return new Score(port.name(), correct, abstained, wrong);
    }

    @Test
    @DisplayName("실제로 찍힌 로그에 규칙과 모델을 나란히 태운다")
    void compareAgainstRuleBaseline() {
        Map<IncidentCause, String> cases = realLogs();
        assertThat(cases).as("실제 로그 표본이 있어야 한다").isNotEmpty();

        System.out.printf("%n╔══ 장애 로그 원인 분석 (실제 로그 %d건) ══%n", cases.size());
        System.out.println("  [규칙 기준선]");
        Score rule = run(new RuleBasedIncidentAnalyzer(), cases);
        System.out.println("  [모델]");
        Score model = run(new OllamaIncidentAnalyzer(
                "http://localhost:11434", "qwen3:8b", 120), cases);

        System.out.println();
        System.out.println(rule.line());
        System.out.println(model.line());
        System.out.println("╚═══════════════════════════════════════");

        // 수치를 통과 조건으로 걸지 않는다 — 재는 테스트다.
        assertThat(rule.name()).isEqualTo("rule");
    }
}
