# ADR-017. 알림을 별도 서비스로 추출하고 consumer-app 데모를 은퇴시킨다

> **번호 변경**: 원래 ADR-010이었다. `main`이 모듈러 모놀리스로 되돌아가면서 이 번호를 다른 결정에 다시 썼다 — `main`의 ADR-010은 「안 만들기로 한 것」이다. 충돌을 피해 017로 옮겼다. 내용은 그대로다.


- 상태: 채택 (Accepted)
- 날짜: 2026-08-04
- 관련: [ADR-016](ADR-016-settlement-service-extraction.md)(정산 추출 — 같은 패턴),
  [ADR-015](ADR-015-event-contracts-artifact.md)(이벤트 계약)

## 맥락

정산 추출(ADR-016)로 서비스 분리 패턴(독립 빌드, 전용 스키마, Kafka 컨슈머 그룹, JWT 로컬
검증)이 확립됐다. 알림(notification) 모듈은 남은 추출 후보 중 결합도가 가장 낮고
(`PaymentConfirmedEvent` 하나만 구독, 역참조 0), 멱등 소비(`ProcessedEvent`)와
DLQ(`DeadLetter`)를 인프로세스 시절부터 갖고 있었다. 이 방어선들은 in-process Outbox에서는
다소 이론적이었지만, Kafka at-least-once에서는 실전 필수가 된다.

한편 `consumer-app`은 "프로세스 밖 소비자가 가능하다"를 실증하는 로그 데모였다. 실서비스 두 개
(settlement, notification)가 그 역할을 실제로 수행하는 지금, 데모는 소임을 다했다.

## 결정

- 알림 모듈을 **`notification-service/`(독립 빌드, :8091, `pay_notification` 스키마)**로
  추출한다. 컨슈머 그룹 `notification-service`로 `payment.confirmed`를 구독한다.
- **실패의 두 층위를 구분**한다. 발송 실패(외부 채널 오류)는 기존대로 서비스가 삼켜 자기
  DLQ 테이블에 격리하고 오프셋은 진행한다. 역직렬화 실패(poison)는 서비스에 넣을 수 없으므로
  재시도 3회 후 Kafka DLT(`-dlt`)로 격리한다(ADR-016와 동일 구성).
- DLQ 어드민(조회·재처리)은 서비스와 함께 이동하고, 데모 콘솔 DLQ 탭은 :8091을 직접
  호출한다(CORS, K8s Ingress 도입 시 제거).
- **consumer-app을 삭제한다.** 실증 목적이 실서비스로 대체됐고, 유지하면 빌드·문서 관리
  대상만 늘린다. 역사적 맥락은 ADR-005와 git 이력에 남는다.

## 실측 (추출 당일, 로컬)

- **이벤트 로그 재생**: 새 컨슈머 그룹이 `auto-offset-reset=earliest`로 부팅하며 당일 누적
  이벤트 전체를 재생 — 결제 5,072건 = 알림 처리(processed_events) 5,072건, 발송 DLQ 0건.
  빈 DB가 이벤트 로그만으로 재구성됐다(로그가 곧 진실 원천).
- **중복 배달 멱등**: 실제 이벤트 1건을 토픽에 재주입 → 알림 5,072건 불변(ProcessedEvent
  유니크), 정산 항목 5,072건 불변(existsByPaymentId 가드). 두 서비스의 멱등층이 실제 중복으로
  증명됐다.
- **poison 격리**: 기존 poison 레코드를 재생 중 만나 4회 시도 후 DLT 격리, 후속 이벤트 처리
  계속(파티션 비정지).

## 결과 구조

```
pay-core(:8080) ──payment.confirmed──▶ settlement-service(:8090)  pay_settlement
        │                          └─▶ notification-service(:8091) pay_notification
        ├──payment.canceled ─────────▶ settlement-service
        └──escrow.released ─────────▶ settlement-service
(토픽별 독립 컨슈머 그룹 — 같은 이벤트를 각자의 오프셋으로 소비)
```

## 정직한 한계

- 발송자는 여전히 `LoggingNotificationSender`(로그 데모)다. 실 채널(푸시·이메일) 연동은 범위 밖.
- DLQ 재처리는 어드민 수동 트리거만 있다(자동 재시도 배치는 후속 과제).
- 서비스 다중화 시 스케줄러 문제는 없지만(스케줄러 없음), 컨슈머 그룹 리밸런싱 중 일시적
  중복 배달이 늘 수 있다 — 멱등층이 흡수한다.
