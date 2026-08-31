BEGIN;

CREATE FUNCTION valid_policy_embedding(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    item JSONB;
BEGIN
    IF jsonb_typeof(value) <> 'array' OR jsonb_array_length(value) <> 1024 THEN
        RETURN false;
    END IF;
    FOR item IN SELECT item_value FROM jsonb_array_elements(value) AS items(item_value) LOOP
        IF jsonb_typeof(item) <> 'number' THEN
            RETURN false;
        END IF;
    END LOOP;
    RETURN true;
END $$;

CREATE TABLE policy_sources (
    id             BIGSERIAL PRIMARY KEY,
    source_key     VARCHAR(100) NOT NULL UNIQUE,
    organization   VARCHAR(200) NOT NULL,
    official_url   TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    retrieved_at   TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_policy_source_key_not_blank CHECK (length(btrim(source_key)) > 0),
    CONSTRAINT ck_policy_source_organization_not_blank CHECK (length(btrim(organization)) > 0),
    CONSTRAINT ck_policy_source_url_not_blank CHECK (length(btrim(official_url)) > 0),
    CONSTRAINT ck_policy_source_sha256 CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE policies (
    id              BIGSERIAL PRIMARY KEY,
    policy_key      VARCHAR(100) NOT NULL UNIQUE,
    title           VARCHAR(200) NOT NULL,
    support_goal    VARCHAR(30) NOT NULL,
    summary         TEXT NOT NULL,
    plan_connection TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_policy_key_not_blank CHECK (length(btrim(policy_key)) > 0),
    CONSTRAINT ck_policy_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_policy_support_goal CHECK (
        support_goal IN ('MONTHLY_RENT', 'MOVE_IN_COST', 'HOUSING_STABILITY')
    )
);

CREATE TABLE policy_versions (
    id               BIGSERIAL PRIMARY KEY,
    policy_id        BIGINT NOT NULL REFERENCES policies(id),
    source_version   VARCHAR(100) NOT NULL,
    review_status    VARCHAR(20) NOT NULL,
    calculation_mode VARCHAR(40) NOT NULL,
    effective_from   DATE,
    effective_to     DATE,
    last_verified_at TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (policy_id, source_version),
    CONSTRAINT ck_policy_version_source_not_blank CHECK (length(btrim(source_version)) > 0),
    CONSTRAINT ck_policy_version_review_status CHECK (
        review_status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')
    ),
    CONSTRAINT ck_policy_version_calculation_mode CHECK (
        calculation_mode IN (
            'INFORMATIONAL', 'ELIGIBILITY_ONLY',
            'ONE_TIME_FUNDING', 'MONTHLY_EXPENSE_REDUCTION'
        )
    ),
    CONSTRAINT ck_policy_version_effective_period CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from
    )
);

CREATE INDEX ix_policy_versions_policy_verified
    ON policy_versions (policy_id, last_verified_at DESC);

CREATE TABLE policy_version_sources (
    policy_version_id BIGINT NOT NULL REFERENCES policy_versions(id) ON DELETE CASCADE,
    policy_source_id  BIGINT NOT NULL REFERENCES policy_sources(id),
    source_locator    TEXT NOT NULL,
    locator_sha256    CHAR(64) NOT NULL,
    is_primary        BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (policy_version_id, policy_source_id, source_locator),
    CONSTRAINT ck_policy_version_source_locator_not_blank
        CHECK (length(btrim(source_locator)) > 0),
    CONSTRAINT ck_policy_version_source_locator_sha256
        CHECK (locator_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_policy_version_primary_source
    ON policy_version_sources (policy_version_id)
    WHERE is_primary;

CREATE TABLE policy_chunks (
    id                BIGSERIAL PRIMARY KEY,
    policy_version_id BIGINT NOT NULL REFERENCES policy_versions(id) ON DELETE CASCADE,
    chunk_index       INTEGER NOT NULL,
    content           TEXT NOT NULL,
    embedding         JSONB NOT NULL,
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (policy_version_id, chunk_index),
    CONSTRAINT ck_policy_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_policy_chunk_content_not_blank CHECK (length(btrim(content)) > 0),
    CONSTRAINT ck_policy_chunk_embedding CHECK (valid_policy_embedding(embedding)),
    CONSTRAINT ck_policy_chunk_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE TABLE policy_query_profiles (
    support_goal  VARCHAR(30) PRIMARY KEY,
    query_text    TEXT NOT NULL,
    embedding     JSONB NOT NULL,
    question_flow JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_policy_query_support_goal CHECK (
        support_goal IN ('MONTHLY_RENT', 'MOVE_IN_COST', 'HOUSING_STABILITY')
    ),
    CONSTRAINT ck_policy_query_text_not_blank CHECK (length(btrim(query_text)) > 0),
    CONSTRAINT ck_policy_query_embedding CHECK (valid_policy_embedding(embedding)),
    CONSTRAINT ck_policy_question_flow_array CHECK (jsonb_typeof(question_flow) = 'array')
);

CREATE TABLE policy_index_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    artifact_version    VARCHAR(100) NOT NULL UNIQUE,
    manifest_sha256     CHAR(64) NOT NULL UNIQUE,
    embedding_model     VARCHAR(200) NOT NULL,
    embedding_dimension INTEGER NOT NULL,
    status              VARCHAR(20) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at        TIMESTAMPTZ,
    CONSTRAINT ck_policy_snapshot_artifact_not_blank CHECK (length(btrim(artifact_version)) > 0),
    CONSTRAINT ck_policy_snapshot_manifest_sha256 CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_policy_snapshot_model_not_blank CHECK (length(btrim(embedding_model)) > 0),
    CONSTRAINT ck_policy_snapshot_dimension CHECK (embedding_dimension = 1024),
    CONSTRAINT ck_policy_snapshot_status CHECK (status IN ('BUILDING', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_policy_snapshot_activation CHECK (
        (status = 'ACTIVE' AND activated_at IS NOT NULL)
        OR (status <> 'ACTIVE' AND activated_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_policy_snapshot_active
    ON policy_index_snapshots ((status))
    WHERE status = 'ACTIVE';

CREATE TABLE policy_snapshot_versions (
    snapshot_id       BIGINT NOT NULL REFERENCES policy_index_snapshots(id) ON DELETE CASCADE,
    policy_version_id BIGINT NOT NULL REFERENCES policy_versions(id),
    PRIMARY KEY (snapshot_id, policy_version_id)
);

CREATE TABLE policy_calculation_rules (
    policy_version_id  BIGINT PRIMARY KEY REFERENCES policy_versions(id) ON DELETE CASCADE,
    adjustment_type    VARCHAR(40) NOT NULL,
    amount_upper_bound NUMERIC(19,0) NOT NULL,
    max_months         SMALLINT,
    source_version     VARCHAR(100) NOT NULL,
    approved_locator   TEXT NOT NULL,
    approved_sha256    CHAR(64) NOT NULL,
    golden_case        JSONB NOT NULL,
    human_approved_at  TIMESTAMPTZ NOT NULL,
    reviewer           VARCHAR(100) NOT NULL,
    CONSTRAINT ck_policy_rule_adjustment_type CHECK (
        adjustment_type IN ('ONE_TIME_FUNDING', 'MONTHLY_EXPENSE_REDUCTION')
    ),
    CONSTRAINT ck_policy_rule_amount CHECK (
        amount_upper_bound BETWEEN 1 AND 1000000000000000
    ),
    CONSTRAINT ck_policy_rule_duration CHECK (
        (adjustment_type = 'ONE_TIME_FUNDING' AND max_months IS NULL)
        OR (adjustment_type = 'MONTHLY_EXPENSE_REDUCTION' AND max_months BETWEEN 1 AND 120)
    ),
    CONSTRAINT ck_policy_rule_source_not_blank CHECK (length(btrim(source_version)) > 0),
    CONSTRAINT ck_policy_rule_locator_not_blank CHECK (length(btrim(approved_locator)) > 0),
    CONSTRAINT ck_policy_rule_sha256 CHECK (approved_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_policy_rule_golden_object CHECK (jsonb_typeof(golden_case) = 'object'),
    CONSTRAINT ck_policy_rule_reviewer_not_blank CHECK (length(btrim(reviewer)) > 0)
);

CREATE TABLE policy_retrieval_runs (
    id                 BIGSERIAL PRIMARY KEY,
    snapshot_id        BIGINT NOT NULL REFERENCES policy_index_snapshots(id),
    support_goal       VARCHAR(30) NOT NULL,
    top_version_ids    JSONB NOT NULL,
    latency_ms         INTEGER NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_policy_retrieval_support_goal CHECK (
        support_goal IN ('MONTHLY_RENT', 'MOVE_IN_COST', 'HOUSING_STABILITY')
    ),
    CONSTRAINT ck_policy_retrieval_top_versions CHECK (jsonb_typeof(top_version_ids) = 'array'),
    CONSTRAINT ck_policy_retrieval_latency CHECK (latency_ms >= 0)
);

COMMIT;
