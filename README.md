# pay — Spring Modulith 결제 시스템

[![CI](https://github.com/dj258255/payment-system/actions/workflows/ci.yml/badge.svg)](https://github.com/dj258255/payment-system/actions/workflows/ci.yml)

실 서비스 운영을 상정해 만든 결제 백엔드. 결제의 정상 경로보다 **실패·정합성 처리**에 무게를 뒀다 —
타임아웃/중복/장애 같은 사건이 실제로 일어난다고 전제하고, 각 사건을 상태로 보존하고 확정하는 구조로 설계했다.

## 데모 콘솔

`docker compose up -d && ./gradlew bootRun` 후 `http://localhost:8080/` 에서 전 흐름을 눌러볼 수 있는 데모 콘솔을 함께 제공한다(Spring이 정적 서빙, same-origin이라 별도 프론트 서버·CORS 불필요).

**결제 플로우** — 로그인(JWT) → 주문 생성 → 결제 승인 → 취소/구매확정. 응답이 아니라 실제 API 호출·상태를 그대로 보여준다.

![결제 플로우 데모](docs/images/demo-checkout.png)

**운영 콘솔(ROLE_ADMIN)** — 미확정 결제 복구, 보상 태스크 재처리, 정산 대사, 강제취소 2인 승인, FDS 사후 심사, DLQ.

![운영 콘솔 데모](docs/images/demo-admin.png)

**강제취소 · 2인 승인(maker-checker)** — 요청자와 승인자가 반드시 달라야 실행된다. 요청자 본인이 승인하면 `MAKER_CHECKER_VIOLATION`으로 막힌다.

![maker-checker 본인 승인 차단](docs/images/demo-maker-checker.png)

## 기술 스택

- **Java 21**, **Spring Boot 3.4**, **Spring Modulith 1.3**
- **MySQL 8.4** + JPA(도메인 모델) + QueryDSL, **Flyway**(스키마 마이그레이션)
- **Redis**(캐시·분산락), **Resilience4j**(서킷브레이커·재시도), **Kafka**(결제 이벤트 외부화 — 프로세스 밖 소비자용, 브로커 있을 때만)
- **Micrometer + Prometheus/Grafana**(관측성), **Spring Security**(인증·인가)
- 테스트: JUnit5 + Mockito, H2(동시성 실측), **344 tests** + Spring Modulith 경계 검증 + Toxiproxy 카오스(`chaosTest`)

## 아키텍처 — 모듈형 모놀리스

`com.beomsu.pay` 바로 아래 각 패키지가 하나의 애플리케이션 모듈이다. 모듈 간 통신은 직접 호출이 아니라
**도메인 이벤트**로 하고, 그 경계를 테스트(`ModularityTests`)가 강제한다. 규칙 위반 시 빌드가 깨진다.

```
com.beomsu.pay
├── order          주문 상태머신, 금액 위변조 검증, 체크아웃 오케스트레이션, 멱등키
├── payment        승인/취소/멱등/상태머신, PG 연동(3-상태), 망취소, 웹훅, 가상계좌
├── ledger         복식부기 원장 (차변=대변 불변식)
├── settlement     Spring Batch 정산
├── escrow         자금 보류(에스크로) — 구매확정 전까지 HELD, 확정 시 RELEASED/취소 시 REFUNDED
├── reconciliation 대사 (내부 vs PG 파일 4분류)
├── notification   결제 이벤트 소비 (멱등 컨슈머 + DLQ)
├── point          포인트 원장 (복합결제)
├── subscription   빌링키 정기결제 + dunning
├── wallet         선불 충전 월렛 (전금법 한도)
├── fraud          이상거래탐지(FDS) 룰 엔진
└── shared         Money, ULID 등 공유 값 타입 (OPEN 모듈)
```

이벤트 발행은 Spring Modulith의 Event Publication Registry(= Transactional Outbox)로 신뢰성을 보장한다
([ADR-002](docs/adr/ADR-002-outbox-event-publication-registry.md)).

## 핵심 설계

| 영역 | 설계 |
|---|---|
| 신뢰 경계 | 금액·가격·userId를 클라이언트가 아니라 서버/인증 컨텍스트에서 정한다 (위변조·IDOR 차단) |
| 실패 처리 | PG 타임아웃을 `UNKNOWN`으로 보존 → 복구 배치가 조회로 확정 / 망취소 / 서킷브레이커 |
| 멱등성 | `Idempotency-Key` + DB 유니크 제약 (INSERT 성공 = 처리권 획득) |
| 이벤트 | Outbox → 멱등 컨슈머 → DLQ (유실·중복·순서역전 대응) |
| 정합성 | 복식부기 원장(차변=대변)으로 자금 이동을 수학적으로 검증, 대사가 최종 방어선 |
| 동시성 | 재고·잔액 차감 락 3종 비교 실측 후 조건부 UPDATE 채택 ([ADR-004](docs/adr/ADR-004-stock-deduction-locking.md)) |

## 실행

```bash
docker compose up -d              # MySQL 8.4 + Redis 7.4
./gradlew bootRun                 # Flyway 마이그레이션 후 기동 (localhost:8080)
```

부하테스트:
```bash
k6 run k6/checkout-load.js        # 주문→승인 흐름 (인증 필요)
```

## 문서

- [docs/02 결제 도메인 핵심 개념](docs/02-결제도메인-핵심개념.md) — PG/VAN 구조, 결제 3단계, 상태머신
- [docs/03 아키텍처 설계](docs/03-아키텍처-설계.md) — 멱등성, Saga/Outbox, 원장, 웹훅, 정산/대사
- [docs/04 장애 시나리오 설계](docs/04-장애-시나리오-설계.md) — 외부 API 실패 처리 전반
- [docs/05 성능 전략](docs/05-성능개선-전략.md) — 동시성 제어, 부하테스트, 관측성
- [docs/09 ERD](docs/09-ERD-설계.md), [docs/10 API 스펙](docs/10-API-스펙.md)
- [docs/adr](docs/adr/) — 아키텍처 결정 기록
