BEGIN;

CREATE TABLE savings_products (
    id            BIGSERIAL PRIMARY KEY,
    fin_co_no     VARCHAR(20) NOT NULL,
    fin_prdt_cd   VARCHAR(100) NOT NULL,
    dcls_month    CHAR(6) NOT NULL,
    kor_co_nm     VARCHAR(200) NOT NULL,
    fin_prdt_nm   VARCHAR(300) NOT NULL,
    join_way      TEXT,
    mtrt_int      TEXT,
    spcl_cnd      TEXT,
    join_deny     CHAR(1) NOT NULL,
    join_member   TEXT,
    max_limit     NUMERIC(19,0),
    active        BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (fin_co_no, fin_prdt_cd),
    CONSTRAINT ck_savings_product_dcls_month CHECK (dcls_month ~ '^[0-9]{6}$'),
    CONSTRAINT ck_savings_product_join_deny CHECK (join_deny IN ('1', '2', '3')),
    CONSTRAINT ck_savings_product_max_limit CHECK (max_limit IS NULL OR max_limit > 0)
);

CREATE TABLE savings_product_options (
    id                BIGSERIAL PRIMARY KEY,
    product_id        BIGINT NOT NULL REFERENCES savings_products(id) ON DELETE CASCADE,
    intr_rate_type    CHAR(1) NOT NULL,
    intr_rate_type_nm VARCHAR(100),
    rsrv_type         CHAR(1) NOT NULL,
    rsrv_type_nm      VARCHAR(100),
    save_trm          SMALLINT NOT NULL,
    intr_rate         NUMERIC(8,4) NOT NULL,
    intr_rate2        NUMERIC(8,4) NOT NULL,
    active            BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_id, intr_rate_type, rsrv_type, save_trm),
    CONSTRAINT ck_savings_option_rate_type CHECK (intr_rate_type IN ('S', 'M')),
    CONSTRAINT ck_savings_option_reserve_type CHECK (rsrv_type IN ('F', 'S')),
    CONSTRAINT ck_savings_option_term CHECK (save_trm BETWEEN 1 AND 120),
    CONSTRAINT ck_savings_option_rate CHECK (intr_rate >= 0 AND intr_rate2 >= intr_rate)
);

CREATE TABLE savings_product_conditions (
    id            BIGSERIAL PRIMARY KEY,
    product_id    BIGINT NOT NULL REFERENCES savings_products(id) ON DELETE CASCADE,
    label         VARCHAR(500) NOT NULL,
    bonus_rate    NUMERIC(8,4) NOT NULL,
    source        VARCHAR(10) NOT NULL,
    review_needed BOOLEAN NOT NULL,
    raw_fragment  TEXT,
    active        BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_id, source, label),
    CONSTRAINT ck_savings_condition_label CHECK (length(btrim(label)) > 0),
    CONSTRAINT ck_savings_condition_bonus CHECK (bonus_rate >= 0),
    CONSTRAINT ck_savings_condition_source CHECK (source IN ('RULE', 'LLM')),
    CONSTRAINT ck_savings_condition_review CHECK (
        (source = 'RULE' AND review_needed = false)
        OR (source = 'LLM' AND review_needed = true)
    )
);

CREATE INDEX ix_savings_products_active ON savings_products (active, fin_co_no, fin_prdt_cd);
CREATE INDEX ix_savings_options_active ON savings_product_options (product_id, active);
CREATE INDEX ix_savings_conditions_rule ON savings_product_conditions (product_id, active, source);

COMMIT;
