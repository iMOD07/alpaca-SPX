-- ============================================================
-- SPX Trading Bot — Complete PostgreSQL Schema v3.0
-- ============================================================
-- to run (DROPS AND RECREATES EVERYTHING):
--   psql -U postgres -v ON_ERROR_STOP=1 -f create_database.sql
--
-- This script:
--   1. DROPS the existing database (if present) — ⚠️ DESTRUCTIVE
--   2. Creates fresh database + user
--   3. Creates ALL tables (app_settings, deals, deal_events,
--      processed_messages, pending_entries)
--   4. Compatible with spring.jpa.hibernate.ddl-auto=validate
--
-- ⚠️ WARNING: This will delete ALL existing data!
-- ============================================================


-- ========================================
-- 1. Drop existing database (clean slate)
-- ========================================
-- Terminate any active connections first
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'SPX_bot_ibkr' AND pid <> pg_backend_pid();

DROP DATABASE IF EXISTS "SPX_bot_ibkr";


-- ========================================
-- 2. Create database
-- ========================================
CREATE DATABASE "SPX_bot_ibkr"
    WITH OWNER = postgres
    ENCODING 'UTF8'
    TEMPLATE template0
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE   = 'en_US.UTF-8';


-- ========================================
-- 3. Create application user (skip if exists)
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
    END IF;
END
$$;


-- ========================================
-- 4. Grant database-level privileges
-- ========================================
GRANT CONNECT, CREATE, TEMP ON DATABASE "SPX_bot_ibkr" TO imod98;

\connect "SPX_bot_ibkr"

GRANT USAGE, CREATE ON SCHEMA public TO imod98;
GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA public TO imod98;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO imod98;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES    TO imod98;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO imod98;


-- ============================================================
-- 5. TABLES
-- ============================================================

-- ────────────────────────────────────────────────────────────
-- 5.1 app_settings
-- ────────────────────────────────────────────────────────────
-- AppSettings.java:
--   id          Integer → INTEGER (SERIAL — entity uses Integer + IDENTITY)
--   aiEnabled   boolean → BOOLEAN
--   updatedAt   Instant → TIMESTAMPTZ
CREATE TABLE app_settings (
    id          SERIAL      PRIMARY KEY,
    ai_enabled  BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);


-- ────────────────────────────────────────────────────────────
-- 5.2 deals
-- ────────────────────────────────────────────────────────────
-- Deal.java: aligned 1:1 with JPA entity
--   All Instant fields  → TIMESTAMPTZ
--   All BigDecimal      → NUMERIC(18,4)
--   All Long FKs/IDs    → BIGINT
--   All Integer fields  → INTEGER
--   status              → VARCHAR (enum stored as STRING)
CREATE TABLE deals (
    id                    BIGSERIAL    PRIMARY KEY,

    -- @Version (optimistic locking)
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

    -- Telegram link (UNIQUE — enforced via partial index below)
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

    -- Timestamps (set in @PrePersist / @PreUpdate)
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Data integrity constraints (v2.1+)
    CONSTRAINT chk_deals_status
        CHECK (status IS NULL OR status IN (
            'ENTRY_PENDING', 'ENTERED', 'CLOSED', 'CANCELLED', 'FAILED'
        )),
    CONSTRAINT chk_deals_option_type
        CHECK (option_type IS NULL OR option_type IN ('CALL', 'PUT')),
    CONSTRAINT chk_deals_strike
        CHECK (strike IS NULL OR (strike >= 1000 AND strike <= 10000)),
    CONSTRAINT chk_deals_prices_positive
        CHECK (
            (entry_signal_price IS NULL OR entry_signal_price > 0) AND
            (entry_price        IS NULL OR entry_price        > 0) AND
            (tp_price           IS NULL OR tp_price           > 0) AND
            (sl_price           IS NULL OR sl_price           > 0)
        )
);


-- ────────────────────────────────────────────────────────────
-- 5.3 deal_events
-- ────────────────────────────────────────────────────────────
-- DealEvent.java:
--   id           Long          → BIGINT
--   deal         Deal (FK)     → BIGINT REFERENCES deals(id)
--   eventType    enum STRING   → VARCHAR
--   eventPrice   BigDecimal    → NUMERIC(18,4)
--   rawMessage   String (TEXT) → TEXT
--   createdAt    Instant       → TIMESTAMPTZ
CREATE TABLE deal_events (
    id           BIGSERIAL   PRIMARY KEY,
    deal_id      BIGINT      NOT NULL REFERENCES deals(id) ON DELETE CASCADE,
    event_type   VARCHAR(50),
    event_price  NUMERIC(18,4),
    raw_message  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


-- ────────────────────────────────────────────────────────────
-- 5.4 processed_messages (Idempotency)
-- ────────────────────────────────────────────────────────────
-- IdempotencyService — DB-backed deduplication.
-- Survives application restarts. INSERT...ON CONFLICT DO NOTHING.
CREATE TABLE processed_messages (
    message_id    BIGINT       PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);


-- ────────────────────────────────────────────────────────────
-- 5.5 pending_entries (PREP-then-ENTRY workflow) ⭐ NEW v3
-- ────────────────────────────────────────────────────────────
-- يخزّن رسائل PREP من admin حتى تطابق رسالة ENTRY.
--
-- Lifecycle:
--   1. PREP يصل → INSERT (consumed_at = null)
--   2. ENTRY يطابق → consumed_at = now, reason='matched_entry'
--   3. Reply "إلغاء" → consumed_at = now, reason='admin_cancel'
--   4. End of trading day (4 PM ET) → consumed_at = now, reason='expired_eod'
CREATE TABLE pending_entries (
    id                  BIGSERIAL    PRIMARY KEY,

    -- Telegram link (each PREP message has unique ID)
    telegram_message_id BIGINT       NOT NULL UNIQUE,

    -- Contract data extracted from PREP TEXT (no OCR)
    option_type         VARCHAR(10)  NOT NULL,        -- CALL | PUT
    strike              NUMERIC(18,4) NOT NULL,
    entry_price         NUMERIC(18,4) NOT NULL,       -- من "حط أمر التنفيذ بسعر : 3.9"
    expiry_date         DATE         NOT NULL,

    -- Raw text (audit)
    raw_text            TEXT,

    -- Timestamps
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Consumption tracking
    consumed_at         TIMESTAMPTZ,                  -- NULL = active, SET = used
    consumed_by_deal_id BIGINT,                       -- FK to deals.id (nullable)
    consumed_reason     VARCHAR(50),                  -- matched_entry | admin_cancel | expired_eod

    -- Constraints
    CONSTRAINT chk_pending_option_type
        CHECK (option_type IN ('CALL', 'PUT')),
    CONSTRAINT chk_pending_strike
        CHECK (strike >= 1000 AND strike <= 10000),
    CONSTRAINT chk_pending_price
        CHECK (entry_price > 0 AND entry_price <= 500),
    CONSTRAINT chk_pending_consumed_reason
        CHECK (consumed_reason IS NULL OR consumed_reason IN (
            'matched_entry', 'admin_cancel', 'admin_cancel_standalone',
            'expired_eod', 'manual'
        ))
);


-- ============================================================
-- 6. INDEXES
-- ============================================================

-- ── deals indexes ──
CREATE INDEX idx_deals_status            ON deals(status);
CREATE INDEX idx_deals_telegram_msg      ON deals(telegram_message_id);
CREATE INDEX idx_deals_created_at        ON deals(created_at);
CREATE INDEX idx_deals_ibkr_entry_order  ON deals(ibkr_entry_order_id);
CREATE INDEX idx_deals_ibkr_tp_order     ON deals(ibkr_tp_order_id);
CREATE INDEX idx_deals_ibkr_sl_order     ON deals(ibkr_sl_order_id);
CREATE INDEX idx_deals_ibkr_exit_order   ON deals(ibkr_exit_order_id);

-- Composite for findByStatusAndSymbolAndStrikeAndOptionTypeAndExpiryDate
CREATE INDEX idx_deals_match
    ON deals(status, symbol, strike, option_type, expiry_date);

-- Enforce telegram_message_id uniqueness (matches @Column(unique=true))
-- Partial index allows multiple NULLs while blocking duplicates of real values
CREATE UNIQUE INDEX uq_deals_telegram_msg
    ON deals(telegram_message_id)
    WHERE telegram_message_id IS NOT NULL;

-- ── deal_events indexes ──
CREATE INDEX idx_deal_events_deal_id     ON deal_events(deal_id);
CREATE INDEX idx_deal_events_type        ON deal_events(event_type);
CREATE INDEX idx_deal_events_created_at  ON deal_events(created_at);

-- ── processed_messages indexes ──
CREATE INDEX idx_processed_messages_at   ON processed_messages(processed_at);

-- ── pending_entries indexes ⭐ ──
-- Critical: lookup active PREPs by option_type (الأكثر استخداماً)
CREATE INDEX idx_pending_active_lookup
    ON pending_entries(option_type, created_at DESC)
    WHERE consumed_at IS NULL;

-- For EOD cleanup & audit queries
CREATE INDEX idx_pending_created_at      ON pending_entries(created_at);
CREATE INDEX idx_pending_consumed_at     ON pending_entries(consumed_at);


-- ============================================================
-- 7. FUNCTIONS & TRIGGERS
-- ============================================================

-- ── 7.1 updated_at trigger for deals ──
CREATE OR REPLACE FUNCTION fn_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_deals_set_updated_at
    BEFORE UPDATE ON deals
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();


-- ── 7.2 Cleanup function for processed_messages ──
-- Removes rows older than N days to keep table small.
-- Usage: SELECT cleanup_processed_messages(7);
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


-- ── 7.3 Cleanup function for old consumed pending_entries ──
-- Removes consumed PREPs older than N days (audit retention).
-- Active PREPs (consumed_at IS NULL) are NEVER deleted by this.
-- Usage: SELECT cleanup_old_pending_entries(30);
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
-- 8. GRANT permissions to imod98
-- ============================================================

-- Tables
GRANT ALL PRIVILEGES ON TABLE app_settings        TO imod98;
GRANT ALL PRIVILEGES ON TABLE deals               TO imod98;
GRANT ALL PRIVILEGES ON TABLE deal_events         TO imod98;
GRANT ALL PRIVILEGES ON TABLE processed_messages  TO imod98;
GRANT ALL PRIVILEGES ON TABLE pending_entries     TO imod98;

-- Sequences
GRANT USAGE, SELECT ON SEQUENCE app_settings_id_seq      TO imod98;
GRANT USAGE, SELECT ON SEQUENCE deals_id_seq             TO imod98;
GRANT USAGE, SELECT ON SEQUENCE deal_events_id_seq       TO imod98;
GRANT USAGE, SELECT ON SEQUENCE pending_entries_id_seq   TO imod98;

-- Functions
GRANT EXECUTE ON FUNCTION fn_set_updated_at()                    TO imod98;
GRANT EXECUTE ON FUNCTION cleanup_processed_messages(INTEGER)    TO imod98;
GRANT EXECUTE ON FUNCTION cleanup_old_pending_entries(INTEGER)   TO imod98;


-- ============================================================
-- 9. SEED DATA
-- ============================================================

INSERT INTO app_settings (ai_enabled, updated_at)
SELECT true, NOW()
WHERE NOT EXISTS (SELECT 1 FROM app_settings);


-- ============================================================
-- 10. VERIFICATION
-- ============================================================

SELECT table_name, COUNT(*) AS row_count FROM (
    SELECT 'app_settings'       AS table_name FROM app_settings        UNION ALL
    SELECT 'deals'               AS table_name FROM deals               UNION ALL
    SELECT 'deal_events'         AS table_name FROM deal_events         UNION ALL
    SELECT 'processed_messages'  AS table_name FROM processed_messages  UNION ALL
    SELECT 'pending_entries'     AS table_name FROM pending_entries
) t
GROUP BY table_name
ORDER BY table_name;

\echo
\echo '════════════════════════════════════════════════════════════════'
\echo '  ✅ SPX Trading Bot — Schema v3.0 created successfully'
\echo '════════════════════════════════════════════════════════════════'
\echo
\echo '  Tables created:'
\echo '    • app_settings        — Configuration flags'
\echo '    • deals               — Trade lifecycle'
\echo '    • deal_events         — Audit log per deal'
\echo '    • processed_messages  — Telegram idempotency (DB-backed)'
\echo '    • pending_entries     — PREP-then-ENTRY workflow (NEW)'
\echo
\echo '  Functions available:'
\echo '    • cleanup_processed_messages(days)   — call weekly'
\echo '    • cleanup_old_pending_entries(days)  — call monthly'
\echo
\echo '  Configuration check:'
\echo '    spring.jpa.hibernate.ddl-auto=validate'
\echo '    spring.flyway.enabled=false'
\echo
\echo '════════════════════════════════════════════════════════════════'
