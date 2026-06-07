# ADR-009. 비밀번호 해시는 BCrypt로 두되, 알고리즘 이관 경로를 연다

- 상태: 채택 (Accepted)
- 날짜: 2026-08-28
- 관련: `SecurityConfig`, `RateLimitFilter`, [ADR-006](ADR-006-envelope-encryption-dek-kek.md)

## 맥락

비밀번호는 `BCryptPasswordEncoder`로 해싱해 `members.password_hash`에 저장한다. 인증은 로그인 1회 BCrypt 검증 후 JWT를 발급하는 구조다(요청당 재해싱 없음).

**BCrypt가 깨진 건 아니다. 다만 1순위 권고가 아니다.**

OWASP Password Storage Cheat Sheet는 **Argon2id를 먼저** 권하고, scrypt, 그 다음 bcrypt 순으로 둔다. bcrypt는 "레거시 시스템에서 work factor 10 이상"으로 표현된다.

BCrypt의 한계는 둘이다.

1. **72바이트 절단** — 그보다 긴 비밀번호는 조용히 잘린다. 긴 패스프레이즈를 쓰는 사용자에게 실질 엔트로피 상한이 생긴다.
2. **메모리 하드가 약하다** — 4KB 수준이라 GPU·ASIC 병렬 공격에 Argon2id/scrypt보다 취약하다.

## 결정

**지금은 BCrypt를 유지한다. 대신 `DelegatingPasswordEncoder`로 감싸 알고리즘 접두사를 붙인다.**

```java
String encodingId = "bcrypt";
Map<String, PasswordEncoder> encoders = Map.of(encodingId, new BCryptPasswordEncoder());

DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder(encodingId, encoders);
delegating.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
```

- 새로 만드는 해시: `{bcrypt}$2a$10$...`
- 이미 저장된 접두사 없는 해시: `setDefaultPasswordEncoderForMatches`가 계속 검증한다

## 근거

**1. 지금 Argon2로 바꿀 수 없다.** Spring Security의 `Argon2PasswordEncoder`·`SCryptPasswordEncoder`는 **BouncyCastle을 요구**하는데 이 프로젝트에 그 의존이 없다. `PasswordEncoderFactories.createDelegatingPasswordEncoder()`는 이 둘을 즉시 생성하므로 BouncyCastle 없이는 빈 생성 자체가 실패한다. 그래서 맵을 직접 구성했다.

**2. 의존을 늘리는 것보다 이관 경로를 여는 게 먼저다.** BCrypt는 지금 안전 범위 안에 있다. 급한 건 알고리즘 교체가 아니라 **교체할 수 있는 상태를 만드는 것**이다.

**3. 접두사가 없으면 이관 자체가 불가능하다.** 저장된 해시가 어떤 알고리즘으로 만들어졌는지 알 수 없으면, 나중에 알고리즘을 바꿀 때 **전수 비밀번호 재설정** 말고는 방법이 없다. 접두사를 붙여 두면 맵에 argon2를 추가하고 `encodingId`만 바꿔서, **기존 해시를 그대로 둔 채 새 가입부터** 새 알고리즘으로 넘어간다. 기존 사용자는 다음 로그인 때 재인코딩하는 방식으로 점진 이관할 수 있다.

## 이관 절차 (나중에 할 때)

1. BouncyCastle 의존 추가
2. `encoders` 맵에 `"argon2"` 추가 (19MiB / iterations 2 / parallelism 1 이상)
3. `encodingId`를 `"argon2"`로 변경 — **여기까지가 코드 변경의 전부**
4. 로그인 성공 시 `upgradeEncoding` 판단으로 기존 해시를 점진 재인코딩

## 트레이드오프 / 포기한 것

- **지금 당장의 강도는 안 올라간다.** 인코딩 알고리즘은 그대로 BCrypt다. 이건 **준비**이지 개선이 아니다. 그렇게 적는다.
- 저장 문자열이 접두사만큼 길어진다(`{bcrypt}` 8바이트). 컬럼 길이 여유를 확인했다.
- 72바이트 절단은 **여전히 남아 있다.** Argon2로 넘어가기 전까지 유효한 한계다.

## 연결 — BCrypt를 없앨 수 없는 자리

JWT 전환으로 **요청마다 하던 BCrypt 재검증**은 없앴다(min 110ms → 4.17ms). 그러나 **로그인 한 곳에서는 BCrypt가 남는다.** 비밀번호를 실제로 대조하는 자리라 없앨 수 없다.

그래서 그 경로가 **비대칭 DoS 표면**이 된다. 공격자는 요청 하나를 보내고 서버는 ~110ms의 CPU를 태운다. 비밀번호가 틀려도 해싱은 돌기 때문에 계정을 몰라도 공격이 성립한다.

`RateLimitFilter`가 `/auth/login`·`/members/signup`을 **IP 기준**으로 막는 이유가 이것이다. 이 두 경로는 인증 전이라 principal이 없어 사용자별 제한을 그냥 통과했다. 그리고 `X-Forwarded-For`는 클라이언트가 위조할 수 있어 신뢰하지 않고 소켓 피어만 쓴다.

**같은 110ms가 성능에서는 없앤 병목이고 보안에서는 남겨야 하는 비용이다.** 없앨 수 없으니 유입을 제한하는 쪽으로 갔다.

## 대안

- **지금 Argon2로 교체**: BouncyCastle 의존 추가가 필요하고, BCrypt가 안전 범위 안이라 급하지 않다. 이관 경로를 먼저 열고 필요할 때 넘어간다.
- **`PasswordEncoderFactories.createDelegatingPasswordEncoder()` 사용**: 표준 팩토리지만 Argon2·SCrypt 인코더를 즉시 생성해 **BouncyCastle 없이는 부팅이 깨진다.** 맵을 직접 구성해 회피했다.
- **그대로 두기**: 접두사가 없으면 나중에 전수 재설정 말고는 이관 방법이 없다. 되돌리기 비싼 선택이라 기각.
