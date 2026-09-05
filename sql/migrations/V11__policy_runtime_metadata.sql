BEGIN;

CREATE TABLE policy_version_application_status (
    policy_version_id BIGINT PRIMARY KEY REFERENCES policy_versions(id) ON DELETE CASCADE,
    decision          VARCHAR(10) NOT NULL,
    as_of_date        DATE NOT NULL,
    current_status    VARCHAR(100) NOT NULL,
    verified_via      VARCHAR(100) NOT NULL,
    evidence_url      TEXT NOT NULL,
    verified_at       DATE NOT NULL,
    CONSTRAINT ck_policy_application_status_decision
        CHECK (decision IN ('ALLOW', 'EXCLUDE', 'RECHECK')),
    CONSTRAINT ck_policy_application_status_current_not_blank
        CHECK (length(btrim(current_status)) > 0),
    CONSTRAINT ck_policy_application_status_via_not_blank
        CHECK (length(btrim(verified_via)) > 0),
    CONSTRAINT ck_policy_application_status_url_not_blank
        CHECK (length(btrim(evidence_url)) > 0),
    CONSTRAINT ck_policy_application_status_verified_date
        CHECK (verified_at <= as_of_date)
);

CREATE INDEX ix_policy_application_status_decision
    ON policy_version_application_status (decision, policy_version_id);

CREATE TABLE policy_version_eligibility (
    policy_version_id BIGINT PRIMARY KEY REFERENCES policy_versions(id) ON DELETE CASCADE,
    region_scope      VARCHAR(10) NOT NULL,
    age_min           SMALLINT,
    age_max           SMALLINT,
    CONSTRAINT ck_policy_eligibility_region_scope
        CHECK (region_scope IN ('NATIONAL', 'LOCAL')),
    CONSTRAINT ck_policy_eligibility_age_pair
        CHECK ((age_min IS NULL) = (age_max IS NULL)),
    CONSTRAINT ck_policy_eligibility_age_min
        CHECK (age_min IS NULL OR age_min BETWEEN 0 AND 120),
    CONSTRAINT ck_policy_eligibility_age_max
        CHECK (age_max IS NULL OR age_max BETWEEN 0 AND 120),
    CONSTRAINT ck_policy_eligibility_age_order
        CHECK (age_min IS NULL OR age_min <= age_max)
);

CREATE TABLE policy_version_regions (
    policy_version_id BIGINT NOT NULL REFERENCES policy_version_eligibility(policy_version_id)
        ON DELETE CASCADE,
    region_code       CHAR(5) NOT NULL,
    PRIMARY KEY (policy_version_id, region_code),
    CONSTRAINT ck_policy_version_region_code CHECK (region_code ~ '^[0-9]{5}$')
);

CREATE INDEX ix_policy_version_regions_code
    ON policy_version_regions (region_code, policy_version_id);

COMMIT;
