# 성능 리포트 — 방법론과 실측

> 카카오페이 "온라인 결제 2.5배 성능 개선기"의 프레임을 따른다:
> **목표 산정 → 현재 한계 측정 → 병목 계측 → 개선(하나씩) → 전후 수치**.
> "측정 없이는 개선 없다."

## 1. 재고 차감 락 3종 비교 (실측 완료)

동시성 제어 전략 선택을 감이 아니라 수치로 했다. `StockLockComparisonTest`가 실제 스레드로
동시 차감을 측정한다(H2, 30스레드가 재고 20에 동시 차감, Docker 불필요·CI 결정적).

| 전략 | 초과판매 | 완판 | 소요 |
|---|---|---|---|
| **조건부 UPDATE** | 0 | 20/20 | **8ms** |
| 낙관적 락 | 0 | 20/20 | 17ms |
| 비관적 락 | 0 | 20/20 | 32ms |

**결론**: 단일 재고 행 차감은 조건부 UPDATE가 최속이자 최저비용 → 기본 전략으로 채택([ADR-004](../adr/ADR-004-stock-deduction-locking.md)).
낙관적 락은 스레드를 150으로 올리면 재시도 소진으로 미달판매가 발생한다(hot row 부적합).

재현:
```bash
JAVA_HOME=<jdk21> ./gradlew test --tests "com.beomsu.pay.order.StockLockComparisonTest"
```

## 2. 엔드투엔드 부하테스트 (k6)

`k6/checkout-load.js`가 주문 생성 → 결제 승인 흐름을 실제 사용자 시나리오로 두들긴다.
(FakePgClient가 승인을 성공 처리하므로 실제 PG 키 없이 부하를 준다.)

### 실행
```bash
# 1) 인프라 + 앱
docker compose up -d              # MySQL 8.4 + Redis 7.4
./gradlew bootRun                 # (스키마·시드 준비 후)
# 2) 부하
k6 run k6/checkout-load.js
```

### 개선 스토리 프레임 (리포트 작성 시)
1. **목표 산정 근거**: 예) "선착순 이벤트 1만 명 → 순간 목표 300 TPS"
2. **현재 한계 측정**: k6 ramping-vus로 TPS가 꺾이는 지점·p99·오류율
3. **병목 계측**: slow query 로그, `EXPLAIN`, 커넥션 풀 사용률, APM
4. **개선(하나씩, 재측정)**:
   - HikariCP 풀 튜닝 (데드락 회피 공식 `pool ≥ Tn×(Cm−1)+1`)
   - 조회 쿼리 인덱스/커버링 인덱스 (`Using Where` → `Using Index`)
   - 캐싱 (TTL 지터·널 오브젝트)
   - 선착순은 Redis Sorted Set 대기열로 DB 유입 제어
5. **전후 수치 대비** + 남은 한계

### thresholds (성능 회귀 게이트)
`checkout-load.js`는 `p95<300ms`, `p99<800ms`, 오류율 `<1%`를 임계치로 두어, 위반 시 k6가
실패로 종료한다 — PR마다 소규모 부하로 성능 회귀를 커밋 단위로 잡을 수 있다.

## 3. 관측성 (Phase 6에서 대시보드화)

핵심 패널: 결제 성공률(SLO), p99 레이턴시, HikariCP 풀 사용률, 서킷브레이커 상태, Kafka lag.
Actuator + Micrometer → Prometheus → Grafana. (Phase 6 참조)

## 메모

- 락 비교는 H2 인메모리로 결정적으로 실측했다. 실 DB(MySQL)에서의 절대 수치는 다르지만, **전략 간 상대 우열**(조건부 < 낙관적 < 비관적)은 동일하게 재현된다.
- 전체 앱 컨텍스트 + Testcontainers 기반 동시성 테스트도 시도했으나, 부팅 비용이 커 CI 기본 스위트에서는 제외하고 H2 경량 비교로 대체했다.

## 4. 확장 후 엔드투엔드 재측정 (인증 포함)

확장 모듈(point·subscription·wallet·fraud·audit·receipt·va)까지 스키마에 얹고, 인증(ROLE_USER)이
걸린 상태로 k6 재실행:

- 50 VU, 오류율 0%, **p95 567ms · p99 712ms** (min 110ms)

무인증 초기 측정(min ~3.5ms, p95 96ms)과 비교하면 **요청당 최소 지연이 ~110ms로 상승**했다.
원인은 **HTTP Basic + BCrypt**다 — 무상태 Basic 인증은 요청마다 비밀번호를 BCrypt로 재검증하는데,
BCrypt는 의도적으로 느리다(브루트포스 방어). 즉 이 지연은 앱 로직이 아니라 **인증 방식의 비용**이다.

실서비스는 이를 **토큰/세션**으로 해소한다 — 로그인 때 한 번만 검증하고 이후엔 서명 검증(JWT)이나
세션 조회로 요청당 재해싱을 피한다. 부하테스트가 "무엇이 병목인지"를 정확히 짚어준 사례다.

## 5. 병목 제거 — JWT 전환 후 재측정 (실측 완료)

4절에서 짚은 병목(요청당 BCrypt)을 실제로 제거했다. 인증을 **HTTP Basic + 요청당 BCrypt →
JWT Bearer**(로그인 시 BCrypt 1회, 이후 서명 검증만)로 교체하고, **같은 머신·같은 부하 조건**
(50 VU, load avg ~6)에서 `k6/checkout-load.js`를 재실행했다.

| 지표 | HTTP Basic + BCrypt (4절) | **JWT (5절)** | 개선 |
|---|---|---|---|
| min | 110ms *(BCrypt 바닥)* | **4.17ms** | ~26배 |
| p95 | 567.84ms | **37.09ms** | ~15배 |
| p99 | 712.27ms | **58.47ms** | ~12배 |
| 오류율 | 0% | 0% | — |
| 처리 | — | 4,071 req, 50 req/s | — |

**요청당 ~110ms의 BCrypt 바닥이 사라졌다.** 한 번의 체크아웃이 주문+승인으로 인증을 두 번
타므로 개선폭이 특히 컸다. 병목이 **앱 로직이 아니라 인증 방식의 비용**이었음을 전후 수치가
확증한다 — "측정 → 병목 지목 → 개선 → 재측정"의 한 사이클을 닫았다.

재현:
```bash
docker compose up -d && ./gradlew bootRun          # 스키마·시드 자동 준비(V1~V4)
USER_PASSWORD=user-local-only k6 run k6/checkout-load.js
```

## 6. 카오스 테스트 — 실제 네트워크 장애로 복원력을 검증한다

성능이 "빠른가"라면, 복원력은 "장애가 나도 정합성이 깨지지 않는가"다. 서킷브레이커·타임아웃=UNKNOWN·
멱등 같은 복원력 설계를 **코드로 주장만** 하지 않고 **장애를 주입해** 확인한다. 두 층위로 나눴다.

### (1) 서킷브레이커 단위 테스트 — 컨테이너 없이 결정적으로

`ResilientPgClientTest`는 장애를 주입하는 `PgClient` 더블을 `new ResilientPgClient(faulty)`로 감싸,
resilience4j 데코레이터의 동작을 순수 단위로 못박는다(부팅·컨테이너 없음, 기본 스위트 포함).

- **승인은 재시도하지 않는다**: faulty delegate가 예외를 던져도 delegate를 **딱 1번** 호출하고
  결과는 `TIMEOUT`(=UNKNOWN). 멱등키 없는 승인 재시도는 이중결제라서, 실패로 단정하지 않고 미확정으로 돌린다.
- **서킷 오픈 폴백**: 반복 실패로 서킷이 OPEN되면, 이후 승인은 delegate를 **아예 호출하지 않고**
  즉시 `TIMEOUT`으로 폴백한다(호출 횟수가 더 늘지 않음을 검증 → PG 장애가 우리 스레드를 고갈시키지 않음).
- **조회는 재시도한다**: 읽기라 안전하므로 몇 번 실패 후 성공하면 재시도로 흡수한다(호출 > 1).
  단, 재시도는 **무한이 아니라** maxAttempts(3)에서 멈추고 예외를 전파해 복구 배치가 다음 주기에 다시 잡는다.

### (2) Toxiproxy 네트워크 카오스 — 실제 장애를 주입해 정합성 확인

`MySqlChaosTest`(`@Tag("chaos")`)는 MySQL 컨테이너와 Toxiproxy 컨테이너를 같은 네트워크에 띄우고
앱을 부팅한다. 앱의 `spring.datasource.url`을 **Toxiproxy 프록시 주소**로 주입해 모든 DB 트래픽이
장애 구간을 통과하게 만든 뒤:

1. 정상 상태에서 체크아웃(주문 생성 → 승인) 1건 성공.
2. `latency(UPSTREAM, 5s)` toxic 주입 — JDBC `socketTimeout`(3s)·Hikari `connectionTimeout`(2s)을
   짧게 둬, 장애가 **무한 hang이 아니라 타임아웃 예외(5xx)로 깨끗하게** 끝나는지(부분 커밋 없음) 확인.
3. toxic 제거 후 **같은 Idempotency-Key로 재시도** → 첫 응답이 그대로 재반환되어
   **이중 결제/이중 차감이 없음**(멱등이 장애를 건너 생존)을 확인.

### 실행법과 기본 스위트 제외 이유

```bash
./gradlew test        # 기본: 카오스 제외(excludeTags 'chaos'), 컨테이너 안 뜸 — 빠르고 결정적
./gradlew chaosTest   # 카오스만: 컨테이너 2개 + 부트 필요, 전용 환경에서 수동 실행
```

카오스 테스트는 컨테이너 2개 + 앱 부팅이 필요해 무겁고, 머신에 따라 부팅이 불안정하다. CI 기본
스위트의 **결정성과 속도**를 지키려고 `@Tag("chaos")`로 분리해 기본 `test`에서 제외하고, 네트워크가
준비된 전용 환경에서만 `chaosTest`로 돌린다.

## 7. 폭주 유입 제어 — 스파이크 실측 (실측 완료)

순간 트래픽 폭증(선착순 등)에 대한 유입 제어를 넣고, **같은 스파이크(0→150 VU 10초 급증)**를
제어 전/후로 두 번 실측했다(`k6/spike-test.js`, `app.ratelimit.enabled`로 전환).

제어 3층: ① Redis 분산 rate limiter(사용자별 5/s + 전역 100/s → 초과분 `429 + Retry-After`)
② 한정판 상품 대기열 게이트 강제(입장권 없으면 `429 QUEUE_PASS_REQUIRED`)
③ 빠른 실패 풀 설정(Hikari connection-timeout 3s·pool 20, Tomcat threads 100 — 매달리지 않고 실패).

| 지표 | 제어 없음 | **제어 있음** |
|---|---|---|
| 유입 | 304 req/s, **전량 DB까지 처리** | 398 req/s 중 **97.5%를 429로 거절** |
| DB 도달 요청 | 15,445건 (100%) | ~510건 (2.5%) |
| 성공 요청 p95 | 737.81ms | **52.01ms (14배↓)** |
| 성공 요청 max | 1.48s | 105ms |
| 5xx | 0% | 0.55% (아래) |

**해석**: 제어가 없으면 폭주가 전량 DB로 흘러 정상 요청까지 같이 느려진다(p95 738ms — 평시 37ms의
20배). 제어를 켜면 감당 못 할 요청을 바깥 층에서 싸게(429) 거절하고, **통과한 요청은 평시에 가까운
속도(p95 52ms)를 유지**한다 — "모두가 느려지는 것"에서 "일부는 기다리고 나머지는 정상"으로.

**부수 발견 → 해소**: 제어 ON에서만 5xx 113건(0.55%) — 고정 윈도우 경계에 통과 요청이
버스트로 몰리며 `event_publication`(outbox) INSERT가 MySQL 데드락(1213). 제어가 없을 땐 지연이
자연 직렬화해 0건이었다. **후속 조치**: 데드락은 MySQL이 "재시작하라"고 명시하는 transient
실패라, 모든 변경 API를 감싸는 `IdempotencyService`에 재시도(최대 3회, 지터 백오프)를 넣었다.
타임아웃 무재시도 규칙과 충돌하지 않는 이유 — 타임아웃은 결과 미상(UNKNOWN)이지만 데드락은
**전체 롤백이 확정된 실패**라 재실행이 안전하다. 재실측: 5xx **0.00%**(이번 런은 데드락 자체가
확률적으로 0건 — 재시도는 단위 테스트 3종으로 검증된 안전망), `idempotency.deadlock.retry`
카운터로 발동을 관측한다.

재현:
```bash
./gradlew bootRun --args='--app.ratelimit.enabled=false'   # 전
USER_PASSWORD=user-local-only k6 run k6/spike-test.js
./gradlew bootRun                                           # 후(기본 on)
USER_PASSWORD=user-local-only k6 run k6/spike-test.js
```
