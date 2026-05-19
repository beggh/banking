-- ─────────────────────────────────────────────────────────────────────────────
-- Banking Schema
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TYPE account_status    AS ENUM ('ACTIVE', 'FROZEN', 'CLOSED');
CREATE TYPE transaction_type  AS ENUM ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL');
CREATE TYPE transaction_status AS ENUM ('PENDING', 'SUCCESS', 'FAILED', 'REVERSED');
CREATE TYPE audit_operation   AS ENUM ('CREDIT', 'DEBIT', 'REVERSAL_CREDIT', 'REVERSAL_DEBIT');
CREATE TYPE audit_outcome     AS ENUM ('SUCCESS', 'FAILURE');
CREATE TYPE failure_reason    AS ENUM (
    'INSUFFICIENT_FUNDS', 'ACCOUNT_NOT_FOUND', 'ACCOUNT_FROZEN', 'ACCOUNT_CLOSED',
    'INVALID_AMOUNT', 'SAME_ACCOUNT_TRANSFER', 'TRANSACTION_NOT_FOUND',
    'ALREADY_REVERSED', 'REVERSAL_NOT_ALLOWED', 'DUPLICATE_REQUEST'
);

-- ── account ───────────────────────────────────────────────────────────────────

CREATE TABLE account (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_name  VARCHAR(255)    NOT NULL,
    balance     BIGINT          NOT NULL DEFAULT 0,
    status      account_status  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- DB-level guard: balance can never go below 0
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);

-- ── transaction ───────────────────────────────────────────────────────────────

CREATE TABLE transaction (
    id                        UUID                PRIMARY KEY DEFAULT gen_random_uuid(),
    type                      transaction_type    NOT NULL,
    from_account_id           UUID                REFERENCES account(id),
    to_account_id             UUID                REFERENCES account(id),
    amount                    BIGINT              NOT NULL,
    status                    transaction_status  NOT NULL DEFAULT 'PENDING',
    idempotency_key           VARCHAR(255)        NOT NULL UNIQUE,  -- prevents duplicate submissions
    original_transaction_id   UUID                REFERENCES transaction(id),  -- set only on REVERSAL
    failure_reason            failure_reason,
    created_at                TIMESTAMPTZ         NOT NULL DEFAULT now(),

    CONSTRAINT chk_amount_positive CHECK (amount > 0),
    -- reversal must point to an original
    CONSTRAINT chk_reversal_has_original CHECK (
        type != 'REVERSAL' OR original_transaction_id IS NOT NULL
    )
);

CREATE INDEX idx_txn_from_account  ON transaction(from_account_id);
CREATE INDEX idx_txn_to_account    ON transaction(to_account_id);
CREATE INDEX idx_txn_original      ON transaction(original_transaction_id);

-- ── audit_log ─────────────────────────────────────────────────────────────────

CREATE TABLE audit_log (
    id              BIGSERIAL       PRIMARY KEY,
    transaction_id  UUID            NOT NULL REFERENCES transaction(id),
    account_id      UUID            NOT NULL REFERENCES account(id),
    operation       audit_operation NOT NULL,
    amount          BIGINT          NOT NULL,
    balance_before  BIGINT,         -- null on FAILED rows (balance was never read)
    balance_after   BIGINT,         -- null on FAILED rows
    outcome         audit_outcome   NOT NULL,
    failure_reason  failure_reason,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_txn     ON audit_log(transaction_id);
CREATE INDEX idx_audit_account ON audit_log(account_id);
