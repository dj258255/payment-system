# K8s 배포 (로컬 kind)

pay 3-서비스(코어·정산·알림) + 인프라(MySQL·Redis·Kafka)를 단일 kind 클러스터에 올린다.
Ingress 경로 라우팅이 세 서비스를 same-origin으로 묶어, 로컬 개발에서 쓰던 CORS가 사라진다.

```
localhost:8081 (Ingress)
  ├── /api/v1/admin/settlements/**   → settlement-service(:8090)   pay_settlement DB
  ├── /api/v1/admin/dead-letters/**  → notification-service(:8091) pay_notification DB
  └── /**                            → pay-core(:8080)             pay DB
                    (셋은 클러스터 내 Kafka(kafka:9092)로 이벤트를 주고받는다)
```

## 기동

```bash
# 1. 이미지 빌드
./gradlew bootJar && ./gradlew -p settlement-service bootJar && ./gradlew -p notification-service bootJar
docker build -t pay-core:local .
docker build -t settlement-service:local settlement-service
docker build -t notification-service:local notification-service

# 2. 클러스터 + Ingress + metrics-server(HPA용)
kind create cluster --name pay --config k8s/kind-config.yaml
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

# 3. 이미지 주입 → 인프라 → 앱
kind load docker-image pay-core:local settlement-service:local notification-service:local --name pay
kubectl apply -f k8s/infra/
kubectl wait --for=condition=ready pod -l app=mysql -l app=kafka --timeout=180s
kubectl apply -f k8s/apps/

# 4. 데모 콘솔
open http://localhost:8081/
```

## 설계 메모

- **probe**: Spring Boot가 K8s를 감지하면 `/actuator/health/{readiness,liveness}` 그룹을 자동
  노출한다. 시큐리티는 `/actuator/health/**`까지 공개해야 한다(정확 일치만 열면 probe가 401 —
  실제로 겪고 고친 함정).
- **무중단 종료**: `preStop sleep 5`(엔드포인트 제거 전파) + 앱의 graceful shutdown 20s 드레이닝
  + `terminationGracePeriodSeconds: 30`. 셋의 부등식(30 > 5 + 20)이 지켜져야 SIGKILL이 없다.
- **HPA는 pay-core만**: 분리의 결실 — 모놀리스에선 스케일아웃이 정산 스케줄러를 복제했다
  (실측 재현). 정산은 replicas 1 고정(스케줄러 단일 소유), 알림은 파티션 수가 소비 병렬성의 상한.
- **인프라는 개발용**: emptyDir 단일 인스턴스(Recreate 전략). 운영은 관리형 DB/MSK 또는
  StatefulSet + PVC.

## 검증된 시나리오 (2026-08-05 실측)

- Ingress 경로 라우팅으로 콘솔 → 3서비스 전 흐름(결제→Kafka→정산 적재·알림 처리) 동작.
- **파드 kill 데모**: 결제를 2초 간격으로 쏘면서 `kubectl delete pod -l app=settlement-service`
  → 결제 15/15 성공(격리), Deployment 자가치유로 새 파드 기동, 복구 후
  결제 16건 = 정산 적재 16건(**유실 0** — 다운 중 이벤트는 Kafka에 적체, 컨슈머가
  커밋 오프셋부터 재개).
- **무중단 롤링**: 노드 용량 내 부하(10VU) 중 `rollout restart` → **5xx·연결거부 0건**
  (잔여 0.3%는 멱등 계약의 409 충돌 응답). 여기까지 오는 데 4회의 실측·진단이 필요했다 —
  probe timeout 오탐, MySQL CFS 기아, MySQL OOM 순으로 잡았다. 전 과정은
  [실측 문서](../docs/performance/msa-baseline-experiments.md)의 롤링 절 참고.
- **HPA**: 부하 구간에서 pay-core만 1→3 스케일아웃 관측(정산·알림 불변 — 분리의 결실).

## 실측이 남긴 하드닝 (전부 겪고 고친 것)

| 증상 | 원인 | 수정 |
|---|---|---|
| probe 401 → CrashLoop | 시큐리티가 `/actuator/health` 정확 일치만 허용 | `/actuator/health/**` 공개 |
| 부팅 중 liveness kill 연쇄 | probe timeout 기본 1s + liveness가 부팅 감시 | startupProbe 신설, timeout 3s |
| 롤링 중 전 레플리카 동시 교체 | 기본 maxUnavailable 25% | `maxUnavailable: 0, maxSurge: 1` |
| 부팅 파드 Flyway 연결 실패 | MySQL cpu request 250m — 포화 시 CFS 밀림 | DB는 공유 병목, cpu "1"로 |
| 부하 중 MySQL OOMKill | 메모리 limit 1Gi < 버퍼풀+커넥션 실사용 | limit 1536Mi |
