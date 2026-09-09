-- 심사 초안 블라인드 비교용 표본을 심는다 (27 문서 7절).
--
-- 왜 SQL 인가: tools/seed-blind-review.sql 과 같은 이유다. 여기서 필요한 것은 <심사자가 보는
-- 사실>이 규칙별로 다른 12건이지 탐지 규칙의 검증이 아니다. 규칙 검증은 단위 테스트가 한다.
--
-- <카드를 겹쳐 심는다.> 열두 건이 전부 다른 카드면 "같은 카드의 지난 심사"가 늘 비어서
-- 초안이 나르는 사실 하나가 통째로 죽는다. 그러면 모델과 템플릿이 같은 빈칸을 놓고 겨루게 된다.
-- card_key 는 V46 이 붙인 카드 지문과 같은 자리에 들어가는 값이라 여기서도 카드 단위로 준다.
--
-- 다시 돌려도 안전하다. 같은 주문번호를 지우고 다시 넣는다.

DELETE h FROM payment_history h JOIN payments p ON p.id = h.payment_id WHERE p.order_no LIKE 'FR-%';
DELETE FROM fraud_draft_reviews WHERE fraud_review_id IN
    (SELECT id FROM fraud_reviews WHERE order_no LIKE 'FR-%');
DELETE FROM fraud_reviews WHERE order_no LIKE 'FR-%';
DELETE FROM payments      WHERE order_no LIKE 'FR-%';

SET @t0 = DATE_SUB(NOW(), INTERVAL 3 DAY);

-- 카드 넷에 결제 열둘. 카드 A 가 다섯 건이라 뒤로 갈수록 "지난 심사"가 쌓인다.
INSERT INTO payments (order_no, payment_key, card_fingerprint, amount, balance_amount, status, method,
                      created_at, updated_at, approved_at)
VALUES
 ('FR-01','pk-fr-01', SHA2('FAKE|card-A',256), 980000, 980000,'DONE','CARD',@t0,@t0,@t0),
 ('FR-02','pk-fr-02', SHA2('FAKE|card-A',256), 970000, 970000,'DONE','CARD',@t0,@t0,@t0),
 ('FR-03','pk-fr-03', SHA2('FAKE|card-A',256), 995000, 995000,'DONE','CARD',@t0,@t0,@t0),
 ('FR-04','pk-fr-04', SHA2('FAKE|card-B',256),    300,    300,'DONE','CARD',@t0,@t0,@t0),
 ('FR-05','pk-fr-05', SHA2('FAKE|card-B',256),    500,    500,'DONE','CARD',@t0,@t0,@t0),
 ('FR-06','pk-fr-06', SHA2('FAKE|card-B',256), 840000, 840000,'DONE','CARD',@t0,@t0,@t0),
 ('FR-07','pk-fr-07', SHA2('FAKE|card-C',256), 120000, 120000,'DONE','CARD',@t0,@t0,@t0),
 ('FR-08','pk-fr-08', SHA2('FAKE|card-C',256), 460000, 460000,'DONE','CARD',@t0,@t0,@t0),
 ('FR-09','pk-fr-09', SHA2('FAKE|card-C',256), 910000, 910000,'DONE','CARD',@t0,@t0,@t0),
 ('FR-10','pk-fr-10', SHA2('FAKE|card-D',256), 999000, 999000,'DONE','CARD',@t0,@t0,@t0),
 ('FR-11','pk-fr-11', SHA2('FAKE|card-A',256), 640000, 640000,'DONE','CARD',@t0,@t0,@t0),
 ('FR-12','pk-fr-12', SHA2('FAKE|card-A',256), 205000, 205000,'DONE','CARD',@t0,@t0,@t0);

-- 심사 큐. decision 은 REVIEW/BLOCK 만 큐에 들어간다(ALLOW/CHALLENGE 는 제외).
-- reasons 는 화면과 초안이 규칙 이름을 뽑는 자리다. 괄호 안 값까지 같은 모양으로 넣는다.
INSERT INTO fraud_reviews (order_no, payment_id, card_key, amount, score, decision, status,
                           reasons, model_risk, created_at)
SELECT p.order_no, p.id, p.card_fingerprint, p.amount, s.score, s.decision, 'PENDING',
       s.reasons, s.risk, @t0
FROM payments p
JOIN (
  SELECT 'FR-01' AS ono, 62 AS score,'REVIEW' AS decision,'NEAR_THRESHOLD(980000/1000000)' AS reasons, 0.61 AS risk UNION ALL
  SELECT 'FR-02', 66,'REVIEW','NEAR_THRESHOLD(970000/1000000)', 0.64 UNION ALL
  SELECT 'FR-03', 71,'REVIEW','NEAR_THRESHOLD(995000/1000000), VELOCITY_EXCEEDED(3/1m)', 0.72 UNION ALL
  SELECT 'FR-04', 55,'REVIEW','MICRO_PROBE(300)', 0.48 UNION ALL
  SELECT 'FR-05', 57,'REVIEW','MICRO_PROBE(500)', 0.51 UNION ALL
  SELECT 'FR-06', 74,'BLOCK','MICRO_PROBE(500), HIGH_AMOUNT(840000)', 0.79 UNION ALL
  SELECT 'FR-07', 51,'REVIEW','VELOCITY_EXCEEDED(4/1m)', 0.39 UNION ALL
  SELECT 'FR-08', 58,'REVIEW','VELOCITY_EXCEEDED(5/1m)', 0.44 UNION ALL
  SELECT 'FR-09', 69,'REVIEW','VELOCITY_EXCEEDED(6/1m), NEAR_THRESHOLD(910000/1000000)', 0.68 UNION ALL
  SELECT 'FR-10', 83,'BLOCK','NEAR_THRESHOLD(999000/1000000), DEVICE_CHURN(5)', 0.88 UNION ALL
  SELECT 'FR-11', 53,'REVIEW','DEVICE_CHURN(5)', 0.41 UNION ALL
  SELECT 'FR-12', 60,'REVIEW','DEVICE_CHURN(6), MICRO_PROBE(300)', 0.55
) s ON s.ono = p.order_no
WHERE p.order_no LIKE 'FR-%';

SELECT CONCAT('심사 큐 ', COUNT(*), '건 · 카드 ', COUNT(DISTINCT card_key), '장') AS seeded
FROM fraud_reviews WHERE order_no LIKE 'FR-%';
