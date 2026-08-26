\set ON_ERROR_STOP on
SET client_min_messages TO NOTICE;

BEGIN;

INSERT INTO users (id) VALUES (9101), (9102);
INSERT INTO transactions (
    id, user_id, transaction_at, amount, transaction_type, category,
    external_transaction_id
) VALUES
    (91001, 9101, '2026-05-20T12:00:00+09:00', 100000, 'PAYMENT', 'SHOPPING', 'pay-1'),
    (91002, 9101, '2026-06-02T12:00:00+09:00',  60000, 'PAYMENT', 'SHOPPING', 'pay-2'),
    (91003, 9101, '2026-08-03T12:00:00+09:00', 120000, 'REFUND',  'SHOPPING', 'refund-1'),
    (91004, 9101, '2026-08-04T12:00:00+09:00',  30000, 'REFUND',  'SHOPPING', 'refund-2'),
    (91005, 9102, '2026-05-20T12:00:00+09:00', 100000, 'PAYMENT', 'SHOPPING', 'other-pay');

INSERT INTO refund_allocations (
    user_id, refund_transaction_id, payment_transaction_id,
    payment_external_transaction_id, amount
) VALUES
    (9101, 91003, 91001, 'pay-1', 70000),
    (9101, 91003, 91002, 'pay-2',  20000),
    (9101, 91004, 91001, 'pay-1',  30000);

DO $$
DECLARE
    may_row monthly_spending_summary%ROWTYPE;
    aug_row monthly_spending_summary%ROWTYPE;
    allocation_data_type TEXT;
BEGIN
    SELECT data_type INTO STRICT allocation_data_type
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'refund_allocations'
       AND column_name = 'amount';
    IF allocation_data_type <> 'numeric' THEN
        RAISE EXCEPTION 'refund_allocations.amount 타입 불일치: %', allocation_data_type;
    END IF;

    SELECT * INTO STRICT may_row
      FROM monthly_spending_summary
     WHERE user_id = 9101 AND year_month = DATE '2026-05-01';
    IF may_row.gross_payment_spending <> 100000
       OR may_row.linked_refund_amount <> 100000
       OR may_row.adjusted_consumption <> 0
       OR may_row.bootstrap_eligible_spending <> 0 THEN
        RAISE EXCEPTION '5월 연결 환불 집계 불일치: %', row_to_json(may_row);
    END IF;

    SELECT * INTO STRICT aug_row
      FROM monthly_spending_summary
     WHERE user_id = 9101 AND year_month = DATE '2026-08-01';
    IF aug_row.gross_payment_spending <> 0
       OR aug_row.linked_refund_amount <> 0
       OR aug_row.unmatched_refund_inflow <> 30000
       OR aug_row.adjusted_consumption <> 0
       OR aug_row.net_cash_flow <> 150000 THEN
        RAISE EXCEPTION '8월 미연결 환불 집계 불일치: %', row_to_json(aug_row);
    END IF;
END $$;

INSERT INTO transactions (
    id, user_id, transaction_at, amount, transaction_type, category,
    external_transaction_id
) VALUES
    (91006, 9101, '2026-08-05T12:00:00+09:00', 10, 'REFUND', 'SHOPPING', 'refund-3');

DO $$
BEGIN
    BEGIN
        INSERT INTO refund_allocations (
            user_id, refund_transaction_id, payment_transaction_id,
            payment_external_transaction_id, amount
        ) VALUES (9101, 91003, NULL, 'pending-pay', 40000);
        RAISE EXCEPTION '환불액 초과 배분이 통과함';
    EXCEPTION WHEN check_violation THEN
        IF SQLERRM NOT LIKE '%refund allocation exceeds refund amount%' THEN RAISE; END IF;
    END;

    BEGIN
        INSERT INTO refund_allocations (
            user_id, refund_transaction_id, payment_transaction_id,
            payment_external_transaction_id, amount
        ) VALUES (9101, 91006, 91001, 'pay-1', 1);
        RAISE EXCEPTION '결제액 초과 배분이 통과함';
    EXCEPTION WHEN check_violation THEN
        IF SQLERRM NOT LIKE '%refund allocation exceeds payment amount%' THEN RAISE; END IF;
    END;

    BEGIN
        INSERT INTO refund_allocations (
            user_id, refund_transaction_id, payment_transaction_id,
            payment_external_transaction_id, amount
        ) VALUES (9101, 91003, 91005, 'other-pay', 1);
        RAISE EXCEPTION '교차 사용자의 결제 연결이 통과함';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        UPDATE transactions SET amount = 50000 WHERE id = 91001;
        RAISE EXCEPTION '연결된 결제 원금 수정이 통과함';
    EXCEPTION WHEN check_violation THEN
        IF SQLERRM NOT LIKE '%allocated transaction identity is immutable%' THEN RAISE; END IF;
    END;

    BEGIN
        UPDATE transactions SET transaction_type = 'REFUND' WHERE id = 91001;
        RAISE EXCEPTION '연결된 결제 타입 수정이 통과함';
    EXCEPTION WHEN check_violation THEN
        IF SQLERRM NOT LIKE '%allocated transaction identity is immutable%' THEN RAISE; END IF;
    END;
END $$;

ROLLBACK;
\echo '환불 배분 검증 종료'
