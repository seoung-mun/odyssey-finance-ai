BEGIN;

ALTER FUNCTION seed_demo_user(INTEGER, VARCHAR) RENAME TO seed_demo_user_v8;

CREATE FUNCTION seed_demo_user(p_user_id INTEGER, p_tester_id VARCHAR)
RETURNS TABLE (
    out_tester_id       VARCHAR,
    out_scenario_version INTEGER,
    out_seeded_at       TIMESTAMPTZ
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM seed_demo_user_v8(p_user_id, p_tester_id);

    UPDATE financial_goals
       SET target_date = (
               date_trunc('month', target_date)::date - INTERVAL '1 day'
           )::date
     WHERE user_id = p_user_id
       AND status = 'ACTIVE';
END $$;

COMMENT ON FUNCTION seed_demo_user(INTEGER, VARCHAR) IS
    '오프라인 템플릿 재생 뒤 데모 목표를 18개 포함 월의 말일로 보정한다.';

COMMIT;
