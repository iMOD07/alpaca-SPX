-- to run Script
-- psql -U postgres -v ON_ERROR_STOP=1 -f create_database.sql
-- ========================================
-- 1. Create the database (run as postgres)
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

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL ON TABLES TO imod98;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL ON SEQUENCES TO imod98;

-- ========================================
-- 4. Tables
-- ========================================
CREATE TABLE IF NOT EXISTS app_settings (
    id          SERIAL PRIMARY KEY,
    ai_enabled  BOOLEAN   NOT NULL,
    updated_at  TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS deals (
    id                    BIGSERIAL PRIMARY KEY,
    symbol                VARCHAR(50),
    option_type           VARCHAR(10),
    strike                NUMERIC(18,4),
    expiry_date           DATE,
    status                VARCHAR(20),
    prepare_price         NUMERIC(18,4),
    entry_signal_price    NUMERIC(18,4),
    entry_price           NUMERIC(18,4),
    entry_min_price       NUMERIC(18,4),
    entry_max_price       NUMERIC(18,4),
    tp_price              NUMERIC(18,4),
    sl_price              NUMERIC(18,4),
    current_price         NUMERIC(18,4),
    telegram_message_id   BIGINT,

    version               BIGINT      NOT NULL DEFAULT 0,
    ibkr_contract_id      INTEGER,
    ibkr_entry_order_id   INTEGER,
    ibkr_tp_order_id      INTEGER,
    ibkr_sl_order_id      INTEGER,
    ibkr_exit_order_id    INTEGER,
    filled_qty            INTEGER,
    signal_received_at    TIMESTAMPTZ,
    order_sent_at         TIMESTAMPTZ,
    filled_at             TIMESTAMPTZ,

    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS deal_events (
    id           BIGSERIAL PRIMARY KEY,
    deal_id      BIGINT NOT NULL REFERENCES deals(id) ON DELETE CASCADE,
    event_type   VARCHAR(50),
    event_price  NUMERIC(18,4),
    raw_message  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ========================================
-- 5. Indexes
-- ========================================
CREATE INDEX IF NOT EXISTS idx_deals_status            ON deals(status);
CREATE INDEX IF NOT EXISTS idx_deals_symbol_strike     ON deals(symbol, strike);
CREATE INDEX IF NOT EXISTS idx_deals_telegram_msg      ON deals(telegram_message_id);
CREATE INDEX IF NOT EXISTS idx_deals_created_at        ON deals(created_at);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_entry_order  ON deals(ibkr_entry_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_tp_order     ON deals(ibkr_tp_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_sl_order     ON deals(ibkr_sl_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_exit_order   ON deals(ibkr_exit_order_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_deals_telegram_msg
    ON deals(telegram_message_id)
    WHERE telegram_message_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_deal_events_deal_id     ON deal_events(deal_id);
CREATE INDEX IF NOT EXISTS idx_deal_events_type        ON deal_events(event_type);
CREATE INDEX IF NOT EXISTS idx_deal_events_created_at  ON deal_events(created_at);

-- ========================================
-- 6. Default seed (only if table is empty)
-- ========================================
INSERT INTO app_settings (ai_enabled, updated_at)
SELECT true, NOW()
WHERE NOT EXISTS (SELECT 1 FROM app_settings);

-- ========================================
-- 7. Quick check
-- ========================================
SELECT * FROM app_settings;
