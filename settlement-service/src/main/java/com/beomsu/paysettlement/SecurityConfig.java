package com.beomsu.paysettlement;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 정산 서비스 보안 — pay-core가 발급한 JWT(HS256)를 <b>같은 시크릿으로 로컬 검증</b>한다.
 *
 * <p>별도 인증서버를 두지 않은 이유: 발급 주체는 pay-core 하나이고 JWT가 무상태라, 각 서비스는
 * 서명·만료 검증만으로 인가를 끝낼 수 있다(인증 서비스 호출·세션 저장소 없음). 클라이언트가 늘어
 * 로그인이 공유되는 시점에 발급을 IdP로 분리하면 되고, 검증이 무상태라 그 전환 비용도 낮다.
 *
 * <p>정직한 트레이드오프: 코어의 jti denylist(로그아웃 즉시 폐기) 검증은 여기 없다 — Redis를
 * 공유하면 인프라 결합이 생겨서다. 어드민 전용 API + 짧은 access 만료(30분)로 노출을 한정하고,
 * 필요해지면 폐기 이벤트 구독 또는 IdP 분리로 해소한다.
 *
 * <p>CORS: 데모 콘솔(pay-core가 서빙, http://localhost:8080)이 이 서비스의 어드민 API를 직접
 * 호출하므로 그 오리진만 허용한다. 운영(K8s)에서는 Ingress 경로 라우팅으로 same-origin이 되어
 * CORS 자체가 불필요해진다.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    // MVC의 HandlerMappingIntrospector도 CorsConfigurationSource 구현이라
                                    // 타입 주입이 모호해진다 — 우리 CORS 빈으로 한정한다.
                                    @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource)
            throws Exception {
        http.csrf(csrf -> csrf.disable())               // 무상태 토큰 인증 — 세션·쿠키 없음
                .cors(cors -> cors.configurationSource(corsSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /** pay-core와 동일한 대칭키(HS256)로 서명을 검증한다. 키는 환경변수로 공유한다. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes (HS256)");
        }
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * roles 클레임(["ROLE_ADMIN"] 형태)을 prefix 추가 없이 그대로 authority로 쓴다 —
     * pay-core의 컨버터와 동일 계약이라 같은 토큰이 양쪽에서 같은 권한으로 해석된다.
     */
    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            List<GrantedAuthority> authorities = roles == null ? List.of()
                    : roles.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origin:http://localhost:8080}") String allowedOrigin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
