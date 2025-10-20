# 관측성(Observability) 구성

Actuator + Micrometer → Prometheus → Grafana.

## 노출 메트릭
- `payment_confirm_total{outcome="success|failed|timeout"}` — 결제 승인 결과별 카운터
  (PaymentService에서 계측). **결제 성공률 SLO**의 소스.
- Spring Boot 기본: `http_server_requests_seconds`(p95/p99), `hikaricp_connections_*`(풀 사용률),
  `resilience4j_circuitbreaker_state`(서킷 상태), JVM/시스템 메트릭.

## 실행
```bash
./gradlew bootRun                                   # /actuator/prometheus 노출
prometheus --config.file=monitoring/prometheus.yml  # 수집
# Grafana에서 Prometheus 데이터소스 추가 후 dashboard.json 임포트
```

## Grafana 핵심 패널 (dashboard.json)
1. 결제 성공률 = success / (success+failed+timeout)  — SLO
2. p99 레이턴시 (http_server_requests p99)
3. HikariCP 풀 사용률
4. 서킷브레이커 상태 (CLOSED/OPEN/HALF_OPEN)
5. UNKNOWN(미확정) 결제 추이 — 복구 배치 부하 신호
