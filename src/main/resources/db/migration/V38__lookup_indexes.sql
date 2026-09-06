-- 조회 조건 컬럼에 인덱스를 건다.
--
-- 배치의 무제한 조회를 잡고 나서 서빙 쪽은 안 봤다는 것을 알고 실 MySQL에서 쟀다.
-- 내 주문 목록 조회는 건수가 이미 50건으로 묶여 있었는데도 65.6ms 걸렸다. user_id 인덱스가
-- 없어 PK를 역방향으로 훑으며 걸러냈고, 주문이 50건 미만인 사용자는 그 50건을 영영 못 채워
-- 테이블 끝까지 갔다. 30만 행 전부다. 실제 서비스에서 주문 50건 미만은 대다수 사용자다.
--
-- EXPLAIN 만으로는 못 잡는다. 옵티마이저는 rows=50 이라고 추정했는데 EXPLAIN ANALYZE 로 보니
-- 실제로 훑은 것은 300,000 행이었다. 추정이 6,000배 틀렸고 계획만 보면 멀쩡해 보였다.
-- (user_id, id) 를 걸고 65.6ms → 0.6ms.
--
-- status 는 값이 세 가지뿐이라 "카디널리티가 낮아 인덱스가 소용없다"는 통념의 대상이다.
-- 갈라서 재 보니 통념이 반만 맞았다. 드문 값(PENDING 0.1%)을 찾을 때는 15.2ms → 0.7ms 로
-- 20배 빨라졌고, 흔한 값(DONE 99.9%)을 찾을 때는 0.6ms → 1.0ms 로 오히려 느려졌다.
-- 인덱스를 걸지 말지는 컬럼의 성질이 아니라 그 조회가 무엇을 찾는지에 달렸다.
-- 이 저장소의 status 조회는 전부 할 일을 찾는 조회다(PENDING·UNKNOWN·HELD·OPEN·WAITING).
--
-- 재현: ./gradlew integrationTest --tests "*OrderQueryIndexMySqlTest" "*StatusIndexSelectivityMySqlTest"

-- 사용자·주문번호로 찾는 조회 (고카디널리티 점 조회)
CREATE INDEX idx_orders_user_id            ON orders (user_id, id);
CREATE INDEX idx_payments_payment_key      ON payments (payment_key);
CREATE INDEX idx_point_histories_user_id   ON point_histories (user_id, id);
CREATE INDEX idx_point_histories_order_no  ON point_histories (order_no);
CREATE INDEX idx_wallet_tx_user_id         ON wallet_transactions (user_id, id);
CREATE INDEX idx_subscriptions_user_id     ON subscriptions (user_id, id);
CREATE INDEX idx_subscriptions_billing_key ON subscriptions (billing_key, status);
CREATE INDEX idx_disputes_order_no         ON disputes (order_no);
CREATE INDEX idx_cash_receipts_order_no    ON cash_receipts (order_no);
CREATE INDEX idx_recon_results_order_no    ON reconciliation_results (order_no);
CREATE INDEX idx_settlement_items_order_no ON settlement_items (order_no);
CREATE INDEX idx_virtual_accounts_pay_key  ON virtual_accounts (payment_key);
CREATE INDEX idx_dunning_subscription_id   ON dunning_attempts (subscription_id);

-- 할 일을 찾는 조회 (드문 상태 + 그 조회가 함께 거는 시각 컬럼)
-- 시각을 두 번째 자리에 두어야 status 로 좁힌 뒤 범위까지 인덱스로 끝난다.
-- payments (status, requested_at) 는 이미 있다 — 미확정 복구 배치 때 걸어 뒀다.
CREATE INDEX idx_orders_status_expires     ON orders (status, expires_at);
CREATE INDEX idx_settlement_items_status   ON settlement_items (status, confirmed_date);
CREATE INDEX idx_disputes_status           ON disputes (status, id);
CREATE INDEX idx_recon_results_status      ON reconciliation_results (status, id);
CREATE INDEX idx_virtual_accounts_status   ON virtual_accounts (status, due_date);
-- 구독 청구 배치는 예외다. 찾는 값(ACTIVE)이 드물지 않고 흔하다. 그래도 값을 하는 이유는
-- 선택도가 상태가 아니라 날짜에서 나오기 때문이다 — next_billing_date <= 오늘 이 오늘치만 남긴다.
-- 그래서 날짜가 반드시 둘째 자리에 있어야 하고, status 만 걸었다면 아무 값도 못 했을 것이다.
CREATE INDEX idx_subscriptions_status      ON subscriptions (status, next_billing_date);

-- 일부러 안 건 것 —
--   audit_logs.target_type       : 쓰기가 많고 조회는 사후 조사뿐이다. 쓰기 비용을 상시로 내는 쪽이 손해다
--   blind_reviews.edited_at      : 평가용 테이블이라 행이 수백 단위다. 풀스캔이 인덱스보다 싸다
--   narrative_preferences.choice : 같은 이유
--   ledger_transactions.source_type : 어드민이 최근 50건을 보는 용도뿐이라 PK 역방향 스캔으로 충분하다
-- 인덱스는 공짜가 아니라 쓰기마다 내는 비용이다. 조회가 실제로 느려진 뒤에 걸어도 늦지 않다.
