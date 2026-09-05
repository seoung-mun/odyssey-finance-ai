BEGIN;

CREATE TABLE policy_benefits (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            INTEGER NOT NULL REFERENCES users(id),
    goal_id            INTEGER NOT NULL,
    policy_version_id  BIGINT NOT NULL REFERENCES policy_versions(id),
    adjustment_type    VARCHAR(40) NOT NULL,
    amount_won         NUMERIC(19,0) NOT NULL,
    start_year_month   DATE NOT NULL,
    end_year_month     DATE,
    status             VARCHAR(20) NOT NULL,
    confirmed_at       TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_policy_benefit_owned_goal
        FOREIGN KEY (goal_id, user_id) REFERENCES financial_goals(id, user_id),
    CONSTRAINT ck_policy_benefit_adjustment_type
        CHECK (adjustment_type IN ('ONE_TIME_FUNDING', 'MONTHLY_EXPENSE_REDUCTION')),
    CONSTRAINT ck_policy_benefit_amount
        CHECK (amount_won > 0),
    CONSTRAINT ck_policy_benefit_status
        CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_policy_benefit_start_month
        CHECK (extract(day FROM start_year_month) = 1),
    CONSTRAINT ck_policy_benefit_end_month
        CHECK (end_year_month IS NULL OR extract(day FROM end_year_month) = 1),
    CONSTRAINT ck_policy_benefit_period
        CHECK (
            (adjustment_type = 'ONE_TIME_FUNDING' AND end_year_month IS NULL)
            OR
            (adjustment_type = 'MONTHLY_EXPENSE_REDUCTION'
                AND end_year_month IS NOT NULL
                AND end_year_month >= start_year_month)
        )
);

CREATE UNIQUE INDEX uq_policy_benefit_confirmed
    ON policy_benefits (user_id, goal_id, policy_version_id)
    WHERE status = 'CONFIRMED';

CREATE INDEX ix_policy_benefits_user_goal_status
    ON policy_benefits (user_id, goal_id, status, confirmed_at DESC);

COMMIT;
