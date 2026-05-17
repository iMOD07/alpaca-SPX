-- to run Script
-- psql -U postgres -v ON_ERROR_STOP=1 -f create_database.sql
-- ============================================================
-- SPX Trading Bot — PostgreSQL Schema
-- ============================================================
-- to run:
--   psql -U postgres -v ON_ERROR_STOP=1 -f create_database.sql
--
-- This script creates the database, user, and all tables
-- aligned with JPA entities: AppSettings, Deal, DealEvent.
-- Compatible with spring.jpa.hibernate.ddl-auto=validate
-- ============================================================


-- ========================================
-- 1. Create database (run as postgres)
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


-- ========================================
-- 4. Tables
-- ========================================

-- AppSettings.java:
--   id          Integer → INTEGER  (entity uses Integer + GenerationType.IDENTITY,
--                                    so column must be SERIAL/int4, NOT BIGSERIAL)
--   aiEnabled   boolean → BOOLEAN
--   updatedAt   Instant → TIMESTAMPTZ
CREATE TABLE IF NOT EXISTS app_settings (
    id          SERIAL      PRIMARY KEY,
    ai_enabled  BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);


-- Deal.java: aligned 1:1 with JPA entity
--   All Instant fields  → TIMESTAMPTZ
--   All BigDecimal      → NUMERIC(18,4)
--   All Long FKs/IDs    → BIGINT
--   All Integer fields  → INTEGER
--   status              → VARCHAR (enum stored as STRING)
CREATE TABLE IF NOT EXISTS deals (
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
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);


-- DealEvent.java:
--   id           Long          → BIGINT
--   deal         Deal (FK)     → BIGINT REFERENCES deals(id)
--   eventType    enum STRING   → VARCHAR
--   eventPrice   BigDecimal    → NUMERIC(18,4)
--   rawMessage   String (TEXT) → TEXT (no @Lob, no oid)
--   createdAt    Instant       → TIMESTAMPTZ
CREATE TABLE IF NOT EXISTS deal_events (
    id           BIGSERIAL   PRIMARY KEY,
    deal_id      BIGINT      NOT NULL REFERENCES deals(id) ON DELETE CASCADE,
    event_type   VARCHAR(50),
    event_price  NUMERIC(18,4),
    raw_message  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


-- ========================================
-- 5. Indexes (match @Index annotations + production access patterns)
-- ========================================

-- deals: declared @Index in Deal.java
CREATE INDEX IF NOT EXISTS idx_deals_status            ON deals(status);
CREATE INDEX IF NOT EXISTS idx_deals_telegram_msg      ON deals(telegram_message_id);
CREATE INDEX IF NOT EXISTS idx_deals_created_at        ON deals(created_at);

-- deals: extra indexes for OrderTrackingService.findByAnyOrderId
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_entry_order  ON deals(ibkr_entry_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_tp_order     ON deals(ibkr_tp_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_sl_order     ON deals(ibkr_sl_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_exit_order   ON deals(ibkr_exit_order_id);

-- deals: composite for findByStatusAndSymbolAndStrikeAndOptionTypeAndExpiryDate
CREATE INDEX IF NOT EXISTS idx_deals_match
    ON deals(status, symbol, strike, option_type, expiry_date);

-- deals: enforce telegram_message_id uniqueness (matches @Column(unique=true))
-- Partial index allows multiple NULLs while blocking duplicates of real values
CREATE UNIQUE INDEX IF NOT EXISTS uq_deals_telegram_msg
    ON deals(telegram_message_id)
    WHERE telegram_message_id IS NOT NULL;

-- deal_events: FK index + time queries
CREATE INDEX IF NOT EXISTS idx_deal_events_deal_id     ON deal_events(deal_id);
CREATE INDEX IF NOT EXISTS idx_deal_events_type        ON deal_events(event_type);
CREATE INDEX IF NOT EXISTS idx_deal_events_created_at  ON deal_events(created_at);


-- ========================================
-- 6. updated_at trigger (matches @PreUpdate behavior)
-- ========================================
-- JPA @PreUpdate sets updated_at on entity update, but if any code path
-- updates deals via raw SQL (e.g. Flyway data migrations), the trigger
-- ensures updated_at stays correct.
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


-- ========================================
-- 7. Flyway baseline (so Flyway doesn't try to re-run V2 migration)
-- ========================================
-- Since this script creates everything that V2__production_grade_schema.sql
-- would have done, we let Flyway baseline at version 2.
-- Make sure application.properties has:
--   spring.flyway.baseline-on-migrate=true
--   spring.flyway.baseline-version=2
-- OR just delete the V2 file since this script already applied it.


-- ========================================
-- 8. Default seed
-- ========================================
INSERT INTO app_settings (ai_enabled, updated_at)
SELECT true, NOW()
WHERE NOT EXISTS (SELECT 1 FROM app_settings);


-- ========================================
-- 9. Verification
-- ========================================
SELECT 'app_settings' AS table_name, COUNT(*) AS row_count FROM app_settings
UNION ALL
SELECT 'deals',       COUNT(*) FROM deals
UNION ALL
SELECT 'deal_events', COUNT(*) FROM deal_events;

\echo
\echo '========================================='
\echo 'Schema created successfully.'
\echo 'Run application with ddl-auto=validate'
\echo '========================================='
