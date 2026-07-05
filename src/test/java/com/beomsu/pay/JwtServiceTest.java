package com.beomsu.pay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 발급/검증 단위 테스트 — JwtConfig의 인코더/디코더를 직접 생성해 왕복을 확인한다.
 * (Spring 컨텍스트 로딩 없이 순수 단위.)
 */
class JwtServiceTest {

    // 32바이트 이상 테스트 시크릿(HS256 최소 길이 충족)
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final JwtConfig config = new JwtConfig(SECRET);
    private final JwtService service = new JwtService(config.jwtEncoder(), 60);
    private final JwtDecoder decoder = config.jwtDecoder();

    @Test
    @DisplayName("발급한 토큰을 디코드하면 subject=userId, roles 클레임, 미래 만료가 담겨 있다")
    void issuedTokenDecodesWithClaims() {
        String token = service.issue("1", List.of("ROLE_USER"));

        Jwt jwt = decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo("1");                       // principal name = userId
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_USER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("pay");
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("시크릿이 32바이트 미만이면 기동 실패(fail-fast)")
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtConfig("too-short-secret"))   // < 32바이트
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("빈 시크릿도 기동 실패(fail-fast)")
    void blankSecretFailsFast() {
        assertThatThrownBy(() -> new JwtConfig("   "))
                .isInstanceOf(IllegalStateException.class);
    }
}
