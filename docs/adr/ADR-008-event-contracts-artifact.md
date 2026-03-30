# ADR-008. 이벤트 계약을 독립 아티팩트(pay-contracts)로 분리한다

- 상태: 채택 (Accepted)
- 날짜: 2026-08-04
- 관련: [ADR-005](ADR-005-event-externalization-kafka.md)(Kafka 외부화),
  [docs/performance/msa-baseline-experiments.md](../performance/msa-baseline-experiments.md)(분리 근거 실측)

## 맥락

정산(settlement)을 별도 서비스로 분리하기로 했다(근거는 실측 문서 참고: 스케줄러 중복 실행과
배포 결합 재현). 분리된 정산 서비스는 `payment.confirmed`·`payment.canceled`·`escrow.released`
이벤트를 Kafka로 구독해야 하는데, 이 이벤트 record들은 지금까지 pay 본체의 `payment`·`escrow`
모듈 안에 있었다. 프로세스 밖 소비자가 생기는 순간 이 타입들은 한 모듈의 내부가 아니라
**발행자와 소비자가 공유하는 계약(published language)**이 된다.

기존 프로세스 밖 소비자(consumer-app)는 계약 클래스 없이 String + Jackson `readTree`로 파싱해
왔다. 이 방식은 유지하되, 토픽명 문자열이 발행측(`@Externalized`)과 소비측(`@KafkaListener`)에
흩어져 있는 문제는 계약으로 해결한다.

## 결정

이벤트 record 3종과 토픽명 상수(`PayTopics`)를 **독립 Gradle 빌드 `contracts/`
(아티팩트 `com.beomsu.pay:pay-contracts`)**로 옮기고, 소비자들은 **Gradle composite build**
(`includeBuild`)로 조합한다.

```
contracts/src/main/java/com/beomsu/paycontracts/
  PayTopics.java             토픽명 상수 (발행·소비가 같은 상수 참조)
  PaymentConfirmedEvent.java
  PaymentCanceledEvent.java
  EscrowReleasedEvent.java
```

- 메인 앱: `settings.gradle`에 `includeBuild 'contracts'`, 의존은 `com.beomsu.pay:pay-contracts`
- consumer-app: `includeBuild '../contracts'`, `@KafkaListener(topics = PayTopics.PAYMENT_CONFIRMED)`
- 의존성 규율: 계약은 `spring-modulith-events-api`(@Externalized 애노테이션 전용 경량 아티팩트)
  하나만 노출한다. Spring 본체를 끌고 가지 않는다.

## 근거

1. **소유권이 사실을 반영한다.** 이벤트 스키마를 바꾸면 프로세스 밖 소비자가 깨진다. 그런 타입이
   한 모듈의 내부에 있으면 "모듈 내부 수정"처럼 보이는 변경이 실제로는 계약 파괴다. 별도
   아티팩트로 두면 계약 변경이 눈에 보이는 사건(아티팩트 diff)이 된다.
2. **토픽명이 컴파일 타임에 묶인다.** 발행측과 소비측이 `PayTopics` 상수 하나를 참조하므로
   토픽명 오타·불일치가 컴파일 에러가 된다. 문자열 두 곳 관리가 사라진다.
3. **composite build는 퍼블리시 인프라 없이 지금 쓰고, 나중에 전환된다.** `includeBuild`가
   `com.beomsu.pay:pay-contracts` 선언을 로컬 빌드로 치환하므로 저장소 배포 없이 개발한다.
   추후 사내 저장소에 배포해도 소비자의 의존 선언은 그대로다.
4. **와이어 포맷 무변경.** record의 패키지는 JSON 직렬화 결과에 나타나지 않으므로 Kafka
   메시지는 바이트 단위로 동일하다. consumer-app의 String + `readTree` 소비도 무수정으로 동작한다
   (토픽 상수 참조만 추가).

## 패키지를 `com.beomsu.pay` 밖(`com.beomsu.paycontracts`)에 둔 이유

처음에 `com.beomsu.pay.contracts`로 옮겼더니 `ModularityTests`가 깨졌다. Spring Modulith는
베이스 패키지(`com.beomsu.pay`) 직하위 패키지를 클래스패스 출처와 무관하게 애플리케이션
모듈로 간주하므로, jar 안의 계약 패키지가 'contracts' 모듈로 검출됐고 각 모듈의
`allowedDependencies` 화이트리스트에 걸렸다.

계약은 앱 모듈이 아니라 외부 라이브러리다. 8개 모듈의 허용 목록에 'contracts'를 추가하는
대신 패키지를 베이스 밖으로 빼서, Modulith가 Spring·Jackson 타입처럼 취급하게 했다.
모듈 맵도 소스가 `src/` 밖에 있는 유령 모듈 없이 17개 그대로 유지된다.

## 마이그레이션 주의: event_publication의 FQCN

Modulith Outbox(`event_publication`)는 이벤트 타입을 FQCN 문자열로 저장한다. 패키지 이동은
**미완료(재발행 대기) 이벤트가 없는 상태**에서 해야 한다. 미완료분이 남은 채 이동하면 재기동
재발행 시 옛 FQCN을 역직렬화하지 못한다. 이번 이동은 로컬 개발 DB에서 수행했고, 운영 환경이라면
드레이닝(미완료 0 확인) 후 배포해야 한다.

## 대안 검토

- **Avro/Protobuf + 스키마 레지스트리**: 스키마 진화 강제라는 장점이 있으나 인프라(레지스트리)와
  학습 비용이 추가된다. 소비자가 2~3개인 현 단계에서는 JSON + 계약 아티팩트로 충분하고,
  Zero-Payload 원칙(식별자 + 최소 정보)이 스키마 변경 압력 자체를 낮춘다. 소비자가 늘고 스키마
  진화 충돌이 실제로 발생하면 재검토한다.
- **각 모듈의 allowedDependencies에 contracts 추가**: 계약을 앱 모듈로 인정하는 셈이 되고,
  소스가 앱 밖에 있는 모듈이 모듈 맵에 낀다. 기각.
- **소비자마다 이벤트 클래스 복제**: 계약 변경이 조용히 어긋난다(런타임에야 발견). 기각.
