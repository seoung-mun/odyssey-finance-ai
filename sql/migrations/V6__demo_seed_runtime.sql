BEGIN;

CREATE TABLE demo_scenarios (
    tester_id                    VARCHAR(50) NOT NULL,
    scenario_version             INTEGER NOT NULL,
    display_name                 VARCHAR(100) NOT NULL,
    description                  VARCHAR(300) NOT NULL,
    age_group                    VARCHAR(30) NOT NULL,
    birth_years_ago              SMALLINT NOT NULL,
    region_code                  CHAR(5) NOT NULL,
    monthly_income               NUMERIC(19,0) NOT NULL,
    monthly_fixed_cost           NUMERIC(19,0) NOT NULL,
    goal_name                    VARCHAR(100) NOT NULL,
    goal_target_amount           NUMERIC(19,0) NOT NULL,
    goal_current_saved_amount    NUMERIC(19,0) NOT NULL DEFAULT 0,
    goal_months                  SMALLINT NOT NULL,
    scheduled_expenses           JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_spending_budget_ratio NUMERIC(5,2) NOT NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tester_id, scenario_version),
    CONSTRAINT ck_demo_scenario_version CHECK (scenario_version >= 1),
    CONSTRAINT ck_demo_scenario_birth_years CHECK (birth_years_ago BETWEEN 14 AND 100),
    CONSTRAINT ck_demo_scenario_region_code CHECK (region_code ~ '^[0-9]{5}$'),
    CONSTRAINT ck_demo_scenario_amounts CHECK (
        monthly_income >= 0
        AND monthly_fixed_cost >= 0
        AND goal_target_amount > 0
        AND goal_current_saved_amount >= 0
    ),
    CONSTRAINT ck_demo_scenario_goal_months CHECK (goal_months >= 1),
    CONSTRAINT ck_demo_scenario_expenses_array
        CHECK (jsonb_typeof(scheduled_expenses) = 'array'),
    CONSTRAINT ck_demo_scenario_target_ratio CHECK (target_spending_budget_ratio > 0)
);

CREATE TABLE demo_transaction_templates (
    tester_id             VARCHAR(50) NOT NULL,
    scenario_version      INTEGER NOT NULL,
    row_index             INTEGER NOT NULL,
    relative_month        SMALLINT NOT NULL,
    transaction_day       SMALLINT NOT NULL,
    transaction_time      TIME NOT NULL,
    amount_krw            NUMERIC(19,0) NOT NULL,
    category              VARCHAR(30) NOT NULL,
    merchant_name         VARCHAR(200),
    scheduled_expense_key VARCHAR(50),
    PRIMARY KEY (tester_id, scenario_version, row_index),
    CONSTRAINT fk_demo_template_scenario
        FOREIGN KEY (tester_id, scenario_version)
        REFERENCES demo_scenarios (tester_id, scenario_version),
    CONSTRAINT ck_demo_template_row_index CHECK (row_index >= 1),
    CONSTRAINT ck_demo_template_relative_month CHECK (relative_month BETWEEN -24 AND 0),
    CONSTRAINT ck_demo_template_day CHECK (transaction_day BETWEEN 1 AND 31),
    CONSTRAINT ck_demo_template_amount CHECK (amount_krw > 0),
    CONSTRAINT ck_demo_template_variable_category
        CHECK (category IN ('food','transport','shopping','health','leisure','other'))
);

CREATE TABLE demo_seed_state (
    user_id          INTEGER PRIMARY KEY REFERENCES users(id),
    tester_id        VARCHAR(50) NOT NULL,
    scenario_version INTEGER NOT NULL,
    seeded_on        DATE NOT NULL,
    seeded_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_demo_seed_scenario
        FOREIGN KEY (tester_id, scenario_version)
        REFERENCES demo_scenarios (tester_id, scenario_version)
);

CREATE OR REPLACE FUNCTION seed_demo_user(p_user_id INTEGER, p_tester_id VARCHAR)
RETURNS TABLE (
    out_tester_id VARCHAR,
    out_scenario_version INTEGER,
    out_seeded_at TIMESTAMPTZ
)
LANGUAGE plpgsql AS $$
DECLARE
    v_now              TIMESTAMPTZ := clock_timestamp();
    v_today            DATE;
    v_month_start      DATE;
    v_scenario         demo_scenarios%ROWTYPE;
    v_previous_state   demo_seed_state%ROWTYPE;
    v_has_state        BOOLEAN := false;
    v_seeded_at        TIMESTAMPTZ;
    v_expense          JSONB;
    v_expense_key      TEXT;
    v_expense_month    DATE;
    v_expense_date     DATE;
    v_expense_id       INTEGER;
    v_expense_ids      JSONB := '{}'::jsonb;
    v_complete_months  INTEGER;
BEGIN
    v_today := timezone('Asia/Seoul', v_now)::date;
    v_month_start := date_trunc('month', v_today)::date;

    PERFORM pg_advisory_xact_lock(hashtext('seed_demo_user'), p_user_id);
    PERFORM id FROM users WHERE id = p_user_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'DEMO_USER_NOT_FOUND'
            USING ERRCODE = 'P0002';
    END IF;

    SELECT * INTO v_scenario
      FROM demo_scenarios
     WHERE tester_id = p_tester_id
     ORDER BY scenario_version DESC
     LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'DEMO_TESTER_NOT_FOUND'
            USING ERRCODE = 'P0002';
    END IF;

    SELECT * INTO v_previous_state
      FROM demo_seed_state
     WHERE user_id = p_user_id
     FOR UPDATE;
    v_has_state := FOUND;

    IF NOT v_has_state AND (
        EXISTS (SELECT 1 FROM user_profiles WHERE user_id = p_user_id)
        OR EXISTS (SELECT 1 FROM financial_profiles WHERE user_id = p_user_id)
        OR EXISTS (SELECT 1 FROM financial_goals WHERE user_id = p_user_id)
        OR EXISTS (SELECT 1 FROM scheduled_expenses WHERE user_id = p_user_id)
        OR EXISTS (SELECT 1 FROM transactions WHERE user_id = p_user_id)
    ) THEN
        RAISE EXCEPTION 'DEMO_SEED_CONFLICT'
            USING ERRCODE = 'P0001';
    END IF;

    IF v_has_state THEN
        DELETE FROM plan_option_percentile_bands b
         USING plan_options po, plan_versions pv, financial_goals g
         WHERE b.plan_option_id = po.id
           AND po.plan_version_id = pv.id
           AND pv.goal_id = g.id
           AND g.user_id = p_user_id;

        DELETE FROM plan_options po
         USING plan_versions pv, financial_goals g
         WHERE po.plan_version_id = pv.id
           AND pv.goal_id = g.id
           AND g.user_id = p_user_id;

        DELETE FROM replan_events WHERE user_id = p_user_id;

        DELETE FROM simulation_runs sr
         USING plan_versions pv, financial_goals g
         WHERE sr.plan_version_id = pv.id
           AND pv.goal_id = g.id
           AND g.user_id = p_user_id;

        DELETE FROM plan_versions pv
         USING financial_goals g
         WHERE pv.goal_id = g.id
           AND g.user_id = p_user_id;

        DELETE FROM refund_allocations WHERE user_id = p_user_id;
        DELETE FROM transactions WHERE user_id = p_user_id;
        DELETE FROM scheduled_expenses WHERE user_id = p_user_id;
        DELETE FROM financial_goals WHERE user_id = p_user_id;
        DELETE FROM financial_profiles WHERE user_id = p_user_id;
        DELETE FROM user_profiles WHERE user_id = p_user_id;
        DELETE FROM demo_seed_state WHERE user_id = p_user_id;
    END IF;

    v_seeded_at := CASE
        WHEN v_has_state
         AND v_previous_state.tester_id = v_scenario.tester_id
         AND v_previous_state.scenario_version = v_scenario.scenario_version
         AND v_previous_state.seeded_on = v_today
        THEN v_previous_state.seeded_at
        ELSE v_now
    END;

    INSERT INTO user_profiles (user_id, birth_date, region_code, created_at, updated_at)
    VALUES (
        p_user_id,
        make_date(extract(year FROM v_today)::INTEGER - v_scenario.birth_years_ago, 1, 1),
        v_scenario.region_code,
        v_seeded_at,
        v_seeded_at
    );

    INSERT INTO financial_profiles (
        user_id, monthly_income, monthly_fixed_cost, updated_at
    ) VALUES (
        p_user_id,
        v_scenario.monthly_income::BIGINT,
        v_scenario.monthly_fixed_cost::BIGINT,
        v_seeded_at
    );

    INSERT INTO financial_goals (
        user_id, name, target_amount, current_saved_amount, target_date,
        status, created_at, updated_at
    ) VALUES (
        p_user_id,
        v_scenario.goal_name,
        v_scenario.goal_target_amount::BIGINT,
        v_scenario.goal_current_saved_amount::BIGINT,
        (v_today + make_interval(months => v_scenario.goal_months))::date,
        'ACTIVE',
        v_seeded_at,
        v_seeded_at
    );

    FOR v_expense IN SELECT value FROM jsonb_array_elements(v_scenario.scheduled_expenses)
    LOOP
        v_expense_key := v_expense->>'key';
        IF v_expense_key IS NULL
           OR v_expense_key = ''
           OR v_expense->>'status' NOT IN ('PLANNED','COMPLETED')
           OR v_expense_ids ? v_expense_key THEN
            RAISE EXCEPTION 'DEMO_TEMPLATE_INVALID'
                USING ERRCODE = 'P0001';
        END IF;

        v_expense_month := (
            v_month_start + make_interval(months => (v_expense->>'relativeMonth')::INTEGER)
        )::date;
        v_expense_date := v_expense_month + LEAST(
            (v_expense->>'day')::INTEGER,
            extract(day FROM (v_expense_month + INTERVAL '1 month - 1 day'))::INTEGER
        ) - 1;

        INSERT INTO scheduled_expenses (
            user_id, name, amount, scheduled_date, status, created_at, updated_at
        ) VALUES (
            p_user_id,
            v_expense->>'name',
            LEAST((v_expense->>'amount')::NUMERIC, 1000000000000000)::BIGINT,
            v_expense_date,
            v_expense->>'status',
            v_seeded_at,
            v_seeded_at
        ) RETURNING id INTO v_expense_id;

        v_expense_ids := jsonb_set(
            v_expense_ids,
            ARRAY[v_expense_key],
            to_jsonb(v_expense_id)
        );
    END LOOP;

    IF EXISTS (
        SELECT 1
          FROM demo_transaction_templates t
         WHERE t.tester_id = v_scenario.tester_id
           AND t.scenario_version = v_scenario.scenario_version
           AND t.scheduled_expense_key IS NOT NULL
           AND NOT (v_expense_ids ? t.scheduled_expense_key)
    ) THEN
        RAISE EXCEPTION 'DEMO_TEMPLATE_INVALID'
            USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO transactions (
        user_id, transaction_at, amount, transaction_type, category,
        merchant_name, scheduled_expense_id, external_transaction_id,
        created_at, source_id
    )
    SELECT
        p_user_id,
        (
            (
                (v_month_start + make_interval(months => t.relative_month))::date
                + LEAST(
                    t.transaction_day,
                    extract(day FROM (
                        (v_month_start + make_interval(months => t.relative_month))::date
                        + INTERVAL '1 month - 1 day'
                    ))::INTEGER
                ) - 1
            )::date + t.transaction_time
        ) AT TIME ZONE 'Asia/Seoul',
        LEAST(t.amount_krw, 1000000000000000)::BIGINT,
        'PAYMENT',
        t.category,
        t.merchant_name,
        CASE
            WHEN t.scheduled_expense_key IS NULL THEN NULL
            ELSE (v_expense_ids->>t.scheduled_expense_key)::INTEGER
        END,
        format(
            'demo:%s:%s:%s:%s',
            v_scenario.tester_id,
            v_scenario.scenario_version,
            to_char(v_today, 'YYYYMMDD'),
            t.row_index
        ),
        v_seeded_at,
        'DEMO'
      FROM demo_transaction_templates t
     WHERE t.tester_id = v_scenario.tester_id
       AND t.scenario_version = v_scenario.scenario_version
       AND (
           (
               (
                   (v_month_start + make_interval(months => t.relative_month))::date
                   + LEAST(
                       t.transaction_day,
                       extract(day FROM (
                           (v_month_start + make_interval(months => t.relative_month))::date
                           + INTERVAL '1 month - 1 day'
                       ))::INTEGER
                   ) - 1
               )::date + t.transaction_time
           ) AT TIME ZONE 'Asia/Seoul'
       ) <= v_now
     ORDER BY t.row_index;

    SELECT count(DISTINCT date_trunc('month', timezone('Asia/Seoul', transaction_at)))
      INTO v_complete_months
      FROM transactions
     WHERE user_id = p_user_id
       AND source_id = 'DEMO'
       AND transaction_at >= (v_month_start - INTERVAL '24 months') AT TIME ZONE 'Asia/Seoul'
       AND transaction_at < v_month_start::TIMESTAMP AT TIME ZONE 'Asia/Seoul';
    IF v_complete_months <> 24 THEN
        RAISE EXCEPTION 'DEMO_TEMPLATE_INCOMPLETE_MONTHS'
            USING ERRCODE = 'P0001';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM transactions
         WHERE user_id = p_user_id
           AND source_id = 'DEMO'
         GROUP BY date_trunc('month', timezone('Asia/Seoul', transaction_at))
        HAVING sum(amount::NUMERIC) >= 1000000000000000
    ) THEN
        RAISE EXCEPTION 'DEMO_TEMPLATE_MONTHLY_TOTAL_TOO_LARGE'
            USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO demo_seed_state (
        user_id, tester_id, scenario_version, seeded_on, seeded_at
    ) VALUES (
        p_user_id,
        v_scenario.tester_id,
        v_scenario.scenario_version,
        v_today,
        v_seeded_at
    );

    RETURN QUERY SELECT v_scenario.tester_id, v_scenario.scenario_version, v_seeded_at;
END $$;

COMMENT ON FUNCTION seed_demo_user(INTEGER, VARCHAR) IS
    '오프라인 템플릿을 KST 기준으로 이동해 재생한다. 런타임 난수와 계획 계산은 수행하지 않는다.';

COMMIT;
