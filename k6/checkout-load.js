import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * 체크아웃 부하테스트 — 주문 생성 → 결제 승인 흐름을 실제 사용자 시나리오로 두들긴다.
 *
 * 전제:
 *   1. 앱 실행 (docker compose up 으로 MySQL/Redis 띄운 뒤 ./gradlew bootRun)
 *   2. 상품/재고 시드: products(1, 'A', 10000), stock(1, 넉넉히)
 *   3. k6 run k6/checkout-load.js
 *
 * FakePgClient(비-prod)가 승인을 성공 처리하므로 실제 PG 키 없이 부하를 준다.
 * thresholds를 위반하면(예: p95 > 300ms) k6가 실패로 종료 → 성능 회귀를 CI에서 잡는다.
 */

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    // 목표 산정 예: "선착순 이벤트로 순간 트래픽 급증" → 단계적으로 VU를 올린다
    ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '1m', target: 200 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],            // 오류율 1% 미만
    http_req_duration: ['p(95)<300', 'p(99)<800'],
    'http_req_duration{name:confirm}': ['p(95)<300'],
  },
};

export default function () {
  // 1) 주문 생성 — 클라이언트는 productId·quantity만 보낸다(가격은 서버 권위)
  const orderRes = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    userId: 1,
    items: [{ productId: 1, quantity: 1 }],
  }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'order' } });

  check(orderRes, { 'order 201': (r) => r.status === 201 });
  if (orderRes.status !== 201) return;

  const order = orderRes.json();

  // 2) 결제 승인 — 멱등키 필수, 금액은 서버가 준 totalAmount 그대로
  const confirmRes = http.post(`${BASE}/api/v1/payments/confirm`, JSON.stringify({
    paymentKey: `load-${uuidv4()}`,
    orderNo: order.orderNo,
    amount: order.totalAmount,
  }), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4() },
    tags: { name: 'confirm' },
  });

  check(confirmRes, { 'confirm 2xx': (r) => r.status === 200 || r.status === 202 });

  sleep(1);
}
