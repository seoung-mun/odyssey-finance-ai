-- =====================================================================
-- Odyssey Finance DB : 무결성 패치 (v2.4 -> v2.5)
-- ---------------------------------------------------------------------
-- 01_schema.sql 을 이미 돌린 DB 에 적용한다.
-- 아직 안 돌렸다면 갱신된 01_schema.sql 에 아래 내용이 이미 포함돼 있으므로
-- 이 파일은 실행할 필요가 없다.
--
-- 이 패치가 막는 것은 "에러가 나는 버그"가 아니라 "에러 없이 숫자가 틀리는
-- 버그"다. 아래 항목은 전부 v2.4 스키마에서 조용히 통과하는 것이 실측됐다.
-- =====================================================================

BEGIN;

-- =====================================================================
-- 【1】 교차 소유권 위반 차단  ★가장 위험★
-- ---------------------------------------------------------------------
-- 실측된 문제
--   유저1의 거래가 유저2의 예정지출을 참조하는 INSERT 가 그대로 통과했다.
--   기존 FK 는 "scheduled_expense_id 가 존재하는가"만 볼 뿐 "같은 사람 것인가"는
--   보지 않기 때문이다.
--
-- 왜 조용히 위험한가
--   monthly_spending_summary 의 bootstrap_eligible_spending 은
--   scheduled_expense_id IS NOT NULL 인 거래를 제외한다. 남의 예정지출에
--   잘못 연결된 거래는 유저 본인의 MC 부트스트랩 입력에서 통째로 빠진다.
--   -> 월 지출 배열이 실제보다 작아짐 -> Q70/Q80/Q90 분위수가 낮게 나옴
--   -> "월 97만원 쓰세요" 가 실제보다 낙관적으로 계산됨. 에러는 안 난다.
--
-- 해법: 복합 FK 로 소유자까지 함께 참조하게 만들어 구조적으로 불가능하게 한다.
--       scheduled_expense_id 가 NULL 이면 FK 는 검사되지 않는다(MATCH SIMPLE).
-- =====================================================================

ALTER TABLE scheduled_expenses
    ADD CONSTRAINT uq_scheduled_expense_id_user UNIQUE (id, user_id);

ALTER TABLE transactions
    DROP CONSTRAINT transactions_scheduled_expense_id_fkey;

ALTER TABLE transactions
    ADD CONSTRAINT fk_transaction_scheduled_expense_same_user
    FOREIGN KEY (scheduled_expense_id, user_id)
    REFERENCES scheduled_expenses (id, user_id);


-- ---------------------------------------------------------------------
-- 같은 문제가 replan_events 에도 있다.
--   goal_id = 1 인 이벤트가 goal_id = 2 의 plan_version 을 source 로 가리키는
--   INSERT 가 통과했다. 재계획 타임라인 화면이 남의 계획을 섞어 보여준다.
-- ---------------------------------------------------------------------

ALTER TABLE plan_versions
    ADD CONSTRAINT uq_plan_version_id_goal UNIQUE (id, goal_id);

ALTER TABLE replan_events
    DROP CONSTRAINT replan_events_source_plan_version_id_fkey;
ALTER TABLE replan_events
    ADD CONSTRAINT fk_replan_source_same_goal
    FOREIGN KEY (source_plan_version_id, goal_id)
    REFERENCES plan_versions (id, goal_id);

ALTER TABLE replan_events
    DROP CONSTRAINT replan_events_proposed_plan_version_id_fkey;
ALTER TABLE replan_events
    ADD CONSTRAINT fk_replan_proposed_same_goal
    FOREIGN KEY (proposed_plan_version_id, goal_id)
    REFERENCES plan_versions (id, goal_id);


-- =====================================================================
-- 【2】 취소된 예정지출에 매칭된 거래가 bootstrap 에서 빠지는 문제
-- ---------------------------------------------------------------------
-- 실측된 문제
--   예정지출을 CANCELLED 로 바꿔도, 거기 매칭돼 있던 거래는 여전히
--   scheduled_expense_id 가 채워져 있어 bootstrap 에서 제외된 채로 남았다.
--   취소된 예정지출은 계획 계산에서 차감되지 않으므로, 그 거래는 평범한
--   유동지출로 되돌아와야 한다. 지금은 어느 쪽에도 안 잡혀 사라진다.
--
-- 해법: VIEW 가 예정지출의 status 까지 보게 한다.
--       CANCELLED 인 예정지출에 매칭된 거래는 일반 유동지출로 취급.
-- =====================================================================

DROP VIEW IF EXISTS monthly_spending_summary;

CREATE VIEW monthly_spending_summary AS
SELECT
    t.user_id,
    date_trunc('month', timezone('Asia/Seoul', t.transaction_at))::date
        AS year_month,
    SUM(CASE WHEN t.transaction_type = 'PAYMENT' THEN  t.amount
                                                 ELSE -t.amount END)::bigint
        AS total_variable_spending,
    SUM(CASE WHEN se.id IS NOT NULL
              AND se.status <> 'CANCELLED'            THEN 0
             WHEN t.transaction_type = 'PAYMENT'      THEN  t.amount
                                                      ELSE -t.amount END)::bigint
        AS bootstrap_eligible_spending
FROM transactions t
LEFT JOIN scheduled_expenses se ON se.id = t.scheduled_expense_id
GROUP BY 1, 2;

COMMENT ON VIEW monthly_spending_summary IS
    'transactions 파생 집계. 월 경계 Asia/Seoul. REFUND 차감. '
    'bootstrap_eligible 은 유효한(취소되지 않은) 예정지출에 매칭된 거래만 제외한다.';

-- 함수 쪽도 동일하게 맞춘다 (VIEW 와 값이 달라지면 안 됨)
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
        SUM(CASE WHEN se.id IS NOT NULL
                  AND se.status <> 'CANCELLED'       THEN 0
                 WHEN t.transaction_type = 'PAYMENT' THEN  t.amount
                                                     ELSE -t.amount END)::bigint
      FROM transactions t
      LEFT JOIN scheduled_expenses se ON se.id = t.scheduled_expense_id
     WHERE t.user_id        =  p_user_id
       AND t.transaction_at >= p_from
       AND (p_to IS NULL OR t.transaction_at < p_to)
     GROUP BY 1
     ORDER BY 1;
$$;


-- =====================================================================
-- 【3】 값 검증 누락분
-- ---------------------------------------------------------------------
-- 실측: simulation_runs 에 method='아무거나', n_paths=-5, input_hash='' 가
--       그대로 저장됐다. 재현 정보를 담는 테이블인데 재현 불가능한 값이
--       들어가도 아무도 모른다.
-- =====================================================================

ALTER TABLE simulation_runs
    ADD CONSTRAINT ck_simulation_method
    CHECK (method IN ('IID_BOOTSTRAP'));   -- 방식 추가 시 여기에 값 추가

ALTER TABLE simulation_runs
    ADD CONSTRAINT ck_simulation_n_paths
    CHECK (n_paths > 0);

-- input_hash 는 SHA-256 hex 64자 고정
ALTER TABLE simulation_runs
    ADD CONSTRAINT ck_simulation_input_hash
    CHECK (input_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE simulation_runs
    ADD CONSTRAINT ck_simulation_engine_version
    CHECK (length(btrim(engine_version)) > 0);

-- JSONB 가 NOT NULL 이어도 '{}' 는 통과한다. 스냅샷이 비면 "과거 계획을
-- 그때 조건으로 설명한다"는 목적 자체가 무너지므로 빈 객체를 막는다.
ALTER TABLE simulation_runs
    ADD CONSTRAINT ck_simulation_snapshots_not_empty
    CHECK (input_snapshot <> '{}'::jsonb AND result_summary <> '{}'::jsonb);

ALTER TABLE plan_versions
    ADD CONSTRAINT ck_plan_version_policy_snapshot_not_empty
    CHECK (policy_snapshot <> '{}'::jsonb);

-- 빈 문자열/공백만 있는 이름·카테고리 차단.
-- category='' 이면 monthly_category_spending 에 이름 없는 카테고리 칸이 생긴다.
ALTER TABLE transactions
    ADD CONSTRAINT ck_transaction_category_not_blank
    CHECK (length(btrim(category)) > 0);

ALTER TABLE financial_goals
    ADD CONSTRAINT ck_goal_name_not_blank
    CHECK (length(btrim(name)) > 0);

ALTER TABLE scheduled_expenses
    ADD CONSTRAINT ck_scheduled_expense_name_not_blank
    CHECK (length(btrim(name)) > 0);

COMMIT;


-- =====================================================================
-- 【4】 데이터베이스 레벨 운영 설정  ★개발/운영 차이의 주범★
-- ---------------------------------------------------------------------
-- 실측된 문제
--   같은 SQL, 같은 데이터인데 서버 타임존에 따라 결과가 달랐다.
--     '2026-08-01'::timestamptz  ->  KST 서버: 2026-08-01 00:00+09
--                                    UTC 서버: 2026-08-01 00:00+00
--   monthly_spending_window(1, '2026-08-01'::timestamptz) 결과가
--     KST 서버: 1행   /   UTC 서버: 0행
--   즉 개발자 노트북(KST)에서는 맞고 EC2(UTC)에서는 틀린다. 에러는 안 난다.
--
-- 해법 두 가지를 함께 쓴다.
--   (a) DB 자체의 타임존을 고정해 서버 OS 설정과 무관하게 만든다  <- 아래
--   (b) Spring 에서 날짜를 넘길 때 항상 offset 을 명시한다
--         O  '2026-08-01T00:00:00+09:00'
--         X  '2026-08-01'
--
-- ALTER DATABASE 는 트랜잭션 밖에서 실행해야 하며, 적용은 "새 커넥션"부터다.
-- 실행 후 애플리케이션 커넥션 풀을 재시작할 것.
-- =====================================================================

ALTER DATABASE :"DBNAME" SET timezone = 'Asia/Seoul';

-- 무한 대기 방지. 기본값이 전부 0(무제한)이라, 재계획 로직의
-- SELECT ... FOR UPDATE 가 한 번 물리면 커넥션이 영원히 잡힌다.
-- 심사 기간에 커넥션 풀이 고갈되면 서비스 전체가 멈춘다.
ALTER DATABASE :"DBNAME" SET statement_timeout = '15s';
ALTER DATABASE :"DBNAME" SET lock_timeout = '5s';
ALTER DATABASE :"DBNAME" SET idle_in_transaction_session_timeout = '30s';

-- 확인
--   SELECT name, setting FROM pg_settings
--    WHERE name IN ('TimeZone','statement_timeout','lock_timeout',
--                   'idle_in_transaction_session_timeout');


-- =====================================================================
-- 【5】 DB 로 막지 않고 애플리케이션이 책임져야 하는 것
-- ---------------------------------------------------------------------
-- 아래는 CHECK/FK 로 표현할 수 없거나, 표현하면 부작용이 더 큰 항목이다.
-- 코드 리뷰 체크리스트로 쓸 것.
--
-- (a) external_transaction_id 없는 중복 import
--     실측: 같은 거래를 두 번 넣으면 행 2개, 합계 2배가 그대로 저장된다.
--     uq_transaction_external_id 는 external_transaction_id 가 NULL 이면
--     작동하지 않는다. 자연키(시각+금액+가맹점)로 유니크를 걸면 정상적인
--     동일 결제(같은 카페에서 같은 금액 두 잔)까지 막히므로 DB 로는 못 막는다.
--     => import 파이프라인은 external_transaction_id 를 반드시 채울 것.
--        수동 입력 거래에도 UUID 를 발급해 넣는 편이 안전하다.
--
-- (b) 옵션이 선택됐는데 plan_version 이 PROPOSED 인 상태
--     실측: 통과한다. selected_at 기록과 status='ACTIVE' 전환은 반드시
--     같은 트랜잭션에서 함께 수행할 것. (교차 테이블 조건이라 CHECK 불가,
--     트리거로 막을 수는 있으나 재계획 로직이 복잡해져 권장하지 않음)
--
-- (c) fan chart 월 누락
--     실측: month_index 1,2,5 처럼 구멍이 뚫려도 저장된다.
--     bands INSERT 후 count(*) = horizon 인지 애플리케이션에서 확인할 것.
--
-- (d) 목표 금액보다 이미 모은 돈이 많거나 목표일이 과거인 목표
--     실측: 통과한다. 데이터 자체는 유효할 수 있어(달성 후 상태) DB 로는
--     막지 않는다. 계획 생성 시 0단계 feasibility 검사에서 걸러낼 것.
--
-- (e) NUMERIC 정밀도 초과분은 조용히 반올림된다
--     실측: required_reduction_rate 에 0.123456789 -> 0.1235 로 저장.
--     nominal_level(NUMERIC(4,3)) 은 0.7004 -> 0.700.
--     계산 엔진이 넘기는 값의 소수 자릿수를 미리 맞춰서 보낼 것.
--
-- (f) 23505 는 정상 흐름의 일부다
--     실측: FOR UPDATE 없이 두 세션이 동시에 재계획하면 한쪽이
--     uq_plan_version_proposed_per_goal 로 실패한다(최종 상태는 정상).
--     Spring 에서 DataIntegrityViolationException -> 409 + 재조회로 매핑.
--     배치의 uq_replan_monthly_per_goal_month 충돌도 "이미 처리됨"으로 처리.
--
-- (g) 사용자 삭제(회원 탈퇴)
--     실측: FK 가 NO ACTION 이라 users 행을 지울 수 없다.
--     의도적으로 그대로 둔다. 탈퇴는 물리 삭제가 아니라 익명화(soft delete)로
--     설계하는 편이 금융 데이터 보존 요건에도 맞다. 물리 삭제가 필요해지면
--     그때 ON DELETE 정책을 테이블별로 명시적으로 정할 것.
-- =====================================================================
