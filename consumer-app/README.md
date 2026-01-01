# pay-consumer — 프로세스 밖 결제 이벤트 소비자 (정산 알림 데모)

메인 pay 앱이 `@Externalized`로 Kafka에 내보내는 `payment.confirmed` / `payment.canceled`
토픽을 구독하는 **별도 프로세스** 앱이다. 도메인 코드를 한 줄도 고치지 않고 다른 프로세스가
결제 이벤트를 받는 것을 실증한다 — [ADR-005](../docs/adr/ADR-005-event-externalization-kafka.md)의
"프로세스 밖 소비자" 약속의 이행.

> 구조 결정: 메인 빌드에 영향을 주지 않도록 **독립 Gradle 프로젝트**로 둔다
> (루트 settings.gradle에 include하지 않음 — 메인 CI/테스트 무영향).

## 실행

```bash
docker compose up -d kafka mysql redis
SPRING_PROFILES_ACTIVE=kafka ./gradlew bootRun            # 메인 앱(외부화 on)
./gradlew -p consumer-app bootRun                          # 소비자 앱(별도 프로세스)
```

결제 승인/취소가 일어나면 소비자 콘솔에 `[정산알림] 결제 완료 수신 orderNo=... amount=...`
로그가 찍힌다.

## 설계 메모

- **String + Jackson 역직렬화**: producer의 JsonSerializer 타입 헤더(메인 앱 클래스명)에
  결합되지 않도록 값을 String으로 받아 `readTree`로 파싱한다.
- **at-least-once**: Outbox 재발행으로 중복 수신 가능 — 실소비자는 멱등 처리 필수(여기선 로그 데모).
- **Zero-Payload**: 페이로드는 식별자+최소 정보. 상세는 orderNo로 조회 API를 되읽어 확정한다.
