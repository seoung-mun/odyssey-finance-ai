BEGIN;

CREATE OR REPLACE FUNCTION seed_demo_transactions(p_user_id INTEGER, p_tester_id VARCHAR)
RETURNS TABLE (
    out_tester_id VARCHAR,
    out_scenario_version INTEGER,
    out_inserted INTEGER,
    out_complete_months INTEGER
)
LANGUAGE plpgsql AS $$
DECLARE
    v_now              TIMESTAMPTZ := clock_timestamp();
    v_today            DATE;
    v_month_start      DATE;
    v_scenario         demo_scenarios%ROWTYPE;
    v_inserted         INTEGER;
    v_complete_months  INTEGER;
BEGIN
    v_today := timezone('Asia/Seoul', v_now)::date;
    v_month_start := date_trunc('month', v_today)::date;

    PERFORM pg_advisory_xact_lock(hashtext('seed_demo_transactions'), p_user_id);
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

    IF EXISTS (
        SELECT 1
          FROM replan_events event
          JOIN transactions tx
            ON tx.id = event.source_transaction_id
           AND tx.user_id = event.user_id
         WHERE tx.user_id = p_user_id
           AND tx.source_id = 'DEMO'
    ) OR EXISTS (
        SELECT 1
          FROM refund_allocations allocation
          JOIN transactions tx
            ON tx.id IN (
                allocation.refund_transaction_id,
                allocation.payment_transaction_id
            )
           AND tx.user_id = allocation.user_id
         WHERE tx.user_id = p_user_id
           AND tx.source_id = 'DEMO'
    ) THEN
        RAISE EXCEPTION 'DEMO_TRANSACTION_REPLACE_CONFLICT'
            USING ERRCODE = 'P0001';
    END IF;

    DELETE FROM transactions
     WHERE user_id = p_user_id
       AND source_id = 'DEMO';

    INSERT INTO transactions (
        user_id, transaction_at, amount, transaction_type, category,
        merchant_name, scheduled_expense_id, external_transaction_id,
        created_at, source_id
    )
    SELECT
        p_user_id,
        (
            (
                (v_month_start + make_interval(months => template.relative_month))::date
                + LEAST(
                    template.transaction_day,
                    extract(day FROM (
                        (v_month_start + make_interval(months => template.relative_month))::date
                        + INTERVAL '1 month - 1 day'
                    ))::INTEGER
                ) - 1
            )::date + template.transaction_time
        ) AT TIME ZONE 'Asia/Seoul',
        template.amount_krw,
        'PAYMENT',
        template.category,
        template.merchant_name,
        NULL,
        format(
            'demo:%s:%s:%s:%s',
            v_scenario.tester_id,
            v_scenario.scenario_version,
            to_char(v_today, 'YYYYMMDD'),
            template.row_index
        ),
        v_now,
        'DEMO'
      FROM demo_transaction_templates template
     WHERE template.tester_id = v_scenario.tester_id
       AND template.scenario_version = v_scenario.scenario_version
       AND (
           (
               (
                   (v_month_start + make_interval(months => template.relative_month))::date
                   + LEAST(
                       template.transaction_day,
                       extract(day FROM (
                           (v_month_start + make_interval(months => template.relative_month))::date
                           + INTERVAL '1 month - 1 day'
                       ))::INTEGER
                   ) - 1
               )::date + template.transaction_time
           ) AT TIME ZONE 'Asia/Seoul'
       ) <= v_now
     ORDER BY template.row_index;
    GET DIAGNOSTICS v_inserted = ROW_COUNT;

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

    RETURN QUERY
    SELECT
        v_scenario.tester_id,
        v_scenario.scenario_version,
        v_inserted,
        v_complete_months;
END $$;

COMMENT ON FUNCTION seed_demo_transactions(INTEGER, VARCHAR) IS
    '사용자 입력과 non-DEMO 거래는 보존하고 연령대별 DEMO 거래만 KST 기준으로 원자 교체한다.';

COMMIT;
