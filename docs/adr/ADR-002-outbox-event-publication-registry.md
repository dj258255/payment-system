# ADR-002. Transactional Outbox를 Spring Modulith Event Publication Registry로 구현한다

- 상태: 채택 (Accepted)
- 날짜: 2026-07-05
- 관련: [ADR-001](ADR-001-architecture-spring-modulith.md), [docs/03-아키텍처-설계.md](../03-아키텍처-설계.md) §2

## 맥락

결제 완료 이벤트를 다른 모듈(원장·정산)이나 외부(Kafka)로 전달할 때, "DB 커밋"과 "이벤트 발행"을 둘 다 성공시켜야 한다. 이 둘을 순진하게 나눠 쓰면(dual write):

- DB 커밋 후 발행 실패 → **이벤트 유실** (결제는 됐는데 원장에 안 남음)
- 발행 후 DB 롤백 → **유령 이벤트** (원장에는 있는데 결제가 없음)

정석 해법은 Transactional Outbox: 도메인 변경과 이벤트를 **하나의 로컬 트랜잭션**으로 저장하고, 릴레이가 나중에 발행.

## 결정

Outbox를 직접 구현하지 않고 **Spring Modulith의 Event Publication Registry**를 쓴다.

- 모듈이 `ApplicationEventPublisher`로 이벤트를 발행하면, Modulith가 **같은 트랜잭션에서 `event_publication` 테이블에 INSERT**한다.
- 트랜잭션 커밋 후 리스너(`@ApplicationModuleListener`)가 호출되고, 성공 시 발행 완료로 마킹.
- 리스너 실패/앱 다운 시 미완료 이벤트가 테이블에 남고, `republish-outstanding-events-on-restart: true`로 재기동 시 재발행.

즉 `event_publication` 테이블이 곧 outbox이며, 우리가 09 문서에서 설계한 `outbox_events` 테이블의 역할을 프레임워크가 검증된 형태로 제공한다.

## 근거

1. **바퀴를 다시 발명하지 않는다**: 폴링 릴레이·중복 발행 처리·재시도를 직접 짜면 버그 온상. Modulith 구현은 검증돼 있다.
2. **at-least-once**: 트랜잭션이 커밋되면 이벤트는 반드시 (언젠가) 발행된다 → 소비자는 **멱등 컨슈머**로 설계 (processed_events, ADR 후속).
3. **`@ApplicationModuleListener` = `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` + 트랜잭션 전파**를 한 번에 준다.

## 포기한 것 / 주의

- 외부 Kafka로의 발행은 Modulith 이벤트를 **브릿지**로 다시 내보내야 한다(`spring-modulith-events-kafka`). Phase 3에서 도입.
- 이벤트 페이로드는 **Zero-Payload 지향**(식별자+행위+시각) — 순서 역전·스키마 결합 문제를 피한다(배민 사례).
- 직접 구현한 outbox가 아니므로, "검증된 구현을 쓰되 원리는 이해한다"는 판단을 남긴다. 원리는 03 문서에 정리.
