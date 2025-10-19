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
