-- =====================================================================
-- 데모 시드 스크립트 맨 끝에 붙일 것
-- ---------------------------------------------------------------------
-- 왜 필요한가
--   SERIAL 의 nextval 은 DEFAULT 를 사용할 때만 호출된다. 데모 데이터를
--     INSERT INTO plan_versions (id, ...) VALUES (1, ...)
--   처럼 id 명시로 넣으면 시퀀스는 그대로 1 이고 is_called = false 다.
--   이후 앱이 id 없이 INSERT 하는 첫 순간
--     ERROR: duplicate key value ... Key (id)=(1) already exists
--   가 난다.
--
--   기획서 8-7 의 데모 시드(재계획 이력이 여러 번 쌓인 유저)는 replan_events
--   가 특정 plan_version 을 가리켜야 하므로 id 명시가 불가피하다. 즉 이
--   문제는 반드시 발생한다.
--
--   터지는 시점이 하필 심사위원이 [계획 생성] / [옵션 선택] 을 누르는 순간이다.
--   본선 URL 접근 기간(9/7 11:00 ~ 9/11 23:59)에 500 이 뜨면 그대로 감점이다.
--
-- 동작
--   public 스키마의 모든 SERIAL/IDENTITY 컬럼을 자동으로 찾아 max(id) 로
--   맞춘다. 테이블이 늘어나도 이 파일을 수정할 필요가 없다.
--   빈 테이블은 is_called = false 로 두어 다음 값이 1 이 되게 한다.
--   plan_option_percentile_bands 는 복합 PK 라 시퀀스가 없어 자동 제외된다.
-- =====================================================================

DO $$
DECLARE
    r        record;
    seq_name text;
    n        int := 0;
BEGIN
    FOR r IN
        SELECT c.oid::regclass AS tbl, a.attname AS col
          FROM pg_class     c
          JOIN pg_attribute a ON a.attrelid = c.oid
         WHERE c.relkind      = 'r'
           AND c.relnamespace = 'public'::regnamespace
           AND a.attnum       > 0
           AND NOT a.attisdropped
    LOOP
        seq_name := pg_get_serial_sequence(r.tbl::text, r.col);
        CONTINUE WHEN seq_name IS NULL;

        EXECUTE format(
            'SELECT setval(%L, coalesce((SELECT max(%I) FROM %s), 1), (SELECT count(*) > 0 FROM %s))',
            seq_name, r.col, r.tbl, r.tbl);

        n := n + 1;
        RAISE NOTICE 'sequence 정렬: %  (%.%)', seq_name, r.tbl, r.col;
    END LOOP;

    RAISE NOTICE '총 % 개 시퀀스 정렬 완료', n;
END $$;

-- 확인용 (정렬 후 각 시퀀스의 다음 값이 max(id)+1 인지)
--   SELECT schemaname, sequencename, last_value, last_value IS NOT NULL AS called
--     FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename;
