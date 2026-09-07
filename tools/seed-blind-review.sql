-- 블라인드 리뷰용 표본을 심는다 (13 문서 실험 14).
--
-- 왜 SQL 인가: 도메인 서비스로 만들면 주문·결제·대사에 취소·분쟁까지 얽혀 시나리오마다
-- 다른 경로를 타야 한다. 여기서 필요한 것은 <운영자가 보는 사실>이 유형별로 다른 12건이지
-- 도메인 규칙의 검증이 아니다. 규칙 검증은 이미 단위 테스트가 한다.
-- `tools/bench.sh` 가 부하 표본을 심는 방식과 같다.
--
-- 유형은 평가 사례 12건(DraftPromptLayoutEvalTest)의 모양을 따른다. 같은 모양이라야
-- 자동 지표에서 본 것과 사람이 보는 것이 어긋나지 않는다.
--
-- 다시 돌려도 안전하다. 같은 주문번호를 지우고 다시 넣는다.

SET @d0 = DATE_SUB(CURDATE(), INTERVAL 6 DAY);

-- 지우는 순서가 외래키 순서다. payment_history 가 payments 를 잡고 있어서
-- 결제부터 지우면 제약에 걸린다. 자식부터 지운다.
DELETE h FROM payment_history h JOIN payments p ON p.id = h.payment_id WHERE p.order_no LIKE 'BR-%';
DELETE FROM blind_reviews          WHERE order_no LIKE 'BR-%';
DELETE FROM reconciliation_results WHERE order_no LIKE 'BR-%';
DELETE FROM payments               WHERE order_no LIKE 'BR-%';
DELETE FROM orders                 WHERE order_no LIKE 'BR-%';

-- 주문 12건. 금액은 평가 사례와 같은 값을 쓴다.
INSERT INTO orders (user_id, order_no, status, total_amount, currency, version, created_at, updated_at, expires_at) VALUES
 (1,'BR-01-CANCEL-FULL',   'CANCELED',  24000,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE)),
 (1,'BR-02-CANCEL-PARTIAL','PAID',      50000,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE)),
 (1,'BR-03-UNKNOWN',       'PAYMENT_IN_PROGRESS', 31500,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE)),
 (1,'BR-04-VIRTUAL-ACC',   'PENDING_PAYMENT', 70000,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 3 DAY)),
 (1,'BR-05-RECON-DIFF',    'PAID',      19800,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE)),
 (1,'BR-06-NO-INTERNAL',   'PAID',      45000,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE)),
 (1,'BR-07-PG-DELAY',      'PAID',      15000,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE)),
 (1,'BR-08-SUBSCRIPTION',  'FAILED',     9900,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE)),
 (1,'BR-09-DISPUTE',       'PAID',     120000,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE)),
 (1,'BR-10-REFUNDING',     'CANCELED',  33000,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE)),
 (1,'BR-11-THIN',          'CREATED',    6500,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE)),
 (1,'BR-12-DOUBLE-CHARGE', 'PAID',      27000,'KRW',0, @d0, @d0, DATE_ADD(@d0, INTERVAL 30 MINUTE));

-- 결제. 11번은 일부러 안 넣는다 — <타임라인이 불완전한 건>이 표본에 있어야 한다.
INSERT INTO payments (order_no, payment_key, status, method, pg_provider, amount, balance_amount,
                      installment_months, cancel_count, version, requested_at, approved_at, unknown_reason) VALUES
 ('BR-01-CANCEL-FULL',   'pk-br-01','CANCELED',        '카드','TOSS', 24000,      0,0,1,0, @d0, DATE_ADD(@d0, INTERVAL 1 MINUTE), NULL),
 ('BR-02-CANCEL-PARTIAL','pk-br-02','PARTIAL_CANCELED','카드','TOSS', 50000,  30000,0,2,0, @d0, DATE_ADD(@d0, INTERVAL 1 MINUTE), NULL),
 ('BR-03-UNKNOWN',       'pk-br-03','UNKNOWN',         '카드','TOSS', 31500,  31500,0,0,0, @d0, NULL, '승인 응답 없음(타임아웃)'),
 ('BR-04-VIRTUAL-ACC',   'pk-br-04','READY',           '가상계좌','TOSS', 70000, 70000,0,0,0, @d0, NULL, NULL),
 ('BR-05-RECON-DIFF',    'pk-br-05','DONE',            '카드','TOSS', 19800,  19800,0,0,0, @d0, DATE_ADD(@d0, INTERVAL 1 MINUTE), NULL),
 ('BR-07-PG-DELAY',      'pk-br-07','DONE',            '카드','TOSS', 15000,  15000,0,0,0, @d0, DATE_ADD(@d0, INTERVAL 1 MINUTE), NULL),
 ('BR-08-SUBSCRIPTION',  'pk-br-08','ABORTED',         '카드','TOSS',  9900,   9900,0,0,0, @d0, NULL, '정기결제 승인 거절(한도 초과)'),
 ('BR-09-DISPUTE',       'pk-br-09','DONE',            '카드','TOSS',120000, 120000,0,0,0, @d0, DATE_ADD(@d0, INTERVAL 1 MINUTE), NULL),
 ('BR-10-REFUNDING',     'pk-br-10','CANCELED',        '카드','TOSS', 33000,      0,0,1,0, @d0, DATE_ADD(@d0, INTERVAL 1 MINUTE), NULL),
 ('BR-12-DOUBLE-CHARGE', 'pk-br-12','DONE',            '카드','TOSS', 27000,  27000,0,0,0, @d0, DATE_ADD(@d0, INTERVAL 1 MINUTE), NULL);

-- 대사 결과. 전부 PENDING 이라야 리뷰 대상이 된다.
-- 유형을 섞는다. 한 유형만 있으면 편집률이 그 유형의 값이 되지 사람이 쓰는 값이 아니다.
INSERT INTO reconciliation_results (trade_date, order_no, result, status, internal_amount, external_amount, reconciled_at) VALUES
 (@d0,'BR-01-CANCEL-FULL',   'AMOUNT_MISMATCH','PENDING',     0, 24000, DATE_ADD(@d0, INTERVAL 2 DAY)),
 (@d0,'BR-02-CANCEL-PARTIAL','AMOUNT_MISMATCH','PENDING', 30000, 50000, DATE_ADD(@d0, INTERVAL 2 DAY)),
 (@d0,'BR-03-UNKNOWN',       'EXTERNAL_ONLY',  'PENDING',  NULL, 31500, DATE_ADD(@d0, INTERVAL 2 DAY)),
 (@d0,'BR-04-VIRTUAL-ACC',   'INTERNAL_ONLY',  'PENDING', 70000,  NULL, DATE_ADD(@d0, INTERVAL 2 DAY)),
 (@d0,'BR-05-RECON-DIFF',    'AMOUNT_MISMATCH','PENDING', 19800, 19500, DATE_ADD(@d0, INTERVAL 2 DAY)),
 (@d0,'BR-06-NO-INTERNAL',   'EXTERNAL_ONLY',  'PENDING',  NULL, 45000, DATE_ADD(@d0, INTERVAL 2 DAY)),
 (@d0,'BR-07-PG-DELAY',      'INTERNAL_ONLY',  'PENDING', 15000,  NULL, DATE_ADD(@d0, INTERVAL 3 DAY)),
 (@d0,'BR-08-SUBSCRIPTION',  'INTERNAL_ONLY',  'PENDING',  9900,  NULL, DATE_ADD(@d0, INTERVAL 2 DAY)),
 (@d0,'BR-09-DISPUTE',       'AMOUNT_MISMATCH','PENDING',120000,119000, DATE_ADD(@d0, INTERVAL 2 DAY)),
 (@d0,'BR-10-REFUNDING',     'AMOUNT_MISMATCH','PENDING',     0, 33000, DATE_ADD(@d0, INTERVAL 2 DAY)),
 (@d0,'BR-11-THIN',          'EXTERNAL_ONLY',  'PENDING',  NULL,  6500, DATE_ADD(@d0, INTERVAL 2 DAY)),
 (@d0,'BR-12-DOUBLE-CHARGE', 'AMOUNT_MISMATCH','PENDING', 27000, 54000, DATE_ADD(@d0, INTERVAL 2 DAY));

SELECT COUNT(*) AS '심은 대사 건' FROM reconciliation_results WHERE order_no LIKE 'BR-%' AND status='PENDING';

-- 결제 전이 이력. 타임라인이 <언제 무엇에서 무엇으로 바뀌었는가>를 여기서만 읽는다.
-- 이걸 빼면 사실이 "주문 생성"과 "대사 결과" 둘로 얇아져서, 사람이 쓰는 답도
-- 초안도 같이 빈약해진다. 그러면 편집률이 초안의 값이 아니라 표본의 값이 된다.
INSERT INTO payment_history (payment_id, from_status, to_status, triggered_by, reason, created_at)
SELECT p.id, s.f, s.t, s.who, s.why, DATE_ADD(@d0, INTERVAL s.mins MINUTE)
FROM payments p JOIN (
  SELECT 'BR-01-CANCEL-FULL'    o,'READY' f,'IN_PROGRESS' t,'USER'    who, NULL                       why,  0 mins UNION ALL
  SELECT 'BR-01-CANCEL-FULL'     ,'IN_PROGRESS','DONE'      ,'WEBHOOK'  , NULL                          ,  1 UNION ALL
  SELECT 'BR-01-CANCEL-FULL'     ,'DONE'       ,'CANCELED'  ,'USER'     , '고객 요청 전액취소'           , 2880 UNION ALL
  SELECT 'BR-02-CANCEL-PARTIAL'  ,'READY'      ,'IN_PROGRESS','USER'    , NULL                          ,  0 UNION ALL
  SELECT 'BR-02-CANCEL-PARTIAL'  ,'IN_PROGRESS','DONE'      ,'WEBHOOK'  , NULL                          ,  1 UNION ALL
  SELECT 'BR-02-CANCEL-PARTIAL'  ,'DONE'       ,'PARTIAL_CANCELED','USER', '부분취소 12,000원'          , 2880 UNION ALL
  SELECT 'BR-02-CANCEL-PARTIAL'  ,'PARTIAL_CANCELED','PARTIAL_CANCELED','USER','부분취소 8,000원'       , 4320 UNION ALL
  SELECT 'BR-03-UNKNOWN'         ,'READY'      ,'IN_PROGRESS','USER'    , NULL                          ,  0 UNION ALL
  SELECT 'BR-03-UNKNOWN'         ,'IN_PROGRESS','UNKNOWN'   ,'POLLING'  , '승인 응답 없음(타임아웃)'     ,  1 UNION ALL
  SELECT 'BR-04-VIRTUAL-ACC'     ,'READY'      ,'READY'     ,'USER'     , '가상계좌 발급, 입금 대기'     ,  0 UNION ALL
  SELECT 'BR-05-RECON-DIFF'      ,'READY'      ,'IN_PROGRESS','USER'    , NULL                          ,  0 UNION ALL
  SELECT 'BR-05-RECON-DIFF'      ,'IN_PROGRESS','DONE'      ,'WEBHOOK'  , NULL                          ,  1 UNION ALL
  SELECT 'BR-07-PG-DELAY'        ,'READY'      ,'IN_PROGRESS','USER'    , NULL                          ,  0 UNION ALL
  SELECT 'BR-07-PG-DELAY'        ,'IN_PROGRESS','DONE'      ,'WEBHOOK'  , NULL                          ,  1 UNION ALL
  SELECT 'BR-08-SUBSCRIPTION'    ,'READY'      ,'IN_PROGRESS','RECOVERY_BATCH', '정기결제 청구'          ,  0 UNION ALL
  SELECT 'BR-08-SUBSCRIPTION'    ,'IN_PROGRESS','ABORTED'   ,'WEBHOOK'  , '승인 거절(한도 초과)'         ,  1 UNION ALL
  SELECT 'BR-08-SUBSCRIPTION'    ,'ABORTED'    ,'ABORTED'   ,'RECOVERY_BATCH','재시도 실패(한도 초과)'   , 1440 UNION ALL
  SELECT 'BR-09-DISPUTE'         ,'READY'      ,'IN_PROGRESS','USER'    , NULL                          ,  0 UNION ALL
  SELECT 'BR-09-DISPUTE'         ,'IN_PROGRESS','DONE'      ,'WEBHOOK'  , NULL                          ,  1 UNION ALL
  SELECT 'BR-10-REFUNDING'       ,'READY'      ,'IN_PROGRESS','USER'    , NULL                          ,  0 UNION ALL
  SELECT 'BR-10-REFUNDING'       ,'IN_PROGRESS','DONE'      ,'WEBHOOK'  , NULL                          ,  1 UNION ALL
  SELECT 'BR-10-REFUNDING'       ,'DONE'       ,'CANCELED'  ,'USER'     , '전액취소, 환불 요청 전달'     , 1440 UNION ALL
  SELECT 'BR-12-DOUBLE-CHARGE'   ,'READY'      ,'IN_PROGRESS','USER'    , NULL                          ,  0 UNION ALL
  SELECT 'BR-12-DOUBLE-CHARGE'   ,'IN_PROGRESS','DONE'      ,'WEBHOOK'  , NULL                          ,  1
) s ON s.o = p.order_no;

SELECT COUNT(*) AS '심은 결제 전이' FROM payment_history h JOIN payments p ON p.id=h.payment_id WHERE p.order_no LIKE 'BR-%';
