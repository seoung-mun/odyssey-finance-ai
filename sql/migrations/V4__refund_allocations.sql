BEGIN;

ALTER TABLE transactions
    ADD COLUMN source_id VARCHAR(50) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE transactions
    ADD CONSTRAINT ck_transaction_source_not_blank
    CHECK (length(btrim(source_id)) > 0);

DROP INDEX uq_transaction_external_id;
CREATE UNIQUE INDEX uq_transaction_external_id
    ON transactions (user_id, source_id, external_transaction_id)
    WHERE external_transaction_id IS NOT NULL;

CREATE TABLE refund_allocations (
    id                              BIGSERIAL PRIMARY KEY,
    user_id                         INTEGER NOT NULL REFERENCES users(id),
    refund_transaction_id           BIGINT NOT NULL,
    payment_transaction_id          BIGINT,
    payment_source_id               VARCHAR(50) NOT NULL DEFAULT 'MANUAL',
    payment_external_transaction_id VARCHAR(100) NOT NULL,
    amount                           NUMERIC(19,0) NOT NULL,
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_refund_allocation_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_refund_allocation_payment_source_not_blank
        CHECK (length(btrim(payment_source_id)) > 0),
    CONSTRAINT ck_refund_allocation_payment_external_id_not_blank
        CHECK (length(btrim(payment_external_transaction_id)) > 0),
    CONSTRAINT fk_refund_allocation_refund_owner
        FOREIGN KEY (refund_transaction_id, user_id)
        REFERENCES transactions (id, user_id),
    CONSTRAINT fk_refund_allocation_payment_owner
        FOREIGN KEY (payment_transaction_id, user_id)
        REFERENCES transactions (id, user_id)
);

CREATE INDEX ix_refund_allocations_refund
    ON refund_allocations (refund_transaction_id);
CREATE INDEX ix_refund_allocations_payment
    ON refund_allocations (payment_transaction_id)
    WHERE payment_transaction_id IS NOT NULL;
CREATE INDEX ix_refund_allocations_pending
    ON refund_allocations (user_id, payment_source_id, payment_external_transaction_id)
    WHERE payment_transaction_id IS NULL;

CREATE OR REPLACE FUNCTION protect_allocated_transaction_identity()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.user_id, OLD.amount, OLD.transaction_type, OLD.source_id, OLD.external_transaction_id)
       IS DISTINCT FROM
       (NEW.user_id, NEW.amount, NEW.transaction_type, NEW.source_id, NEW.external_transaction_id)
       AND EXISTS (
           SELECT 1
             FROM refund_allocations
            WHERE refund_transaction_id = OLD.id OR payment_transaction_id = OLD.id
       ) THEN
        RAISE EXCEPTION 'allocated transaction identity is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_allocated_transaction_immutable';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER allocated_transaction_identity
BEFORE UPDATE OF user_id, amount, transaction_type, source_id, external_transaction_id
ON transactions
FOR EACH ROW EXECUTE FUNCTION protect_allocated_transaction_identity();

CREATE OR REPLACE FUNCTION enforce_refund_allocation_integrity()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    refund_row transactions%ROWTYPE;
    payment_row transactions%ROWTYPE;
    allocated NUMERIC(19,0);
    locked_transaction_id BIGINT;
BEGIN
    FOR locked_transaction_id IN
        SELECT DISTINCT id
          FROM transactions
         WHERE id IN (NEW.refund_transaction_id, NEW.payment_transaction_id)
         ORDER BY id
    LOOP
        PERFORM id
          FROM transactions
         WHERE id = locked_transaction_id
         FOR UPDATE;
    END LOOP;

    SELECT * INTO STRICT refund_row
      FROM transactions
     WHERE id = NEW.refund_transaction_id;

    IF refund_row.transaction_type <> 'REFUND' THEN
        RAISE EXCEPTION 'refund allocation source must be REFUND'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_refund_allocation_refund_type';
    END IF;

    SELECT coalesce(sum(amount), 0) + NEW.amount INTO allocated
      FROM refund_allocations
     WHERE refund_transaction_id = NEW.refund_transaction_id
       AND id IS DISTINCT FROM NEW.id;
    IF allocated > refund_row.amount THEN
        RAISE EXCEPTION 'refund allocation exceeds refund amount'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_refund_allocation_refund_total';
    END IF;

    IF NEW.payment_transaction_id IS NOT NULL THEN
        SELECT * INTO STRICT payment_row
          FROM transactions
         WHERE id = NEW.payment_transaction_id;

        IF payment_row.transaction_type <> 'PAYMENT' THEN
            RAISE EXCEPTION 'refund allocation target must be PAYMENT'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_refund_allocation_payment_type';
        END IF;
        IF payment_row.source_id <> NEW.payment_source_id
           OR payment_row.external_transaction_id IS DISTINCT FROM NEW.payment_external_transaction_id THEN
            RAISE EXCEPTION 'refund allocation payment identity mismatch'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_refund_allocation_payment_identity';
        END IF;

        SELECT coalesce(sum(amount), 0) + NEW.amount INTO allocated
          FROM refund_allocations
         WHERE payment_transaction_id = NEW.payment_transaction_id
           AND id IS DISTINCT FROM NEW.id;
        IF allocated > payment_row.amount THEN
            RAISE EXCEPTION 'refund allocation exceeds payment amount'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_refund_allocation_payment_total';
        END IF;
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER refund_allocation_integrity
BEFORE INSERT OR UPDATE ON refund_allocations
FOR EACH ROW EXECUTE FUNCTION enforce_refund_allocation_integrity();

DROP VIEW monthly_category_spending;
DROP VIEW monthly_spending_summary;

CREATE VIEW monthly_spending_summary AS
WITH resolved_by_payment AS (
    SELECT payment_transaction_id, sum(amount)::BIGINT AS amount
      FROM refund_allocations
     WHERE payment_transaction_id IS NOT NULL
     GROUP BY payment_transaction_id
),
resolved_by_refund AS (
    SELECT refund_transaction_id, sum(amount)::BIGINT AS amount
      FROM refund_allocations
     WHERE payment_transaction_id IS NOT NULL
     GROUP BY refund_transaction_id
),
monthly_entries AS (
    SELECT
        t.user_id,
        date_trunc('month', timezone('Asia/Seoul', t.transaction_at))::DATE AS year_month,
        t.amount::BIGINT AS gross_payment_spending,
        coalesce(pa.amount, 0)::BIGINT AS linked_refund_amount,
        0::BIGINT AS unmatched_refund_inflow,
        (t.amount - coalesce(pa.amount, 0))::BIGINT AS adjusted_consumption,
        (-t.amount)::BIGINT AS net_cash_flow,
        CASE WHEN se.id IS NOT NULL AND se.status <> 'CANCELLED' THEN 0
             ELSE t.amount - coalesce(pa.amount, 0) END::BIGINT AS bootstrap_eligible_spending
      FROM transactions t
      LEFT JOIN resolved_by_payment pa ON pa.payment_transaction_id = t.id
      LEFT JOIN scheduled_expenses se ON se.id = t.scheduled_expense_id
     WHERE t.transaction_type = 'PAYMENT'
    UNION ALL
    SELECT
        t.user_id,
        date_trunc('month', timezone('Asia/Seoul', t.transaction_at))::DATE,
        0::BIGINT,
        0::BIGINT,
        (t.amount - coalesce(ra.amount, 0))::BIGINT,
        0::BIGINT,
        t.amount::BIGINT,
        0::BIGINT
      FROM transactions t
      LEFT JOIN resolved_by_refund ra ON ra.refund_transaction_id = t.id
     WHERE t.transaction_type = 'REFUND'
)
SELECT
    user_id,
    year_month,
    sum(adjusted_consumption)::BIGINT AS total_variable_spending,
    sum(gross_payment_spending)::BIGINT AS gross_payment_spending,
    sum(linked_refund_amount)::BIGINT AS linked_refund_amount,
    sum(unmatched_refund_inflow)::BIGINT AS unmatched_refund_inflow,
    sum(adjusted_consumption)::BIGINT AS adjusted_consumption,
    sum(net_cash_flow)::BIGINT AS net_cash_flow,
    sum(bootstrap_eligible_spending)::BIGINT AS bootstrap_eligible_spending
  FROM monthly_entries
 GROUP BY user_id, year_month;

COMMENT ON VIEW monthly_spending_summary IS
    '월 경계 Asia/Seoul. 연결 환불은 원 PAYMENT 월 소비를 조정하고 미연결 환불은 REFUND 월 현금 유입에만 반영한다.';

CREATE VIEW monthly_category_spending AS
WITH resolved_by_payment AS (
    SELECT payment_transaction_id, sum(amount)::BIGINT AS amount
      FROM refund_allocations
     WHERE payment_transaction_id IS NOT NULL
     GROUP BY payment_transaction_id
)
SELECT
    t.user_id,
    date_trunc('month', timezone('Asia/Seoul', t.transaction_at))::DATE AS year_month,
    t.category,
    sum(t.amount - coalesce(pa.amount, 0))::BIGINT AS amount
  FROM transactions t
  LEFT JOIN resolved_by_payment pa ON pa.payment_transaction_id = t.id
 WHERE t.transaction_type = 'PAYMENT'
 GROUP BY 1, 2, 3;

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
    WITH resolved_by_payment AS (
        SELECT payment_transaction_id, sum(amount)::BIGINT AS amount
          FROM refund_allocations
         WHERE payment_transaction_id IS NOT NULL
         GROUP BY payment_transaction_id
    )
    SELECT
        date_trunc('month', timezone('Asia/Seoul', t.transaction_at))::DATE,
        sum(t.amount - coalesce(pa.amount, 0))::BIGINT,
        sum(CASE WHEN se.id IS NOT NULL AND se.status <> 'CANCELLED' THEN 0
                 ELSE t.amount - coalesce(pa.amount, 0) END)::BIGINT
      FROM transactions t
      LEFT JOIN resolved_by_payment pa ON pa.payment_transaction_id = t.id
      LEFT JOIN scheduled_expenses se ON se.id = t.scheduled_expense_id
     WHERE t.user_id = p_user_id
       AND t.transaction_type = 'PAYMENT'
       AND t.transaction_at >= p_from
       AND (p_to IS NULL OR t.transaction_at < p_to)
     GROUP BY 1
     ORDER BY 1;
$$;

COMMENT ON FUNCTION monthly_spending_window IS
    'MC 입력용 PAYMENT 원월 연결 환불 조정 소비. 미연결 REFUND와 미래 환불은 포함하지 않는다.';

COMMIT;
