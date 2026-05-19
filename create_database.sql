-- ============================================================
-- SPX Trading Bot — PostgreSQL Schema v3.1 (PRODUCTION)
-- ============================================================

-- ========================================
-- 1. Create database (skip if exists)
-- ========================================
SELECT 'CREATE DATABASE "SPX_bot_ibkr"
    WITH OWNER = postgres
    ENCODING ''UTF8''
    TEMPLATE template0
    LC_COLLATE = ''en_US.UTF-8''
    LC_CTYPE   = ''en_US.UTF-8'''
WHERE NOT EXISTS (
    SELECT FROM pg_database WHERE datname = 'SPX_bot_ibkr'
)\gexec


-- ========================================
-- 2. Create application user (skip if exists)
-- ========================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'imod98') THEN
        CREATE ROLE imod98 WITH
            LOGIN
            PASSWORD 'rrRR@@102030'
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT;
        RAISE NOTICE '✅ User imod98 created';
    ELSE
        RAISE NOTICE 'ℹ️ User imod98 already exists — skipping';
    END IF;
END
$$;


-- ========================================
-- 3. Grant database-level privileges
-- ========================================
GRANT CONNECT, CREATE, TEMP ON DATABASE "SPX_bot_ibkr" TO imod98;

\connect "SPX_bot_ibkr"

GRANT USAGE, CREATE ON SCHEMA public TO imod98;
GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA public TO imod98;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO imod98;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES    TO imod98;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO imod98;


-- ============================================================
-- 4. TABLES (IF NOT EXISTS — safe to re-run)
-- ============================================================

-- ────────────────────────────────────────────────────────────
-- 4.1 app_settings (legacy — kept for backward compat)
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS app_settings (
    id          SERIAL      PRIMARY KEY,
    ai_enabled  BOOLEAN     NOT NULL DEFAULT true,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


-- ────────────────────────────────────────────────────────────
-- 4.2 deals (Trade lifecycle)
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS deals (
    id                    BIGSERIAL    PRIMARY KEY,
    version               BIGINT       NOT NULL DEFAULT 0,

    -- Contract identification
    symbol                VARCHAR(50),
    option_type           VARCHAR(10),
    strike                NUMERIC(18,4),
    expiry_date           DATE,

    -- State machine
    status                VARCHAR(20),

    -- Prices
    prepare_price         NUMERIC(18,4),
    entry_signal_price    NUMERIC(18,4),
    entry_price           NUMERIC(18,4),
    entry_min_price       NUMERIC(18,4),
    entry_max_price       NUMERIC(18,4),
    tp_price              NUMERIC(18,4),
    sl_price              NUMERIC(18,4),
    current_price         NUMERIC(18,4),

    -- Telegram link
    telegram_message_id   BIGINT,

    -- IBKR identifiers
    ibkr_contract_id      INTEGER,
    ibkr_entry_order_id   INTEGER,
    ibkr_tp_order_id      INTEGER,
    ibkr_sl_order_id      INTEGER,
    ibkr_exit_order_id    INTEGER,

    -- Execution metadata
    filled_qty            INTEGER,
    signal_received_at    TIMESTAMPTZ,
    order_sent_at         TIMESTAMPTZ,
    filled_at             TIMESTAMPTZ,

    -- Timestamps
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- CHECK constraints (drop+add to ensure correct version)
ALTER TABLE deals DROP CONSTRAINT IF EXISTS chk_deals_status;
ALTER TABLE deals ADD CONSTRAINT chk_deals_status
    CHECK (status IS NULL OR status IN (
        'ENTRY_PENDING', 'ENTERED', 'CLOSED', 'CANCELLED', 'FAILED'
    ));

ALTER TABLE deals DROP CONSTRAINT IF EXISTS chk_deals_option_type;
ALTER TABLE deals ADD CONSTRAINT chk_deals_option_type
    CHECK (option_type IS NULL OR option_type IN ('CALL', 'PUT'));

ALTER TABLE deals DROP CONSTRAINT IF EXISTS chk_deals_strike;
ALTER TABLE deals ADD CONSTRAINT chk_deals_strike
    CHECK (strike IS NULL OR (strike >= 1000 AND strike <= 10000));

ALTER TABLE deals DROP CONSTRAINT IF EXISTS chk_deals_prices_positive;
ALTER TABLE deals ADD CONSTRAINT chk_deals_prices_positive
    CHECK (
        (entry_signal_price IS NULL OR entry_signal_price > 0) AND
        (entry_price        IS NULL OR entry_price        > 0) AND
        (tp_price           IS NULL OR tp_price           > 0) AND
        (sl_price           IS NULL OR sl_price           > 0)
    );


-- ────────────────────────────────────────────────────────────
-- 4.3 deal_events (Audit log)
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS deal_events (
    id           BIGSERIAL   PRIMARY KEY,
    deal_id      BIGINT      NOT NULL REFERENCES deals(id) ON DELETE CASCADE,
    event_type   VARCHAR(50),
    event_price  NUMERIC(18,4),
    raw_message  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


-- ────────────────────────────────────────────────────────────
-- 4.4 processed_messages (DB-backed Idempotency)
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS processed_messages (
    message_id    BIGINT       PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);


-- ────────────────────────────────────────────────────────────
-- 4.5 pending_entries (PREP-then-ENTRY workflow) ⭐
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS pending_entries (
    id                  BIGSERIAL    PRIMARY KEY,
    version             BIGINT       NOT NULL DEFAULT 0,    -- ⭐ optimistic locking

    telegram_message_id BIGINT       NOT NULL UNIQUE,

    option_type         VARCHAR(10)  NOT NULL,
    strike              NUMERIC(18,4) NOT NULL,
    entry_price         NUMERIC(18,4) NOT NULL,
    expiry_date         DATE         NOT NULL,

    raw_text            TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    consumed_at         TIMESTAMPTZ,
    consumed_by_deal_id BIGINT,
    consumed_reason     VARCHAR(50)
);

-- Constraints
ALTER TABLE pending_entries DROP CONSTRAINT IF EXISTS chk_pending_option_type;
ALTER TABLE pending_entries ADD CONSTRAINT chk_pending_option_type
    CHECK (option_type IN ('CALL', 'PUT'));

ALTER TABLE pending_entries DROP CONSTRAINT IF EXISTS chk_pending_strike;
ALTER TABLE pending_entries ADD CONSTRAINT chk_pending_strike
    CHECK (strike >= 1000 AND strike <= 10000);

ALTER TABLE pending_entries DROP CONSTRAINT IF EXISTS chk_pending_price;
ALTER TABLE pending_entries ADD CONSTRAINT chk_pending_price
    CHECK (entry_price > 0 AND entry_price <= 500);

ALTER TABLE pending_entries DROP CONSTRAINT IF EXISTS chk_pending_consumed_reason;
ALTER TABLE pending_entries ADD CONSTRAINT chk_pending_consumed_reason
    CHECK (consumed_reason IS NULL OR consumed_reason IN (
        'matched_entry', 'admin_cancel', 'admin_cancel_standalone',
        'expired_eod', 'manual'
    ));


-- ────────────────────────────────────────────────────────────
-- 4.6 risk_state (Daily kill switch — 4 consecutive losses) ⭐ NEW v3.1
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS risk_state (
    id                          INTEGER     PRIMARY KEY DEFAULT 1,
    consecutive_losses          INTEGER     NOT NULL DEFAULT 0,
    kill_switch_active          BOOLEAN     NOT NULL DEFAULT FALSE,
    kill_switch_activated_at    TIMESTAMPTZ,
    kill_switch_reason          VARCHAR(100),
    last_deal_id                BIGINT,
    last_deal_pnl               NUMERIC(18,4),
    last_updated                TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_risk_state_singleton CHECK (id = 1),
    CONSTRAINT chk_consecutive_losses CHECK (consecutive_losses >= 0)
);

-- Seed singleton row (idempotent)
INSERT INTO risk_state (id, consecutive_losses, kill_switch_active)
VALUES (1, 0, FALSE)
ON CONFLICT (id) DO NOTHING;


-- ============================================================
-- 5. INDEXES (idempotent)
-- ============================================================

-- deals
CREATE INDEX IF NOT EXISTS idx_deals_status            ON deals(status);
CREATE INDEX IF NOT EXISTS idx_deals_telegram_msg      ON deals(telegram_message_id);
CREATE INDEX IF NOT EXISTS idx_deals_created_at        ON deals(created_at);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_entry_order  ON deals(ibkr_entry_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_tp_order     ON deals(ibkr_tp_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_sl_order     ON deals(ibkr_sl_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_exit_order   ON deals(ibkr_exit_order_id);

CREATE INDEX IF NOT EXISTS idx_deals_match
    ON deals(status, symbol, strike, option_type, expiry_date);

CREATE UNIQUE INDEX IF NOT EXISTS uq_deals_telegram_msg
    ON deals(telegram_message_id)
    WHERE telegram_message_id IS NOT NULL;

-- deal_events
CREATE INDEX IF NOT EXISTS idx_deal_events_deal_id     ON deal_events(deal_id);
CREATE INDEX IF NOT EXISTS idx_deal_events_type        ON deal_events(event_type);
CREATE INDEX IF NOT EXISTS idx_deal_events_created_at  ON deal_events(created_at);

-- processed_messages
CREATE INDEX IF NOT EXISTS idx_processed_messages_at   ON processed_messages(processed_at);

-- pending_entries
CREATE INDEX IF NOT EXISTS idx_pending_active_lookup
    ON pending_entries(option_type, created_at DESC)
    WHERE consumed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_pending_created_at      ON pending_entries(created_at);
CREATE INDEX IF NOT EXISTS idx_pending_consumed_at     ON pending_entries(consumed_at);


-- ============================================================
-- 6. FUNCTIONS & TRIGGERS
-- ============================================================

-- updated_at trigger for deals
CREATE OR REPLACE FUNCTION fn_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_deals_set_updated_at ON deals;
CREATE TRIGGER trg_deals_set_updated_at
    BEFORE UPDATE ON deals
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

-- Cleanup function for processed_messages
CREATE OR REPLACE FUNCTION cleanup_processed_messages(days_to_keep INTEGER DEFAULT 7)
RETURNS INTEGER AS $$
DECLARE
    rows_deleted INTEGER;
BEGIN
    DELETE FROM processed_messages
     WHERE processed_at < NOW() - (days_to_keep || ' days')::INTERVAL;
    GET DIAGNOSTICS rows_deleted = ROW_COUNT;
    RETURN rows_deleted;
END;
$$ LANGUAGE plpgsql;

-- Cleanup function for old consumed pending_entries
CREATE OR REPLACE FUNCTION cleanup_old_pending_entries(days_to_keep INTEGER DEFAULT 30)
RETURNS INTEGER AS $$
DECLARE
    rows_deleted INTEGER;
BEGIN
    DELETE FROM pending_entries
     WHERE consumed_at IS NOT NULL
       AND consumed_at < NOW() - (days_to_keep || ' days')::INTERVAL;
    GET DIAGNOSTICS rows_deleted = ROW_COUNT;
    RETURN rows_deleted;
END;
$$ LANGUAGE plpgsql;


-- ============================================================
-- 7. GRANT permissions to imod98
-- ============================================================
GRANT ALL PRIVILEGES ON TABLE app_settings        TO imod98;
GRANT ALL PRIVILEGES ON TABLE deals               TO imod98;
GRANT ALL PRIVILEGES ON TABLE deal_events         TO imod98;
GRANT ALL PRIVILEGES ON TABLE processed_messages  TO imod98;
GRANT ALL PRIVILEGES ON TABLE pending_entries     TO imod98;
GRANT ALL PRIVILEGES ON TABLE risk_state          TO imod98;

GRANT USAGE, SELECT ON SEQUENCE app_settings_id_seq      TO imod98;
GRANT USAGE, SELECT ON SEQUENCE deals_id_seq             TO imod98;
GRANT USAGE, SELECT ON SEQUENCE deal_events_id_seq       TO imod98;
GRANT USAGE, SELECT ON SEQUENCE pending_entries_id_seq   TO imod98;

GRANT EXECUTE ON FUNCTION fn_set_updated_at()                    TO imod98;
GRANT EXECUTE ON FUNCTION cleanup_processed_messages(INTEGER)    TO imod98;
GRANT EXECUTE ON FUNCTION cleanup_old_pending_entries(INTEGER)   TO imod98;


-- ============================================================
-- 8. SEED DATA
-- ============================================================
INSERT INTO app_settings (ai_enabled, updated_at)
SELECT true, NOW()
WHERE NOT EXISTS (SELECT 1 FROM app_settings);


-- ============================================================
-- 9. VERIFICATION
-- ============================================================
SELECT table_name, COUNT(*) AS row_count FROM (
    SELECT 'app_settings'       AS table_name FROM app_settings        UNION ALL
    SELECT 'deals'               AS table_name FROM deals               UNION ALL
    SELECT 'deal_events'         AS table_name FROM deal_events         UNION ALL
    SELECT 'processed_messages'  AS table_name FROM processed_messages  UNION ALL
    SELECT 'pending_entries'     AS table_name FROM pending_entries     UNION ALL
    SELECT 'risk_state'          AS table_name FROM risk_state
) t
GROUP BY table_name
ORDER BY table_name;

\echo ''
\echo '════════════════════════════════════════════════════════════════'
\echo '  ✅ SPX Trading Bot — Schema v3.1 ready'
\echo '════════════════════════════════════════════════════════════════'
\echo ''
\echo '  Tables (6):'
\echo '    • app_settings       — Legacy config'
\echo '    • deals              — Trade lifecycle'
\echo '    • deal_events        — Audit log'
\echo '    • processed_messages — Telegram idempotency'
\echo '    • pending_entries    — PREP storage ⭐'
\echo '    • risk_state         — Kill switch ⭐ NEW v3.1'
\echo ''
\echo '════════════════════════════════════════════════════════════════'
