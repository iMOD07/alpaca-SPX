-- ============================================================
-- ⚠️ DANGER: DROP EVERYTHING
-- ============================================================
-- Only use this if you want a complete wipe and to start from scratch.
-- All data (deals, events, PREPs, kill switch state) will be deleted!

-- to run:
-- psql -U postgres -f drop_database.sql
-- ============================================================

SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'SPX_bot_ibkr' AND pid <> pg_backend_pid();

DROP DATABASE IF EXISTS "SPX_bot_ibkr";

\echo '⚠️ Database SPX_bot_ibkr dropped. Run create_database.sql to recreate.'
