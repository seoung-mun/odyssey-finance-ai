-- 기존 v2.5 DB를 통합 서비스 계약으로 승격한다. 빈 DB에서는 01_schema.sql 뒤에 재실행해도 안전하다.
BEGIN;

CREATE TABLE IF NOT EXISTS refresh_sessions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         INTEGER NOT NULL REFERENCES users(id),
    token_digest    VARCHAR(64) NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by_id  BIGINT REFERENCES refresh_sessions(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_refresh_session_replacement
        CHECK (replaced_by_id IS NULL OR revoked_at IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS ix_refresh_sessions_user
    ON refresh_sessions (user_id, created_at DESC);

ALTER TABLE plan_versions
    ADD COLUMN IF NOT EXISTS explanation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS explanation_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS explanation_prompt_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS explanation_failed_numbers JSONB NOT NULL DEFAULT '[]'::jsonb;
UPDATE plan_versions
   SET explanation_status = 'READY'
 WHERE explanation_text IS NOT NULL AND explanation_status = 'PENDING';
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_plan_version_explanation_status') THEN
        ALTER TABLE plan_versions ADD CONSTRAINT ck_plan_version_explanation_status
            CHECK (explanation_status IN ('PENDING','PROCESSING','READY','FALLBACK','FAILED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_plan_version_explanation_retry_count') THEN
        ALTER TABLE plan_versions ADD CONSTRAINT ck_plan_version_explanation_retry_count
            CHECK (explanation_retry_count BETWEEN 0 AND 2);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_plan_version_explanation_failed_numbers') THEN
        ALTER TABLE plan_versions ADD CONSTRAINT ck_plan_version_explanation_failed_numbers
            CHECK (jsonb_typeof(explanation_failed_numbers) = 'array');
    END IF;
END $$;

ALTER TABLE plan_options
    ADD COLUMN IF NOT EXISTS target_coverage_met BOOLEAN;
UPDATE plan_options po
   SET target_coverage_met = CASE
           WHEN po.option_type = 'CUSTOM' THEN true
           ELSE po.simulation_coverage >= po.nominal_level END
 WHERE po.target_coverage_met IS NULL;
ALTER TABLE plan_options
    ALTER COLUMN target_coverage_met SET DEFAULT false,
    ALTER COLUMN target_coverage_met SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_financial_goal_id_user') THEN
        ALTER TABLE financial_goals ADD CONSTRAINT uq_financial_goal_id_user UNIQUE (id, user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_transaction_id_user') THEN
        ALTER TABLE transactions ADD CONSTRAINT uq_transaction_id_user UNIQUE (id, user_id);
    END IF;
END $$;

ALTER TABLE replan_events
    ADD COLUMN IF NOT EXISTS user_id INTEGER,
    ADD COLUMN IF NOT EXISTS source_transaction_id BIGINT;
UPDATE replan_events re
   SET user_id = g.user_id
  FROM financial_goals g
 WHERE g.id = re.goal_id AND re.user_id IS NULL;
UPDATE replan_events
   SET source_transaction_id = (trigger_details->>'transactionId')::BIGINT
 WHERE trigger_type = 'LARGE_UNEXPECTED_TRANSACTION'
   AND source_transaction_id IS NULL
   AND trigger_details->>'transactionId' ~ '^[0-9]+$';
ALTER TABLE replan_events ALTER COLUMN user_id SET NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'replan_events_user_id_fkey') THEN
        ALTER TABLE replan_events ADD FOREIGN KEY (user_id) REFERENCES users(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_replan_goal_owner') THEN
        ALTER TABLE replan_events ADD CONSTRAINT fk_replan_goal_owner
            FOREIGN KEY (goal_id, user_id) REFERENCES financial_goals(id, user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_replan_transaction_owner') THEN
        ALTER TABLE replan_events ADD CONSTRAINT fk_replan_transaction_owner
            FOREIGN KEY (source_transaction_id, user_id) REFERENCES transactions(id, user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_replan_source_transaction_type') THEN
        ALTER TABLE replan_events ADD CONSTRAINT ck_replan_source_transaction_type
            CHECK (
                (trigger_type = 'LARGE_UNEXPECTED_TRANSACTION' AND source_transaction_id IS NOT NULL)
             OR (trigger_type <> 'LARGE_UNEXPECTED_TRANSACTION' AND source_transaction_id IS NULL)
            );
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_replan_source_transaction
    ON replan_events (source_transaction_id)
    WHERE source_transaction_id IS NOT NULL;

COMMIT;
