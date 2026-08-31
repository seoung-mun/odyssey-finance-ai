-- 정책 catalog·검색 snapshot schema 검증
-- 실행: psql -X -v ON_ERROR_STOP=1 -d <db> -f sql/08_verify_policy_catalog.sql

BEGIN;

DO $$
DECLARE
    missing_count INTEGER;
BEGIN
    SELECT count(*) INTO missing_count
      FROM unnest(ARRAY[
        'policy_sources', 'policies', 'policy_versions', 'policy_version_sources',
        'policy_chunks', 'policy_query_profiles', 'policy_index_snapshots',
        'policy_snapshot_versions', 'policy_calculation_rules', 'policy_retrieval_runs'
      ]) AS expected(name)
     WHERE to_regclass('public.' || expected.name) IS NULL;
    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'policy schema missing: % tables', missing_count;
    END IF;
END $$;

INSERT INTO policy_sources (source_key, organization, official_url, content_sha256, retrieved_at)
VALUES ('verify-source', '검증기관', 'https://example.invalid/policy', repeat('a', 64), now());

INSERT INTO policies (policy_key, title, support_goal, summary, plan_connection)
VALUES ('verify-policy', '검증 정책', 'MONTHLY_RENT', '검증용', '월 주거비와 연결');

INSERT INTO policy_versions (
    policy_id, source_version, review_status, calculation_mode, last_verified_at
)
SELECT id, '2026-verify', 'APPROVED', 'MONTHLY_EXPENSE_REDUCTION', now()
  FROM policies WHERE policy_key = 'verify-policy';

INSERT INTO policy_version_sources (
    policy_version_id, policy_source_id, source_locator, locator_sha256, is_primary
)
SELECT pv.id, ps.id, 'verify-locator', repeat('b', 64), true
  FROM policy_versions pv
  JOIN policies p ON p.id = pv.policy_id
  CROSS JOIN policy_sources ps
 WHERE p.policy_key = 'verify-policy' AND ps.source_key = 'verify-source';

INSERT INTO policy_chunks (policy_version_id, chunk_index, content, embedding, metadata)
SELECT pv.id, 0, '검증 chunk',
       (SELECT jsonb_agg(0.0 ORDER BY n) FROM generate_series(1, 1024) AS n),
       '{}'::jsonb
  FROM policy_versions pv
  JOIN policies p ON p.id = pv.policy_id
 WHERE p.policy_key = 'verify-policy';

DO $$
BEGIN
    BEGIN
        INSERT INTO policy_query_profiles (support_goal, query_text, embedding, question_flow)
        VALUES (
            'INVALID_DIMENSION', '잘못된 차원',
            (SELECT jsonb_agg(0.0 ORDER BY n) FROM generate_series(1, 1023) AS n),
            '[]'::jsonb
        );
        RAISE EXCEPTION '1023-dimensional embedding accepted';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END $$;

INSERT INTO policy_index_snapshots (
    artifact_version, manifest_sha256, embedding_model, embedding_dimension, status, activated_at
) VALUES ('verify-v1', repeat('c', 64), 'nlpai-lab/KURE-v1', 1024, 'ACTIVE', now());

DO $$
BEGIN
    BEGIN
        INSERT INTO policy_index_snapshots (
            artifact_version, manifest_sha256, embedding_model, embedding_dimension, status, activated_at
        ) VALUES ('verify-v2', repeat('d', 64), 'nlpai-lab/KURE-v1', 1024, 'ACTIVE', now());
        RAISE EXCEPTION 'multiple ACTIVE snapshots accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END $$;

INSERT INTO policy_snapshot_versions (snapshot_id, policy_version_id)
SELECT s.id, pv.id
  FROM policy_index_snapshots s
  CROSS JOIN policy_versions pv
  JOIN policies p ON p.id = pv.policy_id
 WHERE s.artifact_version = 'verify-v1' AND p.policy_key = 'verify-policy';

INSERT INTO policy_calculation_rules (
    policy_version_id, adjustment_type, amount_upper_bound, max_months,
    source_version, approved_locator, approved_sha256, golden_case,
    human_approved_at, reviewer
)
SELECT pv.id, 'MONTHLY_EXPENSE_REDUCTION', 200000, 24,
       pv.source_version, 'verify-locator', repeat('e', 64), '{}'::jsonb,
       now(), 'verify-reviewer'
  FROM policy_versions pv
  JOIN policies p ON p.id = pv.policy_id
 WHERE p.policy_key = 'verify-policy';

DO $$
BEGIN
    IF to_regclass('public.policy_scenarios') IS NOT NULL
       OR to_regclass('public.policy_awards') IS NOT NULL
       OR to_regclass('public.policy_answers') IS NOT NULL THEN
        RAISE EXCEPTION 'transient scenario input table must not exist';
    END IF;
END $$;

ROLLBACK;
