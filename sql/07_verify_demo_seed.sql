\set ON_ERROR_STOP on

BEGIN;

INSERT INTO users(id) VALUES (990001), (990002), (990003);

INSERT INTO demo_scenarios (
    tester_id, scenario_version, display_name, description, age_group,
    birth_years_ago, region_code, monthly_income, monthly_fixed_cost,
    goal_name, goal_target_amount, goal_current_saved_amount, goal_months,
    scheduled_expenses, target_spending_budget_ratio
) VALUES (
    '__verify__', 1, '검증', 'V6 검증', 'TEST',
    30, '29110', 4000000, 1500000,
    '검증 목표', 10000000, 1000000, 12,
    '[
      {"key":"past","name":"과거 예정지출","amount":100000,"relativeMonth":-1,"day":10,"status":"COMPLETED"},
      {"key":"future","name":"미래 예정지출","amount":200000,"relativeMonth":1,"day":10,"status":"PLANNED"}
    ]'::jsonb,
    1.20
);

INSERT INTO demo_transaction_templates (
    tester_id, scenario_version, row_index, relative_month,
    transaction_day, transaction_time, amount_krw, category,
    merchant_name, scheduled_expense_key
)
SELECT
    '__verify__', 1, 25 + relative_month, relative_month,
    5, TIME '09:00', 100000 + relative_month + 24, 'food', '검증 상점',
    CASE WHEN relative_month = -1 THEN 'past' ELSE NULL END
  FROM generate_series(-24, 0) AS relative_month;

SELECT * FROM seed_demo_user(990001, '__verify__');

DO $$
DECLARE
    before_hash TEXT;
    after_hash TEXT;
BEGIN
    SELECT md5(string_agg(
        concat_ws('|', t.transaction_at, t.amount, t.category, t.merchant_name,
                  coalesce(se.name, ''), t.external_transaction_id),
        ',' ORDER BY external_transaction_id
    )) INTO before_hash
      FROM transactions t
      LEFT JOIN scheduled_expenses se ON se.id = t.scheduled_expense_id
     WHERE t.user_id = 990001;

    PERFORM * FROM seed_demo_user(990001, '__verify__');

    SELECT md5(string_agg(
        concat_ws('|', t.transaction_at, t.amount, t.category, t.merchant_name,
                  coalesce(se.name, ''), t.external_transaction_id),
        ',' ORDER BY external_transaction_id
    )) INTO after_hash
      FROM transactions t
      LEFT JOIN scheduled_expenses se ON se.id = t.scheduled_expense_id
     WHERE t.user_id = 990001;

    IF before_hash <> after_hash THEN
        RAISE EXCEPTION '같은 날 재시드 결과가 다름';
    END IF;
    IF (SELECT count(*) FROM plan_versions pv JOIN financial_goals g ON g.id = pv.goal_id
         WHERE g.user_id = 990001) <> 0 THEN
        RAISE EXCEPTION 'seed가 계획 결과를 생성함';
    END IF;
    IF (SELECT count(*) FROM scheduled_expenses WHERE user_id = 990001 AND status = 'PLANNED') <> 1
       OR (SELECT count(*) FROM scheduled_expenses WHERE user_id = 990001 AND status = 'COMPLETED') <> 1 THEN
        RAISE EXCEPTION '예정지출 상태가 잘못됨';
    END IF;
    IF (SELECT count(*) FROM transactions WHERE user_id = 990001 AND scheduled_expense_id IS NOT NULL) <> 1 THEN
        RAISE EXCEPTION '완료 예정지출 연결이 잘못됨';
    END IF;
END $$;

INSERT INTO user_profiles(user_id, birth_date) VALUES (990002, DATE '1990-01-01');
DO $$
BEGIN
    PERFORM * FROM seed_demo_user(990002, '__verify__');
    RAISE EXCEPTION '기존 비데모 사용자가 seed됨';
EXCEPTION
    WHEN SQLSTATE 'P0001' THEN
        IF SQLERRM <> 'DEMO_SEED_CONFLICT' THEN
            RAISE;
        END IF;
END $$;

INSERT INTO user_profiles(user_id, birth_date) VALUES (990003, DATE '1980-01-01');
DO $$
DECLARE
    foreign_hash TEXT;
BEGIN
    SELECT md5(row_to_json(p)::text) INTO foreign_hash
      FROM user_profiles p WHERE user_id = 990003;
    PERFORM * FROM seed_demo_user(990001, '__verify__');
    IF foreign_hash <> (SELECT md5(row_to_json(p)::text) FROM user_profiles p WHERE user_id = 990003) THEN
        RAISE EXCEPTION '다른 사용자 데이터가 변경됨';
    END IF;
END $$;

ROLLBACK;
