package com.beomsu.pay.assist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프롬프트가 <b>고를 수 없는 값을 보여주지 않는지</b> 고정한다.
 *
 * <p>코드에서 막는 것만으로는 부족하다. 목록에 보이면 모델이 고르고, 고른 응답은
 * 통째로 버려진다. 버려지는 호출이 늘면 커버리지가 조용히 떨어진다.
 */
class ResidualPromptBuilderTest {

    private final ResidualPromptBuilder prompts = new ResidualPromptBuilder();

    @Test
    @DisplayName("금지된 원인은 프롬프트 목록에 없다")
    void hidesForbiddenCauses() {
        String allowed = ResidualPromptBuilder.allowedCauses();

        assertThat(allowed).doesNotContain("SUSPECTED_TAMPERING", "OTHER");
        assertThat(allowed).contains("PG_FILE_DELAY", "TIMEZONE_BOUNDARY", "DUPLICATE_RECORD");
    }

    @Test
    @DisplayName("허용된 원인은 전부 뜻이 붙어 있다")
    void everyAllowedCauseHasMeaning() {
        // 정의 없이 이름만 주면 모델이 못 가른다. 실측으로 확인했다(14 문서).
        // 새 원인을 추가하고 뜻을 안 적으면 여기서 걸린다.
        assertThat(ResidualPromptBuilder.causeMenu())
                .doesNotContain("(정의 없음");

        for (String name : ResidualPromptBuilder.allowedCauses().split(", ")) {
            assertThat(ResidualPromptBuilder.causeMenu()).contains(name + ": ");
        }
    }

    @Test
    @DisplayName("경계와 지연을 가르는 방향을 알려준다")
    void explainsDirection() {
        // 두 모델이 같은 자리에서 틀렸다. 둘 다 "다른 날짜 파일에 있다"라서
        // 방향을 안 알려주면 구별이 안 된다.
        String system = new ResidualPromptBuilder().system();
        assertThat(system).contains("전날", "다음날");
    }

    @Test
    @DisplayName("기권하는 법을 알려준다")
    void teachesAbstention() {
        assertThat(prompts.system()).contains("ABSTAIN");
    }

    @Test
    @DisplayName("사실이 불완전하면 그 사실을 프롬프트에 적는다")
    void marksIncompleteFacts() {
        FactPack incomplete = new FactPack("ORD-1", List.of("결제 승인"),
                Set.of(1L), Set.of(LocalDate.of(2026, 8, 30)), null, false);

        assertThat(prompts.user(incomplete)).contains("불완전");
    }
}
