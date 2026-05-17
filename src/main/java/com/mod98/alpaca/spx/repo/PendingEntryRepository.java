package com.mod98.alpaca.spx.repo;

import com.mod98.alpaca.spx.domain.PendingEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PendingEntryRepository extends JpaRepository<PendingEntry, Long> {

    Optional<PendingEntry> findByTelegramMessageId(Long telegramMessageId);

    /**
     * يطلع آخر PREP نشطة بنفس option_type في window زمني.
     * يُستدعى من EntryHandler عند وصول رسالة "دخول CALL/PUT".
     *
     * @param optionType "CALL" أو "PUT"
     * @param since      أقدم وقت مقبول (start of today ET)
     */
    @Query("""
            SELECT p FROM PendingEntry p
            WHERE p.optionType = :optionType
              AND p.consumedAt IS NULL
              AND p.createdAt >= :since
            ORDER BY p.createdAt DESC
            """)
    List<PendingEntry> findActiveByTypeOrderByNewestFirst(
            @Param("optionType") String optionType,
            @Param("since") Instant since);

    /**
     * يطلع كل الـ PREPs النشطة (لـ end-of-day cleanup).
     */
    List<PendingEntry> findByConsumedAtIsNull();
}
