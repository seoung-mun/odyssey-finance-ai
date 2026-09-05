\set ON_ERROR_STOP on

BEGIN;
SET LOCAL TIME ZONE 'Asia/Seoul';

INSERT INTO users(id) VALUES (991001);

INSERT INTO demo_scenarios (
    tester_id, scenario_version, display_name, description, age_group,
    birth_years_ago, region_code, monthly_income, monthly_fixed_cost,
    goal_name, goal_target_amount, goal_current_saved_amount, goal_months,
    scheduled_expenses, target_spending_budget_ratio
) VALUES (
    '__verify_transactions__', 1, '거래 전용 검증', 'V10 검증', 'TEST',
    30, '11680', 4000000, 1500000,
    '사용자 목표', 10000000, 1000000, 12,
    '[]'::jsonb,
    1.20
);

INSERT INTO demo_transaction_templates (
    tester_id, scenario_version, row_index, relative_month,
    transaction_day, transaction_time, amount_krw, category,
    merchant_name, scheduled_expense_key
)
SELECT
    '__verify_transactions__', 1, 25 + relative_month, relative_month,
    CASE WHEN relative_month = 0 THEN 1 ELSE 5 END,
    TIME '09:00', 100000 + relative_month + 24, 'food', '검증 상점',
    CASE WHEN relative_month = -1 THEN '사용자 예정지출과 연결하면 안 됨' ELSE NULL END
  FROM generate_series(-24, 0) AS relative_month;

INSERT INTO user_profiles(user_id, birth_date, region_code)
VALUES (991001, DATE '1990-05-10', '11680');
INSERT INTO financial_profiles(user_id, monthly_income, monthly_fixed_cost)
VALUES (991001, 5000000, 1200000);
INSERT INTO financial_goals(
    user_id, name, target_amount, current_saved_amount, target_date, status
) VALUES (
    991001, '직접 입력 목표', 50000000, 10000000, current_date + 365, 'ACTIVE'
);
INSERT INTO scheduled_expenses(user_id, name, amount, scheduled_date, status)
VALUES (991001, '사용자 예정지출', 700000, current_date + 30, 'PLANNED');
INSERT INTO transactions(
    user_id, transaction_at, amount, transaction_type, category,
    source_id, external_transaction_id
) VALUES (
    991001, now() - INTERVAL '2 days', 50000, 'PAYMENT', 'other',
    'MANUAL', 'manual-preserved'
);

SELECT * FROM seed_demo_transactions(991001, '__verify_transactions__');

DO $$
DECLARE
    profile_hash   TEXT;
    financial_hash TEXT;
    goal_hash      TEXT;
    expense_hash   TEXT;
BEGIN
    SELECT md5(row_to_json(value)::text) INTO profile_hash
      FROM user_profiles value WHERE user_id = 991001;
    SELECT md5(row_to_json(value)::text) INTO financial_hash
      FROM financial_profiles value WHERE user_id = 991001;
    SELECT md5(row_to_json(value)::text) INTO goal_hash
      FROM financial_goals value WHERE user_id = 991001;
    SELECT md5(row_to_json(value)::text) INTO expense_hash
      FROM scheduled_expenses value WHERE user_id = 991001;

    PERFORM * FROM seed_demo_transactions(991001, '__verify_transactions__');

    IF profile_hash <> (SELECT md5(row_to_json(value)::text) FROM user_profiles value WHERE user_id = 991001)
       OR financial_hash <> (SELECT md5(row_to_json(value)::text) FROM financial_profiles value WHERE user_id = 991001)
       OR goal_hash <> (SELECT md5(row_to_json(value)::text) FROM financial_goals value WHERE user_id = 991001)
       OR expense_hash <> (SELECT md5(row_to_json(value)::text) FROM scheduled_expenses value WHERE user_id = 991001) THEN
        RAISE EXCEPTION '직접 입력 사용자 데이터가 변경됨';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM transactions
         WHERE user_id = 991001
           AND source_id = 'MANUAL'
           AND external_transaction_id = 'manual-preserved'
    ) THEN
        RAISE EXCEPTION 'non-DEMO 거래가 삭제됨';
    END IF;
    IF (SELECT count(*) FROM transactions WHERE user_id = 991001 AND source_id = 'DEMO') <> 25 THEN
        RAISE EXCEPTION '재호출 뒤 DEMO 거래 수가 배증하거나 누락됨';
    END IF;
    IF EXISTS (
        SELECT 1 FROM transactions
         WHERE user_id = 991001
           AND source_id = 'DEMO'
           AND scheduled_expense_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'DEMO 거래가 사용자 예정지출에 연결됨';
    END IF;
    IF (
        SELECT count(DISTINCT date_trunc('month', timezone('Asia/Seoul', transaction_at)))
          FROM transactions
         WHERE user_id = 991001
           AND source_id = 'DEMO'
           AND transaction_at >= date_trunc('month', current_date) - INTERVAL '24 months'
           AND transaction_at < date_trunc('month', current_date)
    ) <> 24 THEN
        RAISE EXCEPTION '완료된 과거 거래 월이 24개가 아님';
    END IF;
END $$;

ROLLBACK;
