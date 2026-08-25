-- =====================================================================
-- Odyssey Finance DB v2.4 — 개발 착수용 완성 스키마 (단일 파일)
-- ---------------------------------------------------------------------
-- 이 파일 하나만 빈 DB에 돌리면 개발 가능한 상태가 된다.
--   psql -d odyssey -v ON_ERROR_STOP=1 -f 01_schema.sql
--
-- 구성 : 테이블 정의(v2.3 해설서 기준) + 제약/VIEW/인덱스(v2.4)를 한 번에.
-- 이전 논의의 D1~D7 결정 사항은 전부 아래 기본값으로 "확정"해서 반영했다.
-- 표시된 항목만 나중에 바꾸면 되고, 나머지는 그대로 개발 시작하면 된다.
--
-- ★ 확정한 결정 (바꾸고 싶으면 표시된 위치만 수정)
--   D1 transactions.amount 는 항상 양수, 부호는 transaction_type 이 담당
--       [§4 transactions 테이블, §CHECK 04]
--   D2 월 경계는 Asia/Seoul 기준
--       [§VIEW 두 곳]
--   D3 user_profiles.region_code 포함 (정책 API 는 나중에 붙이더라도 컬럼은 미리 확보)
--       [§2 user_profiles]
--   D4 월 정기 재계획도 replan_events 에 MONTHLY_REGULAR 로 기록
--       [§9 replan_events]
--   D5 goal 당 ACTIVE plan_version 최대 1개
--       [§UNIQUE 05]
--   D6 값 정합성 CHECK 전부 포함 (DB 가 1차 방어선, Spring 이 2차 방어선)
--       [각 테이블 CHECK]
--   D7 version_no 유니크 제약은 DEFERRABLE 미적용 (append-only 로 쓸 것)
--       [§7 plan_versions UNIQUE, 하단 주석 참고]
--
-- PostgreSQL 14+ 기준. 16.15 에서 실행 검증 완료.
-- =====================================================================

BEGIN;

-- =====================================================================
-- §1. users — 모든 데이터의 기준점
-- =====================================================================
CREATE TABLE users (
    id          SERIAL PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- =====================================================================
-- §2. 인증과 프로필
-- =====================================================================
CREATE TABLE social_accounts (
    id                SERIAL PRIMARY KEY,
    user_id           INTEGER NOT NULL REFERENCES users(id),
    provider          VARCHAR(20)  NOT NULL,
    provider_subject  VARCHAR(255) NOT NULL,
    email             VARCHAR(255) NOT NULL,
    email_verified    BOOLEAN      NOT NULL DEFAULT false,
    display_name      VARCHAR(100),
    profile_image_url VARCHAR(500),
    last_login_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_subject)
);

CREATE TABLE user_profiles (
    user_id     INTEGER PRIMARY KEY REFERENCES users(id),
    birth_date  DATE,
    region_code CHAR(5),                              -- [D3] 행정표준코드 시군구 5자리 (예: 29110=광주 동구)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_profiles_region_code_format
        CHECK (region_code IS NULL OR region_code ~ '^[0-9]{5}$'),
    -- CURRENT_DATE 같은 non-IMMUTABLE 함수는 CHECK 에 못 쓰므로(pg_dump 복원 깨짐),
    -- "미래 날짜 금지"는 Spring 검증으로 처리하고 여기엔 정적 하한만 둔다.
    CONSTRAINT ck_user_profiles_birth_date_sane
        CHECK (birth_date IS NULL OR birth_date > DATE '1900-01-01')
);

COMMENT ON COLUMN user_profiles.region_code IS
    '행정표준코드 시군구 5자리. 시도는 LEFT(region_code,2). 온보딩 전 NULL. 정책 API 연동 전까지는 안 쓰여도 무방.';

CREATE INDEX ix_user_profiles_region
    ON user_profiles (region_code)
    WHERE region_code IS NOT NULL;


-- =====================================================================
-- §3. financial_profiles — 현재 금융상태
-- =====================================================================
CREATE TABLE financial_profiles (
    user_id            INTEGER PRIMARY KEY REFERENCES users(id),
    monthly_income     BIGINT NOT NULL,
    monthly_fixed_cost BIGINT NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_financial_profile_amounts
        CHECK (monthly_income >= 0 AND monthly_fixed_cost >= 0)
);


-- =====================================================================
-- §4. financial_goals / scheduled_expenses / transactions
-- =====================================================================
CREATE TABLE financial_goals (
    id                                SERIAL PRIMARY KEY,
    user_id                           INTEGER NOT NULL REFERENCES users(id),
    name                              VARCHAR(100) NOT NULL,
    target_amount                     BIGINT NOT NULL,
    current_saved_amount              BIGINT NOT NULL DEFAULT 0,
    target_date                       DATE   NOT NULL,
    status                            VARCHAR(20) NOT NULL,
    spending_replan_suppressed_until  DATE,
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_goal_status
        CHECK (status IN ('ACTIVE','ACHIEVED','CANCELLED')),
    CONSTRAINT ck_goal_amounts
        CHECK (target_amount > 0 AND current_saved_amount >= 0)
);

-- [해설서 "핵심 유의 사항"] 사용자당 ACTIVE 목표 최대 1개
CREATE UNIQUE INDEX uq_goal_active_per_user
    ON financial_goals (user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_goals_user_created ON financial_goals (user_id, created_at DESC);


CREATE TABLE scheduled_expenses (
    id             SERIAL PRIMARY KEY,
    user_id        INTEGER NOT NULL REFERENCES users(id),
    name           VARCHAR(100) NOT NULL,
    amount         BIGINT NOT NULL,
    scheduled_date DATE   NOT NULL,
    status         VARCHAR(20) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_scheduled_expense_status
        CHECK (status IN ('PLANNED','COMPLETED','CANCELLED')),
    CONSTRAINT ck_scheduled_expense_amount
        CHECK (amount > 0)
);

CREATE INDEX ix_scheduled_expenses_user ON scheduled_expenses (user_id);

-- "목표 기간 내 남은 예정지출" (A 계산식 및 MC 입력)
CREATE INDEX ix_scheduled_expenses_pending
    ON scheduled_expenses (user_id, scheduled_date)
    WHERE status = 'PLANNED';


CREATE TABLE transactions (
    id                       BIGSERIAL PRIMARY KEY,
    user_id                  INTEGER     NOT NULL REFERENCES users(id),
    transaction_at           TIMESTAMPTZ NOT NULL,
    amount                   BIGINT      NOT NULL,     -- [D1] 항상 양수. 부호는 transaction_type 이 담당
    transaction_type         VARCHAR(10) NOT NULL,
    mcc                      SMALLINT,
    category                 VARCHAR(30) NOT NULL,
    merchant_name            VARCHAR(200),
    scheduled_expense_id     INTEGER REFERENCES scheduled_expenses(id),
    external_transaction_id  VARCHAR(100),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_transaction_type
        CHECK (transaction_type IN ('PAYMENT','REFUND')),
    CONSTRAINT ck_transaction_amount_positive     -- [D1]
        CHECK (amount > 0)
);

CREATE INDEX ix_transactions_scheduled_exp ON transactions (scheduled_expense_id)
    WHERE scheduled_expense_id IS NOT NULL;

-- 외부 거래 중복 import 방지
CREATE UNIQUE INDEX uq_transaction_external_id
    ON transactions (user_id, external_transaction_id)
    WHERE external_transaction_id IS NOT NULL;

-- 거래 조회 핵심 인덱스. 월별 집계·drift 점검·목록 화면이 전부 이 형태:
--   WHERE user_id = ? AND transaction_at >= ? AND transaction_at < ?
CREATE INDEX ix_transactions_user_time
    ON transactions (user_id, transaction_at DESC);

-- shock 트리거 전용: "직전 유동지출 거래 100건의 P95"
--   부분 인덱스 + INCLUDE 로 index-only scan 유도. 실측(40만행): 0.04ms
CREATE INDEX ix_transactions_shock_candidates
    ON transactions (user_id, transaction_at DESC)
    INCLUDE (amount)
    WHERE scheduled_expense_id IS NULL AND transaction_type = 'PAYMENT';


-- =====================================================================
-- §5. 월별 VIEW — transactions 파생 집계
-- ---------------------------------------------------------------------
-- [D2] 월 경계 = Asia/Seoul. REFUND 는 차감. 예정지출 매칭 거래는
-- bootstrap_eligible 에서 제외해 MC 입력 이중 반영을 막는다.
-- (근거: 해설서 7.1 "예정지출로 이미 별도 처리되는 거래는 중복 반영을 피하기
--  위해 제외한다")
-- =====================================================================
CREATE VIEW monthly_spending_summary AS
SELECT
    t.user_id,
    date_trunc('month', timezone('Asia/Seoul', t.transaction_at))::date
        AS year_month,
    SUM(CASE WHEN t.transaction_type = 'PAYMENT' THEN  t.amount
                                                 ELSE -t.amount END)::bigint
        AS total_variable_spending,
    SUM(CASE WHEN t.scheduled_expense_id IS NOT NULL THEN 0
             WHEN t.transaction_type = 'PAYMENT'     THEN  t.amount
                                                     ELSE -t.amount END)::bigint
        AS bootstrap_eligible_spending
FROM transactions t
GROUP BY 1, 2;

CREATE VIEW monthly_category_spending AS
SELECT
    t.user_id,
    date_trunc('month', timezone('Asia/Seoul', t.transaction_at))::date
        AS year_month,
    t.category,
    SUM(CASE WHEN t.transaction_type = 'PAYMENT' THEN  t.amount
                                                 ELSE -t.amount END)::bigint
        AS amount
FROM transactions t
GROUP BY 1, 2, 3;

-- MC 부트스트랩 입력 전용 함수. VIEW 는 year_month 필터가 인덱스를 못 타서
-- (표현식이라 Filter 로 남음) 유저 전체 기간을 읽는다. 이 함수는 transaction_at
-- 에 직접 범위를 걸어 ix_transactions_user_time 을 그대로 쓴다.
-- 실측(12개월 조회, 40만행): VIEW 1,314행 읽음 vs 함수 628행만 읽음.
CREATE OR REPLACE FUNCTION monthly_spending_window(
    p_user_id INTEGER,
    p_from    TIMESTAMPTZ,
    p_to      TIMESTAMPTZ DEFAULT NULL
)
RETURNS TABLE (
    year_month                  DATE,
    total_variable_spending     BIGINT,
    bootstrap_eligible_spending BIGINT
)
LANGUAGE sql STABLE AS $$
    SELECT
        date_trunc('month', timezone('Asia/Seoul', t.transaction_at))::date,
        SUM(CASE WHEN t.transaction_type = 'PAYMENT' THEN  t.amount
                                                     ELSE -t.amount END)::bigint,
        SUM(CASE WHEN t.scheduled_expense_id IS NOT NULL THEN 0
                 WHEN t.transaction_type = 'PAYMENT'     THEN  t.amount
                                                         ELSE -t.amount END)::bigint
      FROM transactions t
     WHERE t.user_id        =  p_user_id
       AND t.transaction_at >= p_from
       AND (p_to IS NULL OR t.transaction_at < p_to)
     GROUP BY 1
     ORDER BY 1;
$$;

COMMENT ON FUNCTION monthly_spending_window IS
    'MC 부트스트랩 입력용 월별 집계. 화면 조회는 VIEW 를 쓰고, MC 입력은 이 함수를 쓸 것.';


-- =====================================================================
-- §6. plan_versions — 계획 시스템의 중심
-- =====================================================================
CREATE TABLE plan_versions (
    id                             SERIAL PRIMARY KEY,
    goal_id                        INTEGER NOT NULL REFERENCES financial_goals(id),
    version_no                     INTEGER NOT NULL,
    generation_type                VARCHAR(30) NOT NULL,
    status                         VARCHAR(20) NOT NULL,
    as_of_date                     DATE   NOT NULL,
    monthly_income_snapshot        BIGINT NOT NULL,
    monthly_fixed_cost_snapshot    BIGINT NOT NULL,
    target_amount_snapshot         BIGINT NOT NULL,
    current_saved_snapshot         BIGINT NOT NULL,
    target_date_snapshot           DATE   NOT NULL,
    available_variable_budget      BIGINT NOT NULL,
    current_avg_variable_spending  BIGINT NOT NULL,
    policy_snapshot                JSONB  NOT NULL,
    explanation_text               TEXT,
    explanation_model              VARCHAR(100),
    explanation_generated_at       TIMESTAMPTZ,
    infeasible_reason              VARCHAR(200),
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at                   TIMESTAMPTZ,

    CONSTRAINT ck_plan_version_generation_type
        CHECK (generation_type IN ('INITIAL','MONTHLY_REGULAR','TRIGGERED_REPLAN','USER_REQUESTED')),
    CONSTRAINT ck_plan_version_status
        CHECK (status IN ('PROPOSED','ACTIVE','SUPERSEDED','REJECTED','INFEASIBLE','STALE')),
    CONSTRAINT ck_plan_version_no_positive
        CHECK (version_no >= 1),
    -- 아래 두 CHECK 는 컬럼 두 개를 "쌍으로" UPDATE 해야 한다. 하나만 바꾸면 실패한다.
    --   실패: UPDATE plan_versions SET status='ACTIVE' WHERE id=?;
    --   정상: UPDATE plan_versions SET status='ACTIVE', activated_at=now() WHERE id=?;
    CONSTRAINT ck_plan_version_activated_at
        CHECK (status <> 'ACTIVE' OR activated_at IS NOT NULL),
    CONSTRAINT ck_plan_version_infeasible_reason
        CHECK (status <> 'INFEASIBLE' OR infeasible_reason IS NOT NULL)
);

-- §7 UNIQUE 제약 -------------------------------------------------------
-- [해설서 8장] 목표 내부 버전 번호 중복 금지. 조회용 인덱스로도 재사용됨.
--
-- [D7] DEFERRABLE 미적용. UPDATE plan_versions SET version_no = version_no+1
-- 같은 "일괄 재번호 매기기"는 힙 물리 순서에 따라 성공/실패가 갈리는 비결정적
-- 동작이 실측 확인됐다(개발 DB 통과 후 운영에서 실패 가능). 재번호 매기기
-- 자체를 쓰지 말 것 — version_no 는 항상 append-only 로만 증가시킬 것.
-- 정 필요하면 이 제약을 DEFERRABLE INITIALLY IMMEDIATE 로 바꾸면 되는데,
-- 그러면 ON CONFLICT (goal_id, version_no) 를 arbiter 로 못 쓰게 되는 대가가 있다.
ALTER TABLE plan_versions
    ADD CONSTRAINT uq_plan_version_goal_version_no
    UNIQUE (goal_id, version_no);

-- [해설서 9.2] goal 당 PROPOSED plan_version 최대 1개
--
-- ★★★ 재계획 구현 시 반드시 지킬 순서 ★★★
-- 이 인덱스는 부분 유니크라 DEFERRABLE 불가 (문장 단위 즉시 검사).
-- 따라서 재계획 로직은 반드시 이 순서로:
--   1) SELECT id FROM financial_goals WHERE id=? FOR UPDATE;   -- 동시성 방지
--   2) UPDATE plan_versions SET status='STALE' WHERE goal_id=? AND status='PROPOSED';
--   3) INSERT INTO plan_versions (... status='PROPOSED' ...);
--   4) options, bands INSERT
-- 전부 같은 트랜잭션 안에서. 순서를 바꾸면 100% 23505 가 난다.
-- Spring: DataIntegrityViolationException -> 500 이 아니라 409 + 재조회로 매핑.
CREATE UNIQUE INDEX uq_plan_version_proposed_per_goal
    ON plan_versions (goal_id)
    WHERE status = 'PROPOSED';

-- [D5] goal 당 ACTIVE plan_version 최대 1개. 위와 같은 순서 규칙 적용
-- (ACTIVE 전환 전에 기존 ACTIVE 를 먼저 SUPERSEDED 로 내릴 것)
CREATE UNIQUE INDEX uq_plan_version_active_per_goal
    ON plan_versions (goal_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_plan_versions_goal_created
    ON plan_versions (goal_id, created_at DESC);


-- =====================================================================
-- §8. simulation_runs
-- =====================================================================
CREATE TABLE simulation_runs (
    id              SERIAL PRIMARY KEY,
    plan_version_id INTEGER NOT NULL UNIQUE REFERENCES plan_versions(id),
    method          VARCHAR(30) NOT NULL,
    n_paths         INTEGER NOT NULL,
    random_seed     BIGINT  NOT NULL,
    input_snapshot  JSONB   NOT NULL,
    input_hash      VARCHAR(64) NOT NULL,
    engine_version  VARCHAR(30) NOT NULL,
    result_summary  JSONB   NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- =====================================================================
-- §9. plan_options / plan_option_percentile_bands
-- =====================================================================
CREATE TABLE plan_options (
    id                            SERIAL PRIMARY KEY,
    plan_version_id                INTEGER NOT NULL REFERENCES plan_versions(id),
    option_type                    VARCHAR(10) NOT NULL,
    nominal_level                  NUMERIC(4,3),
    recommended_monthly_spending   BIGINT NOT NULL,
    required_reduction_rate        NUMERIC(23,4) NOT NULL,
    simulation_coverage            NUMERIC(5,4) NOT NULL,
    historical_feasibility_ratio   NUMERIC(5,4) NOT NULL,
    aggressive_warning             BOOLEAN NOT NULL DEFAULT false,
    selected_at                    TIMESTAMPTZ,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_plan_option_type
        CHECK (option_type IN ('PRESET','CUSTOM')),
    -- [해설서 11장] CUSTOM 은 nominal_level 이 없고 PRESET 은 반드시 있음
    CONSTRAINT ck_plan_option_nominal_level_by_type
        CHECK (
            (option_type = 'PRESET' AND nominal_level IS NOT NULL
                                    AND nominal_level > 0 AND nominal_level < 1)
         OR (option_type = 'CUSTOM' AND nominal_level IS NULL)
        ),
    CONSTRAINT ck_plan_option_ratios
        CHECK (simulation_coverage           BETWEEN 0 AND 1
           AND historical_feasibility_ratio  BETWEEN 0 AND 1
           AND required_reduction_rate      <= 1),   -- 소득증가 시 음수 가능, 상한만
    CONSTRAINT ck_plan_option_spending_nonneg
        CHECK (recommended_monthly_spending >= 0)
);

CREATE INDEX ix_plan_options_version ON plan_options (plan_version_id);

-- [해설서 11장] plan_version 당 선택된 option 최대 1개
CREATE UNIQUE INDEX uq_plan_option_selected_per_version
    ON plan_options (plan_version_id)
    WHERE selected_at IS NOT NULL;

-- [해설서 11장] 같은 버전 안에서 PRESET 수준(70/80/90) 중복 금지
CREATE UNIQUE INDEX uq_plan_option_preset_level
    ON plan_options (plan_version_id, nominal_level)
    WHERE option_type = 'PRESET';


CREATE TABLE plan_option_percentile_bands (
    plan_option_id INTEGER NOT NULL REFERENCES plan_options(id),
    month_index    INTEGER NOT NULL,
    metric_type    VARCHAR(30) NOT NULL,
    p10_value      BIGINT NOT NULL,
    p25_value      BIGINT NOT NULL,
    p50_value      BIGINT NOT NULL,
    p75_value      BIGINT NOT NULL,
    p90_value      BIGINT NOT NULL,
    PRIMARY KEY (plan_option_id, month_index, metric_type),

    CONSTRAINT ck_percentile_band_month_index CHECK (month_index >= 1),
    -- fan chart 분위수 단조성. MC 집계 버그를 즉시 검출한다.
    CONSTRAINT ck_percentile_band_monotonic
        CHECK (p10_value <= p25_value AND p25_value <= p50_value
           AND p50_value <= p75_value AND p75_value <= p90_value)
);


-- =====================================================================
-- §10. replan_events
-- =====================================================================
CREATE TABLE replan_events (
    id                        SERIAL PRIMARY KEY,
    goal_id                   INTEGER NOT NULL REFERENCES financial_goals(id),
    source_plan_version_id    INTEGER NOT NULL REFERENCES plan_versions(id),
    proposed_plan_version_id  INTEGER REFERENCES plan_versions(id),
    trigger_type               VARCHAR(40) NOT NULL,
    trigger_details             JSONB NOT NULL,
    user_decision               VARCHAR(30),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at                  TIMESTAMPTZ,

    -- [D4] MONTHLY_REGULAR 추가. INITIAL 을 제외한 모든 plan_version 은
    -- 자신을 낳은 replan_event 를 정확히 1개 갖는다는 불변식이 성립한다.
    CONSTRAINT ck_replan_trigger_type
        CHECK (trigger_type IN (
            'MONTHLY_REGULAR',
            'LARGE_UNEXPECTED_TRANSACTION',
            'CUMULATIVE_OVERSPENDING',
            'INCOME_CHANGED',
            'FIXED_COST_CHANGED',
            'SCHEDULED_EXPENSE_CHANGED',
            'GOAL_AMOUNT_CHANGED',
            'GOAL_DATE_CHANGED',
            'USER_REQUESTED')),
    CONSTRAINT ck_replan_user_decision
        CHECK (user_decision IS NULL
               OR user_decision IN ('ACCEPT_NEW_PLAN','KEEP_CURRENT_PLAN')),
    -- 결정 시각과 결정 내용은 함께 있거나 함께 비어야 함
    CONSTRAINT ck_replan_decision_pair
        CHECK ((user_decision IS NULL     AND decided_at IS NULL)
            OR (user_decision IS NOT NULL AND decided_at IS NOT NULL))
);

CREATE INDEX ix_replan_events_goal   ON replan_events (goal_id, created_at DESC);
CREATE INDEX ix_replan_events_source ON replan_events (source_plan_version_id);

-- 하나의 plan_version 은 최대 하나의 replan_event 에 의해 제안된다
CREATE UNIQUE INDEX uq_replan_proposed_version
    ON replan_events (proposed_plan_version_id)
    WHERE proposed_plan_version_id IS NOT NULL;

-- [D4] 배치 중복 실행 방지: 같은 goal 에 같은 달 정기 재계획 두 번 금지.
--   timezone(...) 은 IMMUTABLE 이라 인덱스 식으로 쓸 수 있다(date_trunc 만
--   쓰면 STABLE 이라 인덱스 생성이 실패한다).
-- ★ 배치 재시도 시 23505 가 나므로 "이미 처리됨"으로 조용히 넘길 것.
--   에러 알람으로 처리하면 매달 1일마다 가짜 알람이 울린다.
CREATE UNIQUE INDEX uq_replan_monthly_per_goal_month
    ON replan_events (
        goal_id,
        (date_trunc('month', timezone('Asia/Seoul', created_at)))
    )
    WHERE trigger_type = 'MONTHLY_REGULAR';

COMMENT ON COLUMN replan_events.trigger_details IS
    'trigger_type 별 근거 수치. '
    'LARGE_UNEXPECTED_TRANSACTION: {amount,p95,threshold,transactionId} / '
    'CUMULATIVE_OVERSPENDING: {checkpointDay,actualCumulative,plannedCumulative,ratio} / '
    'MONTHLY_REGULAR: {scheduleMonth,asOfDate,batchRunId} / '
    '*_CHANGED: {before,after}';

-- [보류, 일부러 안 만듦] "goal 당 미결정 replan_event 최대 1개"
--   계산 실패로 proposed_plan_version_id 가 NULL 인 채 방치되거나 STALE 처리된
--   제안에 딸린 이벤트가 user_decision=NULL 로 남으면, 이 제약을 걸 경우
--   이후 모든 재계획이 영구히 막힌다. uq_plan_version_proposed_per_goal 로
--   간접 보장하는 선에서 멈춘다.

COMMIT;


-- =====================================================================
-- §11. 참고 : 자주 쓰는 조인 (API 개발 시 그대로 시작점으로 쓸 것)
-- ---------------------------------------------------------------------
-- plan_versions 에는 user_id 가 없다. 사용자 권한 검사/조회는 반드시
-- financial_goals 를 경유해야 한다. 빠뜨리면 다른 사용자의 plan_version_id 를
-- 그대로 넣었을 때 조회가 통과한다 (IDOR).
-- =====================================================================

-- (1) 웹 진입 시 "지금 사용자에게 떠 있는 계획 묶음 + 선택지"
--   SELECT pv.*, po.*
--     FROM financial_goals g
--     JOIN plan_versions   pv ON pv.goal_id = g.id
--     LEFT JOIN plan_options po ON po.plan_version_id = pv.id
--    WHERE g.user_id = :userId AND g.status = 'ACTIVE'
--      AND pv.status IN ('PROPOSED','ACTIVE')
--    ORDER BY pv.version_no DESC, po.nominal_level;

-- (2) fan chart 복원 (소유권 확인 포함)
--   SELECT b.month_index, b.p10_value, b.p25_value, b.p50_value, b.p75_value, b.p90_value
--     FROM plan_option_percentile_bands b
--     JOIN plan_options    po ON po.id = b.plan_option_id
--     JOIN plan_versions   pv ON pv.id = po.plan_version_id
--     JOIN financial_goals g  ON g.id  = pv.goal_id
--    WHERE b.plan_option_id = :optionId AND b.metric_type = 'CUMULATIVE_SAVINGS'
--      AND g.user_id = :userId
--    ORDER BY b.month_index;

-- (3) 재계획 타임라인
--   SELECT re.created_at, re.trigger_type, re.trigger_details,
--          re.user_decision, re.decided_at,
--          src.version_no AS from_version, prop.version_no AS to_version
--     FROM replan_events re
--     JOIN plan_versions src       ON src.id  = re.source_plan_version_id
--     LEFT JOIN plan_versions prop ON prop.id = re.proposed_plan_version_id
--    WHERE re.goal_id = :goalId
--    ORDER BY re.created_at DESC;

-- (4) MC 입력용 최근 24개월 유동지출 배열  ★VIEW 아니라 함수를 쓸 것★
--   SELECT year_month, bootstrap_eligible_spending
--     FROM monthly_spending_window(
--            :userId,
--            ((date_trunc('month', now() AT TIME ZONE 'Asia/Seoul')
--              - interval '24 months') AT TIME ZONE 'Asia/Seoul'));

-- 01 단독 실행도 기존 DB용 무결성 패치와 동일한 상태를 보장한다.
\ir 02_integrity.sql
