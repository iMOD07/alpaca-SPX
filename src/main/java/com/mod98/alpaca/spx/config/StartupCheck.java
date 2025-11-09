package com.mod98.alpaca.spx.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class StartupCheck implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);
    private final JdbcTemplate jdbc;

    public StartupCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        try {
            Boolean exists = jdbc.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.tables
                        WHERE table_schema = current_schema()
                        AND table_name = 'app_settings'
                    )
                    """,
                    Boolean.class
            );

            if (Boolean.FALSE.equals(exists)) {
                log.error("❌ The app_settings table does not exist! Shut down and check the database.");
                return;
            }

            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_settings", Integer.class);

            if (count == null || count == 0) {
                log.warn("⚠️ Table app_settings is empty — a default row is being inserted...");
                jdbc.update("INSERT INTO app_settings (ai_enabled, updated_at) VALUES (true, NOW())");
                log.info("✅ A default row was successfully inserted.");
            } else {
                log.info("✅ Database verification successful — app_settings contains {} row(and).", count);
            }

        } catch (Exception e) {
            log.error("❌ Database verification failed: {}", e.getMessage(), e);
        }
    }
}
