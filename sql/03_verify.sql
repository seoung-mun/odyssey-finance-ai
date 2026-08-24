-- =====================================================================
-- Odyssey Finance DB v2.4 : 검증 스크립트
-- ---------------------------------------------------------------------
-- 실행: psql -d <db> -f 03_verify_v2_4.sql
-- 전제: 01_schema.sql 이 적용된 빈 DB. 데이터가 있으면 시드가 충돌한다.
--
-- 각 테스트 태그는 01_schema.sql 헤더의 결정번호(D1~D7)와 대응한다.
--   [P1-x]  해설서에 이미 명시돼 있던 규칙 (스키마에 항상 포함됨)
--   [D1..7] 이번에 확정한 결정 (01_schema.sql 헤더 참고, 바꾸려면 거기서 수정)
--
-- ★ 이 하네스는 SQLSTATE 만 보지 않고 "어느 제약이 막았는지" 이름까지
--   대조한다. 이전 버전은 23505 이기만 하면 통과 처리해서, 의도한 제약에
--   닿기도 전에 PK 에서 죽은 케이스를 가짜로 통과시켰다.
-- =====================================================================

SET client_min_messages TO NOTICE;

CREATE OR REPLACE FUNCTION t_fail(label text, stmt text, expect text)
RETURNS void AS $$
DECLARE got text;
BEGIN
    EXECUTE stmt;
    RAISE NOTICE '[FAIL] % <- 차단돼야 하는데 통과함', label;
EXCEPTION WHEN others THEN
    GET STACKED DIAGNOSTICS got = CONSTRAINT_NAME;
    IF got IS DISTINCT FROM expect THEN
        RAISE NOTICE '[FAIL] % <- 엉뚱한 이유로 차단. 기대=% 실제=% (%)',
                     label, expect, coalesce(got, '(제약아님)'), SQLERRM;
    ELSE
        RAISE NOTICE '[ok]   % <- %', label, got;
    END IF;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION t_ok(label text, stmt text) RETURNS void AS $$
BEGIN
    EXECUTE stmt;
    RAISE NOTICE '[ok]   %', label;
EXCEPTION WHEN others THEN
    RAISE NOTICE '[FAIL] % <- 통과해야 하는데 차단됨: %', label, SQLERRM;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION t_eq(label text, actual anyelement, expected anyelement)
RETURNS void AS $$
BEGIN
    IF actual IS NOT DISTINCT FROM expected THEN
        RAISE NOTICE '[ok]   % = %', label, actual;
    ELSE
        RAISE NOTICE '[FAIL] % <- 기대=% 실제=%', label, expected, actual;
    END IF;
END $$ LANGUAGE plpgsql;


-- =====================================================================
-- 시드 (데모 데이터와 동일하게 id 를 명시해서 넣는다)
-- =====================================================================
INSERT INTO users (id) VALUES (1), (2);
INSERT INTO user_profiles (user_id, birth_date) VALUES (1, '1999-04-02');
INSERT INTO financial_profiles (user_id, monthly_income, monthly_fixed_cost)
     VALUES (1, 3000000, 1200000);
INSERT INTO financial_goals (id,user_id,name,target_amount,current_saved_amount,target_date,status)
     VALUES (1,1,'독립자금',10000000,2000000,'2027-06-30','ACTIVE');

CREATE OR REPLACE FUNCTION mk_pv(pid int, vno int, st text,
        act timestamptz DEFAULT NULL, gtype text DEFAULT 'TRIGGERED_REPLAN',
        reason text DEFAULT NULL)
RETURNS void AS $$
BEGIN
    INSERT INTO plan_versions (id,goal_id,version_no,generation_type,status,as_of_date,
        monthly_income_snapshot,monthly_fixed_cost_snapshot,target_amount_snapshot,
        current_saved_snapshot,target_date_snapshot,available_variable_budget,
        current_avg_variable_spending,policy_snapshot,infeasible_reason,activated_at)
    VALUES (pid,1,vno,gtype,st,'2026-08-01',3000000,1200000,10000000,2000000,
            '2027-06-30',8000000,1200000,'{"p95SampleSize":100,"shockRatio":0.15,"driftRatio":1.20,"checkpointDays":[7,14,21],"aggressiveWarningPct":0.10}'::jsonb,reason,act);
END $$ LANGUAGE plpgsql;

SELECT mk_pv(1, 1, 'ACTIVE', now(), 'INITIAL');
INSERT INTO plan_options (id,plan_version_id,option_type,nominal_level,
    recommended_monthly_spending,required_reduction_rate,simulation_coverage,
    historical_feasibility_ratio,selected_at)
VALUES (1,1,'PRESET',0.70,1200000,0.05,0.70,0.55,NULL),
       (2,1,'PRESET',0.80,1050000,0.15,0.80,0.40,now()),
       (3,1,'PRESET',0.90, 970000,0.22,0.90,0.18,NULL);


\echo ''
\echo '#### 0. 시퀀스 정렬 (04_seed_sequence_fix.sql 의 내용) ####'
\echo '   명시 id 시드 직후 시퀀스는 1 에 머물러 있어 앱의 첫 INSERT 가 PK 충돌한다.'
SELECT t_fail('[seed] 정렬 전 id 생략 INSERT', $$
    INSERT INTO plan_options (plan_version_id,option_type,nominal_level,
      recommended_monthly_spending,required_reduction_rate,simulation_coverage,
      historical_feasibility_ratio)
    VALUES (1,'CUSTOM',NULL,850000,0.29,0.94,0.08)$$, 'plan_options_pkey');

DO $$
DECLARE r record; s text;
BEGIN
    FOR r IN SELECT c.oid::regclass AS tbl, a.attname AS col
               FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid
              WHERE c.relkind='r' AND c.relnamespace='public'::regnamespace
                AND a.attnum > 0 AND NOT a.attisdropped
    LOOP
        s := pg_get_serial_sequence(r.tbl::text, r.col);
        CONTINUE WHEN s IS NULL;
        EXECUTE format('SELECT setval(%L, coalesce((SELECT max(%I) FROM %s),1), (SELECT count(*)>0 FROM %s))',
                       s, r.col, r.tbl, r.tbl);
    END LOOP;
END $$;

SELECT t_ok('[seed] 정렬 후 id 생략 INSERT', $$
    INSERT INTO plan_options (plan_version_id,option_type,nominal_level,
      recommended_monthly_spending,required_reduction_rate,simulation_coverage,
      historical_feasibility_ratio)
    VALUES (1,'CUSTOM',NULL,850000,0.29,0.94,0.08)$$);


\echo ''
\echo '#### 1. 상태 유일성 [P1-1] ####'
SELECT t_fail('[P1-1] 사용자당 ACTIVE goal 2개', $$
    INSERT INTO financial_goals (user_id,name,target_amount,current_saved_amount,target_date,status)
    VALUES (1,'자동차자금',5000000,0,'2027-12-31','ACTIVE')$$, 'uq_goal_active_per_user');
SELECT t_ok('[P1-1] 같은 사용자 CANCELLED goal 은 허용', $$
    INSERT INTO financial_goals (user_id,name,target_amount,current_saved_amount,target_date,status)
    VALUES (1,'예전목표',5000000,0,'2026-01-31','CANCELLED')$$);
SELECT t_ok('[P1-1] 다른 사용자 ACTIVE goal 은 허용', $$
    INSERT INTO financial_goals (user_id,name,target_amount,current_saved_amount,target_date,status)
    VALUES (2,'전세자금',20000000,0,'2028-01-31','ACTIVE')$$);

SELECT t_ok  ('[P1-1] v2 PROPOSED 생성',      $$SELECT mk_pv(2,2,'PROPOSED')$$);
SELECT t_fail('[P1-1] v3 PROPOSED 동시 존재', $$SELECT mk_pv(3,3,'PROPOSED')$$,
              'uq_plan_version_proposed_per_goal');
SELECT t_ok  ('[P1-1] SUPERSEDED 는 여러 개 허용', $$SELECT mk_pv(4,4,'SUPERSEDED')$$);
SELECT t_ok  ('[P1-1] STALE 도 여러 개 허용',      $$SELECT mk_pv(5,5,'STALE')$$);
SELECT t_fail('[P1-2] 같은 goal 에 version_no 중복', $$SELECT mk_pv(6,2,'REJECTED')$$,
              'uq_plan_version_goal_version_no');
SELECT t_fail('[P1-2] version_no 0',                 $$SELECT mk_pv(7,0,'REJECTED')$$,
              'ck_plan_version_no_positive');

\echo ''
\echo '#### 2. 재계획 순서 규칙 (STALE 먼저, INSERT 나중) ####'
SELECT t_fail('[P1-1] STALE 처리 전에 새 PROPOSED', $$SELECT mk_pv(8,8,'PROPOSED')$$,
              'uq_plan_version_proposed_per_goal');
SELECT t_ok  ('[P1-1] 기존 PROPOSED 를 STALE 로 먼저',
              $$UPDATE plan_versions SET status='STALE' WHERE id=2$$);
SELECT t_ok  ('[P1-1] 그 다음 새 PROPOSED',          $$SELECT mk_pv(9,9,'PROPOSED')$$);

\echo ''
\echo '#### 3. 선택된 옵션 유일성 [P1-1] / PRESET 중복 [P1-3] ####'
SELECT t_fail('[P1-1] 같은 버전에서 두 번째 옵션 선택',
              $$UPDATE plan_options SET selected_at=now() WHERE id=3$$,
              'uq_plan_option_selected_per_version');
SELECT t_ok  ('[P1-1] 기존 선택 해제 후 재선택', $$
    UPDATE plan_options SET selected_at=NULL WHERE id=2;
    UPDATE plan_options SET selected_at=now() WHERE id=3$$);
SELECT t_fail('[P1-3] PRESET 70% 중복', $$
    INSERT INTO plan_options (plan_version_id,option_type,nominal_level,
      recommended_monthly_spending,required_reduction_rate,simulation_coverage,
      historical_feasibility_ratio)
    VALUES (1,'PRESET',0.70,1190000,0.05,0.70,0.55)$$, 'uq_plan_option_preset_level');
SELECT t_fail('[P1-6] CUSTOM 인데 nominal_level 있음', $$
    INSERT INTO plan_options (plan_version_id,option_type,nominal_level,
      recommended_monthly_spending,required_reduction_rate,simulation_coverage,
      historical_feasibility_ratio)
    VALUES (1,'CUSTOM',0.86,850000,0.29,0.86,0.08)$$,
    'ck_plan_option_nominal_level_by_type');
SELECT t_fail('[P1-6] PRESET 인데 nominal_level 없음', $$
    INSERT INTO plan_options (plan_version_id,option_type,nominal_level,
      recommended_monthly_spending,required_reduction_rate,simulation_coverage,
      historical_feasibility_ratio)
    VALUES (1,'PRESET',NULL,850000,0.29,0.86,0.08)$$,
    'ck_plan_option_nominal_level_by_type');

\echo ''
\echo '#### 4. 상태값 도메인 [P1-5] ####'
SELECT t_fail('[P1-5] 알 수 없는 goal status', $$
    INSERT INTO financial_goals (user_id,name,target_amount,current_saved_amount,target_date,status)
    VALUES (2,'x',1000,0,'2027-01-01','PAUSED')$$, 'ck_goal_status');
SELECT t_fail('[P1-5] 알 수 없는 transaction_type', $$
    INSERT INTO transactions (user_id,transaction_at,amount,transaction_type,category)
    VALUES (1,'2026-08-10 12:00+09',50000,'CANCEL','식비')$$, 'ck_transaction_type');
SELECT t_fail('[P1-5] 알 수 없는 plan_version status', $$SELECT mk_pv(10,10,'DRAFT')$$,
              'ck_plan_version_status');

\echo ''
\echo '#### 5. VIEW 계산 정확성 [P1-7]  ★기능 정확도 직결★ ####'
INSERT INTO scheduled_expenses (id,user_id,name,amount,scheduled_date,status)
     VALUES (1,1,'제주여행',500000,'2026-08-25','PLANNED');
INSERT INTO transactions (user_id,transaction_at,amount,transaction_type,category,scheduled_expense_id)
VALUES (1,'2026-08-05 12:00+09',400000,'PAYMENT','식비',  NULL),
       (1,'2026-08-06 12:00+09',300000,'PAYMENT','쇼핑',  NULL),
       (1,'2026-08-07 12:00+09',200000,'PAYMENT','교통',  NULL),
       (1,'2026-08-25 12:00+09',500000,'PAYMENT','여행',  1),
       (1,'2026-08-15 12:00+09',300000,'PAYMENT','쇼핑',  NULL),
       (1,'2026-08-16 12:00+09',300000,'REFUND', '쇼핑',  NULL),
       (1,'2026-08-31 20:00+00',100000,'PAYMENT','교통',  NULL);

-- 기대값
--   2026-08 total = 40+30+20+50+30-30 = 140만  (예정지출 포함, 환불 상계)
--   2026-08 boot  = 40+30+20   +30-30 =  90만  (예정지출 제외)
--   2026-09 = UTC 8/31 20:00 -> KST 9/1 05:00 이므로 10만이 9월로
SELECT t_eq('[P1-7] 8월 total (예정지출 포함, 환불 상계)',
       (SELECT total_variable_spending FROM monthly_spending_summary
         WHERE user_id=1 AND year_month='2026-08-01'), 1400000::bigint);
SELECT t_eq('[P1-7] 8월 bootstrap (예정지출 제외)  ★이중반영 방지★',
       (SELECT bootstrap_eligible_spending FROM monthly_spending_summary
         WHERE user_id=1 AND year_month='2026-08-01'),  900000::bigint);
SELECT t_eq('[D2]   9월 total (KST 월경계)',
       (SELECT total_variable_spending FROM monthly_spending_summary
         WHERE user_id=1 AND year_month='2026-09-01'),  100000::bigint);
SELECT t_eq('[P1-7] 8월 쇼핑 카테고리 (환불 상계)',
       (SELECT amount FROM monthly_category_spending
         WHERE user_id=1 AND year_month='2026-08-01' AND category='쇼핑'), 300000::bigint);

\echo ''
\echo '#### 6. 헬퍼 함수가 VIEW 와 같은 값을 내는가 [§II] ####'
SELECT t_eq('[§II] 함수 vs VIEW 불일치 행수',
   (SELECT count(*) FROM (
      (SELECT year_month, bootstrap_eligible_spending FROM monthly_spending_summary
        WHERE user_id=1 AND year_month >= '2026-08-01'
       EXCEPT
       SELECT year_month, bootstrap_eligible_spending
         FROM monthly_spending_window(1, timestamptz '2026-08-01 00:00+09'))
      UNION ALL
      (SELECT year_month, bootstrap_eligible_spending
         FROM monthly_spending_window(1, timestamptz '2026-08-01 00:00+09')
       EXCEPT
       SELECT year_month, bootstrap_eligible_spending FROM monthly_spending_summary
        WHERE user_id=1 AND year_month >= '2026-08-01')) d), 0::bigint);

\echo ''
\echo '#### 7. 외부 거래 중복 import [P1-4] ####'
SELECT t_ok  ('[P1-4] 최초 import', $$
    INSERT INTO transactions (user_id,transaction_at,amount,transaction_type,category,external_transaction_id)
    VALUES (1,'2026-08-10 12:00+09',33000,'PAYMENT','식비','EXT-001')$$);
SELECT t_fail('[P1-4] 같은 external id 재import', $$
    INSERT INTO transactions (user_id,transaction_at,amount,transaction_type,category,external_transaction_id)
    VALUES (1,'2026-08-10 12:00+09',33000,'PAYMENT','식비','EXT-001')$$,
    'uq_transaction_external_id');
SELECT t_ok  ('[P1-4] external id NULL 은 여러 개 허용', $$
    INSERT INTO transactions (user_id,transaction_at,amount,transaction_type,category)
    VALUES (1,'2026-08-11 12:00+09',12000,'PAYMENT','교통'),
           (1,'2026-08-12 12:00+09',15000,'PAYMENT','교통')$$);


\echo ''
\echo '#### ===== 여기부터는 PART 2 (승인 필요 결정) 검증 ===== ####'

\echo ''
\echo '#### D1. transactions.amount 부호 규칙 ####'
SELECT t_fail('[D1] 거래 금액 음수', $$
    INSERT INTO transactions (user_id,transaction_at,amount,transaction_type,category)
    VALUES (1,'2026-08-10 12:00+09',-50000,'REFUND','식비')$$,
    'ck_transaction_amount_positive');

\echo ''
\echo '#### D3. region_code ####'
SELECT t_ok  ('[D3] region_code 정상 (광주 동구)',
              $$UPDATE user_profiles SET region_code='29110' WHERE user_id=1$$);
SELECT t_fail('[D3] region_code 형식 위반',
              $$UPDATE user_profiles SET region_code='광주' WHERE user_id=1$$,
              'ck_user_profiles_region_code_format');
SELECT t_fail('[D3] birth_date 하한 위반',
              $$UPDATE user_profiles SET birth_date='1899-01-01' WHERE user_id=1$$,
              'ck_user_profiles_birth_date_sane');

\echo ''
\echo '#### D4. MONTHLY_REGULAR replan_event ####'
SELECT t_ok  ('[D4] MONTHLY_REGULAR 이벤트 생성', $$
    INSERT INTO replan_events (goal_id,source_plan_version_id,proposed_plan_version_id,
      trigger_type,trigger_details)
    VALUES (1,1,9,'MONTHLY_REGULAR','{"scheduleMonth":"2026-09"}')$$);
SELECT t_fail('[D4] 같은 달 MONTHLY_REGULAR 중복', $$
    INSERT INTO replan_events (goal_id,source_plan_version_id,trigger_type,trigger_details)
    VALUES (1,1,'MONTHLY_REGULAR','{"scheduleMonth":"2026-09"}')$$,
    'uq_replan_monthly_per_goal_month');
SELECT t_ok  ('[D4] 같은 달 다른 trigger_type 은 허용', $$
    INSERT INTO replan_events (goal_id,source_plan_version_id,trigger_type,trigger_details)
    VALUES (1,1,'CUMULATIVE_OVERSPENDING','{"checkpointDay":14,"ratio":1.31}')$$);
SELECT t_fail('[D4] 목록에 없는 trigger_type', $$
    INSERT INTO replan_events (goal_id,source_plan_version_id,trigger_type,trigger_details)
    VALUES (1,1,'WEATHER_CHANGED','{"p95SampleSize":100,"shockRatio":0.15,"driftRatio":1.20,"checkpointDays":[7,14,21],"aggressiveWarningPct":0.10}'::jsonb)$$, 'ck_replan_trigger_type');

\echo ''
\echo '#### D5. goal 당 ACTIVE plan_version 1개 ####'
SELECT t_fail('[D5] ACTIVE 2개', $$SELECT mk_pv(11,11,'ACTIVE',now())$$,
              'uq_plan_version_active_per_goal');

\echo ''
\echo '#### D6. 값 정합성 CHECK ####'
SELECT t_fail('[D6] 분위수 역전 (p50 < p25)', $$
    INSERT INTO plan_option_percentile_bands
    VALUES (1,1,'CUMULATIVE_SAVINGS',100,500,300,700,900)$$,
    'ck_percentile_band_monotonic');
SELECT t_ok  ('[D6] 분위수 정상', $$
    INSERT INTO plan_option_percentile_bands
    VALUES (1,1,'CUMULATIVE_SAVINGS',100,300,500,700,900)$$);
SELECT t_fail('[D6] month_index 0', $$
    INSERT INTO plan_option_percentile_bands
    VALUES (1,0,'CUMULATIVE_SAVINGS',100,300,500,700,900)$$,
    'ck_percentile_band_month_index');
SELECT t_fail('[D6] simulation_coverage 1 초과', $$
    INSERT INTO plan_options (plan_version_id,option_type,nominal_level,
      recommended_monthly_spending,required_reduction_rate,simulation_coverage,
      historical_feasibility_ratio)
    VALUES (5,'PRESET',0.95,900000,0.2,1.5,0.3)$$, 'ck_plan_option_ratios');
SELECT t_ok  ('[D6] required_reduction_rate 음수 (소득 증가 케이스)', $$
    INSERT INTO plan_options (plan_version_id,option_type,nominal_level,
      recommended_monthly_spending,required_reduction_rate,simulation_coverage,
      historical_feasibility_ratio)
    VALUES (5,'PRESET',0.70,1400000,-0.12,0.70,0.85)$$);
SELECT t_fail('[D6] ACTIVE 인데 activated_at 없음', $$SELECT mk_pv(12,12,'ACTIVE')$$,
              'ck_plan_version_activated_at');
SELECT t_fail('[D6] INFEASIBLE 인데 사유 없음', $$SELECT mk_pv(13,13,'INFEASIBLE')$$,
              'ck_plan_version_infeasible_reason');
SELECT t_ok  ('[D6] INFEASIBLE + 사유',
              $$SELECT mk_pv(14,14,'INFEASIBLE',NULL,'TRIGGERED_REPLAN','A<0: 가용예산 음수')$$);
SELECT t_fail('[D6] status 만 ACTIVE 로 UPDATE',
              $$UPDATE plan_versions SET status='ACTIVE' WHERE id=4$$,
              'ck_plan_version_activated_at');
SELECT t_fail('[D6] user_decision 만 있고 decided_at 없음',
              $$UPDATE replan_events SET user_decision='ACCEPT_NEW_PLAN'
                 WHERE trigger_type='MONTHLY_REGULAR'$$, 'ck_replan_decision_pair');
SELECT t_ok  ('[D6] user_decision + decided_at 동시',
              $$UPDATE replan_events SET user_decision='ACCEPT_NEW_PLAN', decided_at=now()
                 WHERE trigger_type='MONTHLY_REGULAR'$$);
SELECT t_fail('[D6] 이미 제안된 버전을 또 제안', $$
    INSERT INTO replan_events (goal_id,source_plan_version_id,proposed_plan_version_id,
      trigger_type,trigger_details)
    VALUES (1,1,9,'USER_REQUESTED','{"p95SampleSize":100,"shockRatio":0.15,"driftRatio":1.20,"checkpointDays":[7,14,21],"aggressiveWarningPct":0.10}'::jsonb)$$, 'uq_replan_proposed_version');

\echo ''
\echo '#### ===== 무결성 패치(04) 검증 ===== ####'

\echo ''
\echo '#### I1. 교차 소유권 (남의 것을 참조할 수 있는가) ####'
INSERT INTO users (id) VALUES (3);
INSERT INTO scheduled_expenses (id,user_id,name,amount,scheduled_date,status)
     VALUES (100,3,'유저3여행',500000,'2026-08-25','PLANNED');
SELECT t_fail('[I1] 유저1 거래가 유저3 예정지출을 참조', $$
    INSERT INTO transactions (user_id,transaction_at,amount,transaction_type,category,scheduled_expense_id)
    VALUES (1,'2026-08-25 12:00+09',500000,'PAYMENT','여행',100)$$,
    'fk_transaction_scheduled_expense_same_user');
SELECT t_ok('[I1] 유저3 거래가 유저3 예정지출을 참조', $$
    INSERT INTO transactions (user_id,transaction_at,amount,transaction_type,category,scheduled_expense_id)
    VALUES (3,'2026-08-25 12:00+09',500000,'PAYMENT','여행',100)$$);

INSERT INTO financial_goals (id,user_id,name,target_amount,current_saved_amount,target_date,status)
     VALUES (100,3,'유저3목표',5000000,0,'2027-06-30','ACTIVE');
SELECT t_ok('[I1] 유저3 목표의 계획 생성', $$
    INSERT INTO plan_versions (id,goal_id,version_no,generation_type,status,as_of_date,
      monthly_income_snapshot,monthly_fixed_cost_snapshot,target_amount_snapshot,
      current_saved_snapshot,target_date_snapshot,available_variable_budget,
      current_avg_variable_spending,policy_snapshot)
    VALUES (100,100,1,'INITIAL','PROPOSED','2026-08-01',3000000,1200000,5000000,0,
            '2027-06-30',8000000,1200000,'{"p95SampleSize":100}')$$);
SELECT t_fail('[I1] goal 1 이벤트가 goal 100 의 계획을 source 로', $$
    INSERT INTO replan_events (goal_id,source_plan_version_id,trigger_type,trigger_details)
    VALUES (1,100,'USER_REQUESTED','{}')$$, 'fk_replan_source_same_goal');

\echo ''
\echo '#### I2. 취소된 예정지출에 매칭된 거래는 bootstrap 으로 복귀하는가 ####'
DELETE FROM transactions WHERE user_id=3;
INSERT INTO transactions (user_id,transaction_at,amount,transaction_type,category,scheduled_expense_id)
VALUES (3,'2026-08-05 12:00+09',300000,'PAYMENT','식비',NULL),
       (3,'2026-08-25 12:00+09',500000,'PAYMENT','여행',100);
SELECT t_eq('[I2] 예정지출 PLANNED 일 때 bootstrap',
       (SELECT bootstrap_eligible_spending FROM monthly_spending_summary
         WHERE user_id=3 AND year_month='2026-08-01'), 300000::bigint);
UPDATE scheduled_expenses SET status='CANCELLED' WHERE id=100;
SELECT t_eq('[I2] 예정지출 CANCELLED 후 bootstrap (거래가 일반 유동지출로 복귀)',
       (SELECT bootstrap_eligible_spending FROM monthly_spending_summary
         WHERE user_id=3 AND year_month='2026-08-01'), 800000::bigint);
SELECT t_eq('[I2] 함수도 VIEW 와 같은 값을 내는가',
       (SELECT bootstrap_eligible_spending FROM monthly_spending_window(3, '2026-08-01 00:00+09'::timestamptz)),
       800000::bigint);

\echo ''
\echo '#### I3. simulation_runs 값 검증 ####'
SELECT t_fail('[I3] 알 수 없는 method', $$
    INSERT INTO simulation_runs (plan_version_id,method,n_paths,random_seed,
      input_snapshot,input_hash,engine_version,result_summary)
    VALUES (100,'아무거나',10000,42,'{"a":1}',repeat('a',64),'v1','{"q70":1}')$$,
    'ck_simulation_method');
SELECT t_fail('[I3] n_paths 음수', $$
    INSERT INTO simulation_runs (plan_version_id,method,n_paths,random_seed,
      input_snapshot,input_hash,engine_version,result_summary)
    VALUES (100,'IID_BOOTSTRAP',-5,42,'{"a":1}',repeat('a',64),'v1','{"q70":1}')$$,
    'ck_simulation_n_paths');
SELECT t_fail('[I3] input_hash 가 SHA-256 형식이 아님', $$
    INSERT INTO simulation_runs (plan_version_id,method,n_paths,random_seed,
      input_snapshot,input_hash,engine_version,result_summary)
    VALUES (100,'IID_BOOTSTRAP',10000,42,'{"a":1}','','v1','{"q70":1}')$$,
    'ck_simulation_input_hash');
SELECT t_fail('[I3] input_snapshot 이 빈 객체', $$
    INSERT INTO simulation_runs (plan_version_id,method,n_paths,random_seed,
      input_snapshot,input_hash,engine_version,result_summary)
    VALUES (100,'IID_BOOTSTRAP',10000,42,'{}',repeat('a',64),'v1','{"q70":1}')$$,
    'ck_simulation_snapshots_not_empty');
SELECT t_ok('[I3] 정상 simulation_run', $$
    INSERT INTO simulation_runs (plan_version_id,method,n_paths,random_seed,
      input_snapshot,input_hash,engine_version,result_summary)
    VALUES (100,'IID_BOOTSTRAP',10000,42,'{"monthlyIncome":3000000}',repeat('a',64),
            'engine-1.0','{"q70":970000}')$$);

\echo ''
\echo '#### I4. 빈 문자열 / 빈 스냅샷 ####'
SELECT t_fail('[I4] 카테고리가 빈 문자열', $$
    INSERT INTO transactions (user_id,transaction_at,amount,transaction_type,category)
    VALUES (1,'2026-08-01 12:00+09',10000,'PAYMENT','')$$,
    'ck_transaction_category_not_blank');
SELECT t_fail('[I4] 목표 이름이 공백뿐', $$
    INSERT INTO financial_goals (user_id,name,target_amount,current_saved_amount,target_date,status)
    VALUES (2,'   ',1000000,0,'2027-01-01','CANCELLED')$$, 'ck_goal_name_not_blank');
SELECT t_fail('[I4] policy_snapshot 이 빈 객체', $$
    INSERT INTO plan_versions (goal_id,version_no,generation_type,status,as_of_date,
      monthly_income_snapshot,monthly_fixed_cost_snapshot,target_amount_snapshot,
      current_saved_snapshot,target_date_snapshot,available_variable_budget,
      current_avg_variable_spending,policy_snapshot)
    VALUES (100,99,'INITIAL','STALE','2026-08-01',3000000,1200000,5000000,0,
            '2027-06-30',8000000,1200000,'{}')$$,
    'ck_plan_version_policy_snapshot_not_empty');

\echo ''
\echo '#### I5. 타임존 고정 확인 (ALTER DATABASE SET timezone) ####'
SELECT t_eq('[I5] DB 타임존', current_setting('TimeZone'), 'Asia/Seoul');

\echo ''
\echo '#### 정리 ####'
DROP FUNCTION IF EXISTS mk_pv(int,int,text,timestamptz,text,text);
DROP FUNCTION IF EXISTS t_fail(text,text,text);
DROP FUNCTION IF EXISTS t_ok(text,text);
DROP FUNCTION IF EXISTS t_eq(text,anyelement,anyelement);
\echo '검증 종료. 실패 건수를 세려면:'
\echo '   psql -d <db> -f 03_verify_v2_4.sql 2>&1 | grep -cE "NOTICE: *.FAIL."'
\echo '   -> 0 이 나와야 정상.'
