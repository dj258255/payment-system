package com.beomsu.pay.seller.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>실제 UN 명단</b>을 받아 우리 파서가 도는지 확인한다.
 *
 * <p>손으로 만든 XML 로만 검증하면 <b>내가 상상한 구조</b>를 검사하는 것이다.
 * 이 저장소는 이미 그 함정을 한 번 밟았다 — 자체 Mock PG 의 규약을 PG 공통으로 여겨
 * 실 PG 웹훅을 한 건도 못 받고 있었다. 그래서 진짜 파일로 한 번 더 본다.
 *
 * <p>네트워크가 필요해 기본 스위트에서 제외한다. {@code ./gradlew integrationTest} 로 실행.
 */
@Tag("integration")
class UnSanctionsListLiveTest {

    private static final String URL = "https://scsanctions.un.org/resources/xml/en/consolidated.xml";

    @Test
    @DisplayName("실제 UN 통합 명단을 받아 파싱한다")
    void parsesRealList() throws Exception {
        var req = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<InputStream> res = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);

        var snap = UnConsolidatedSanctionsList.parse(res.body());
        long withDob = snap.entries().stream().filter(e -> e.birthDate() != null).count();
        long withCountry = snap.entries().stream().filter(e -> e.country() != null).count();
        // 명단은 나라 <이름>을 준다. 우리 판매자는 두 글자 코드로 들고 있어 맞춰야 쓸 수 있다.
        long codeOk = snap.entries().stream()
                .filter(e -> e.identifiers().nationality() != null).count();
        long unknown = withCountry - codeOk;

        System.out.printf("%n=== 실제 UN 통합 명단 ===%n");
        System.out.printf("  판(version)        %s%n", snap.version());
        System.out.printf("  항목               %,d건%n", snap.entries().size());
        System.out.printf("  생년월일 있는 항목    %,d건 (%.1f%%)%n", withDob, 100.0 * withDob / snap.entries().size());
        System.out.printf("  국적 있는 항목       %,d건 (%.1f%%)%n", withCountry, 100.0 * withCountry / snap.entries().size());
        System.out.printf("  두 글자 코드로 인식   %,d건 · 못 알아본 나라 %,d건%n", codeOk, unknown);
        if (unknown > 0) {
            snap.entries().stream()
                    .filter(e -> e.country() != null && e.identifiers().nationality() == null)
                    .map(SanctionsList.Entry::country).distinct().limit(8)
                    .forEach(c -> System.out.printf("    못 알아봄: %s%n", c));
        }
        snap.entries().stream().filter(e -> e.birthDate() != null).limit(3)
                .forEach(e -> System.out.printf("    예) %-38s %s · %s%n", e.name(), e.birthDate(), e.country()));

        assertThat(snap.entries()).as("실제 명단이 비어 있으면 파서가 틀린 것이다").hasSizeGreaterThan(500);
        assertThat(snap.version()).startsWith("UN-2");
        assertThat(codeOk)
                .as("국적을 코드로 못 맞추면 항상 불일치가 되어 맞는 사람의 점수를 깎는다")
                .isGreaterThan(withCountry / 2);
        assertThat(withDob)
                .as("""
                    2차 식별자를 쓸 수 있어야 이 작업이 값을 한다.
                    실제 명단은 연월일을 다 주지 않는다 — EXACT 751건 중 연월일이 온전한 것은 0건이고
                    263건이 연도만 준다. 그래서 전체 날짜를 맞대려던 처음 설계는 한 번도 발동하지
                    않았다(실측 0건). 겹치는 자리까지만 비교하도록 고쳐서 이 수가 살아났다.""")
                .isGreaterThan(100);
    }
}
