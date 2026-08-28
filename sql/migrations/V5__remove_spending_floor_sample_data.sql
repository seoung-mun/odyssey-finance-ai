BEGIN;

ALTER TABLE financial_profiles
    DROP CONSTRAINT IF EXISTS ck_financial_profile_spending_floor,
    DROP COLUMN IF EXISTS spending_floor_mode,
    DROP COLUMN IF EXISTS custom_monthly_variable_floor;

ALTER TABLE plan_options
    DROP CONSTRAINT IF EXISTS ck_plan_option_effective_max_reduction_rate,
    DROP COLUMN IF EXISTS effective_max_reduction_rate,
    DROP COLUMN IF EXISTS floor_applied;

ALTER TABLE users
    DROP COLUMN IF EXISTS sample_data_loaded_at;

COMMIT;
