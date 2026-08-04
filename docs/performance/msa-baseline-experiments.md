# 모놀리스 한계 실측: 정산 분리 결정의 근거

정산(settlement)을 별도 서비스로 분리하기 전에, 분리를 정당화하는 한계가 실제로 존재하는지 실측했다.
락 전략을 실측으로 고른 [ADR-004](../adr/ADR-004-stock-deduction-locking.md)와 같은 방법론이다.
"쪼개면 좋다더라"가 아니라, 재현된 문제만 분리의 근거로 쓴다. 분리 후 같은 실험을 재실행해
before/after를 비교하는 것까지가 계획이므로, 이 문서가 그 before 기록이다.

실험 환경: 로컬(Apple Silicon), MySQL 8.4·Redis 7.4(Docker), k6 v1.6.1, Prometheus 스크레이프 5s.

## 요약

| 가설 | 결과 | 분리 근거로 쓰는가 |
|---|---|---|
| 1. 정산 배치가 결제 API 레이턴시를 오염시킨다 | **미재현** (풀 미포화) | 아니오. 정직하게 기각 |
| 2. 수평 확장 시 정산 스케줄러가 중복 실행된다 | **재현** (중복 집계 후 유니크 제약 위반) | 예. 1차 근거 |
| 부수 발견. 단일 계정 동시 결제 시 포인트 적립 낙관적 락 충돌 | 재현 (confirm 70% 실패) | 별도 후속 과제 |

## 실험 1: 정산 배치 ↔ 결제 API 리소스 경합 (미재현)

**가설**: 같은 JVM, 같은 HikariCP 풀을 쓰므로 정산 배치(항목 2만 건 SELECT + 전건 UPDATE를
단일 트랜잭션에 커밋, 약 10초 소요)가 도는 동안 결제 p95가 악화된다.

**방법** (`k6/settlement-contention.js` + `k6/seed-settlement-contention.sql`):
정산 항목 10개 날짜 × 2만 건을 시드하고, 체크아웃(주문 생성 → 결제 승인) 30VU를 4분간 상시로 깐다.
처음 90초는 베이스라인, 90초부터 어드민 API로 배치를 12초 간격으로 연속 트리거해 경합 구간을 만든다.
부하 변동이 없어야 90초 이후의 변화를 배치 탓으로 귀속할 수 있으므로 VU는 고정한다.

**결과**: 배치는 7회 완주했다(22초 간격, 회당 2만 건 집계 약 10초). 그러나 결제 레이턴시는 흔들리지 않았다.

| 구간 | p95 | p99 |
|---|---|---|
| 베이스라인 (배치 없음, 80s 윈도) | 64.3ms | 91.1ms |
| 경합 구간 (배치 7연타, 140s 윈도) | 54.1ms | 68.0ms |

5초 해상도 타임라인에서도 배치 시점 스파이크가 없다. 베이스라인이 오히려 높은 것은 초반 워밍업
(JIT, 커넥션 풀 채움) 때문이다.

**왜 경합이 없었나**: 경합 구간의 HikariCP 지표가 인과를 보여준다. 풀 20개 중 최대 active 15,
**pending 0**. 배치가 커넥션 1개를 10초씩 점유해도 풀에 여유가 남아 대기 큐 자체가 생기지 않았다.
약 26 req/s의 체크아웃 트래픽과 20커넥션 풀 조합에서는 경합 조건이 성립하지 않는다.

**결론**: 이 규모에서 가설 1은 기각한다. 분리의 주 근거로 쓰지 않는다. 재현이 예상되는 조건은
풀 축소(K8s 파드별 리소스 제한), 항목 수십만 건 이상, 배치 병렬 실행 등이며, 분리 후 재실험 시
같은 조건을 유지해 비교한다.

### 부수 발견: rate limiter가 가리고 있던 동시성 결함

1차 실행은 데모 계정 1개로 30VU를 돌렸고, 결제 승인의 70%가 500으로 무너졌다. 원인은 포인트
적립(실결제액의 1%)이 같은 `PointAccount` 행을 동시 갱신하며 낙관적 락 충돌
(`ObjectOptimisticLockingFailureException`)을 일으킨 것.

운영 구성에서는 사용자별 rate limit(5/s)이 같은 사용자의 동시 결제를 차단해 이 경합이 노출되지
않는다. limiter를 끄고 단일 계정으로 부하를 준 조합이 비현실적 모델이었던 것이고, 부하 모델을
VU별 독립 회원(가입 API로 30명 생성)으로 교정해 해결했다. 실사용 트래픽은 다수 사용자다.

남는 질문 하나는 후속 과제로 기록한다. 결제 승인 트랜잭션 안에서 포인트 적립이 동기로 실행되므로,
적립 실패가 결제 실패로 전파된다. 적립을 이벤트 구독(비동기 재시도 가능)으로 옮길지는 별도 검토.

## 실험 2: 수평 확장 시 정산 스케줄러 중복 실행 (재현)

**가설**: 모놀리스를 수평 확장하면 `SettlementScheduler`가 인스턴스마다 돌아 같은 날짜를 중복
집계한다. `existsBySettlementDate` 가드는 동시 진입 레이스를 막지 못한다.

**방법**: 어제 날짜의 CONFIRMED 항목 2만 건을 시드하고, 같은 DB를 쓰는 인스턴스 2개(8081, 8082)를
스케줄러를 켠 채(`app.settlement.enabled=true`) 거의 동시에 기동했다. 배치가 2만 건을 집계하는
약 7초가 레이스 윈도가 된다.

**결과**: 첫 틱에서 즉시 재현됐다.

```
[인스턴스 B :8082] 14:48:58.407 INFO  정산 배치 완료 date=2026-08-03 ... items=20000
[인스턴스 A :8081] 14:48:58.402 ERROR Duplicate entry '2026-08-03' for key 'settlements.uk_settlement_date'
[인스턴스 A :8081] 14:48:58.416 ERROR 정산 배치 실패 date=2026-08-03
                                      DataIntegrityViolationException ...
```

타임라인: 두 인스턴스 모두 기동 직후 같은 틱에 `settle(어제)`에 진입했다. 이 시점에 정산은 아직
없으므로 `existsBySettlementDate`는 양쪽 모두 false. 양쪽이 2만 건을 통째로 중복 집계한 뒤
5ms 차이로 커밋을 다퉜고, 늦은 쪽이 `settlement_date` 유니크 제약에 막혔다.

**해석**:

- 정합성 자체는 지켜졌다. 트랜잭션 전체 롤백과 DB 유니크 제약이 최후 방어선으로 동작했다.
- 그러나 애플리케이션 가드는 동시 진입에 무력했고, 인스턴스 수만큼 전량 중복 집계(무의미한
  2만 건 SELECT + UPDATE 시도)와 배치 실패 에러가 구조적으로 발생한다.
- 근본 원인은 스케일 단위와 배포 단위의 결합이다. 결제 트래픽 때문에 인스턴스를 늘리면
  원하지도 않은 정산 스케줄러까지 복제된다. 오토스케일링(HPA)과 양립할 수 없는 구조다.

**결론**: 가설 2를 채택한다. 이것이 정산을 별도 서비스(단일 스케줄러 소유자)로 분리하는 1차 근거다.

## 종합

분리 결정의 근거는 다음과 같이 확정한다.

1. **스케줄러 중복 실행** (실험 2, 재현): 정산은 단일 실행 주체가 필요하고, 결제는 수평 확장이
   필요하다. 한 배포 단위에 묶여 있는 한 양립하지 않는다.
2. **리소스 경합** (실험 1, 미재현): 이 규모에서는 근거가 아니다. 근거로 쓰지 않는다.
3. 장애 반경(알림·정산 장애가 결제를 물고 넘어지는 문제)은 후속 실험으로 남긴다.

## 재현 방법

```bash
# 실험 1
docker compose up -d && docker compose --profile monitoring up -d prometheus grafana
APP_RATELIMIT_ENABLED=false ./gradlew bootRun          # Flyway 선실행 후
docker compose exec -T mysql mysql -upay -ppay pay < k6/seed-settlement-contention.sql
k6 run k6/settlement-contention.js

# 실험 2 (어제 날짜 2만 건 시드 후, 스케줄러 켠 인스턴스 2개 동시 기동)
./gradlew bootRun --args='--server.port=8081 --app.settlement.enabled=true --app.settlement.interval-ms=30000'
./gradlew bootRun --args='--server.port=8082 --app.settlement.enabled=true --app.settlement.interval-ms=30000'
```

실험 데이터 정리:

```sql
DELETE FROM settlement_items WHERE payment_id >= 900000000;
DELETE FROM settlements WHERE settlement_date BETWEEN '2001-01-01' AND '2001-01-10';
DELETE FROM settlements WHERE settlement_date = DATE_SUB(UTC_DATE(), INTERVAL 1 DAY);
```
