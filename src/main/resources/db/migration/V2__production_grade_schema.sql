-- V2__production_grade_schema.sql
-- يضيف الحقول المفقودة للـ production-grade Deal entity.
-- يفترض V1 سابقًا أنشأ الجدول الأصلي.

ALTER TABLE deals
    ADD COLUMN IF NOT EXISTS version              BIGINT      DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS ibkr_contract_id     INTEGER,
    ADD COLUMN IF NOT EXISTS ibkr_entry_order_id  INTEGER,
    ADD COLUMN IF NOT EXISTS ibkr_tp_order_id     INTEGER,
    ADD COLUMN IF NOT EXISTS ibkr_sl_order_id     INTEGER,
    ADD COLUMN IF NOT EXISTS ibkr_exit_order_id   INTEGER,
    ADD COLUMN IF NOT EXISTS filled_qty           INTEGER,
    ADD COLUMN IF NOT EXISTS signal_received_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS order_sent_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS filled_at            TIMESTAMPTZ;

-- Indexes critical for lookups
CREATE INDEX IF NOT EXISTS idx_deals_status              ON deals(status);
CREATE INDEX IF NOT EXISTS idx_deals_telegram_msg        ON deals(telegram_message_id);
CREATE INDEX IF NOT EXISTS idx_deals_created_at          ON deals(created_at);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_entry_order    ON deals(ibkr_entry_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_tp_order       ON deals(ibkr_tp_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_sl_order       ON deals(ibkr_sl_order_id);
CREATE INDEX IF NOT EXISTS idx_deals_ibkr_exit_order     ON deals(ibkr_exit_order_id);

-- Unique constraint to prevent duplicate processing of same Telegram message
CREATE UNIQUE INDEX IF NOT EXISTS uq_deals_telegram_msg  ON deals(telegram_message_id) WHERE telegram_message_id IS NOT NULL;

-- deal_events: index for time-based queries
CREATE INDEX IF NOT EXISTS idx_deal_events_deal_id       ON deal_events(deal_id);
CREATE INDEX IF NOT EXISTS idx_deal_events_created_at    ON deal_events(created_at);
