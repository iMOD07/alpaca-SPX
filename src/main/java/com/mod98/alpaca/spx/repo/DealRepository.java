package com.mod98.alpaca.spx.repo;

import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.domain.DealStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DealRepository extends JpaRepository<Deal, Long> {

    Optional<Deal> findByTelegramMessageId(Long telegramMessageId);

    List<Deal> findByStatusIn(List<DealStatus> statuses);

    // آخر صفقة PREPARE
    List<Deal> findByStatusOrderByCreatedAtDesc(DealStatus status);

    // جاهزة، لو حبيت تستعمل مطابقة كاملة على العقد (مستقبلاً مع OpenCV)
    List<Deal> findByStatusAndSymbolAndStrikeAndOptionTypeAndExpiryDateOrderByCreatedAtDesc(
            DealStatus status,
            String symbol,
            BigDecimal strike,
            String optionType,
            LocalDate expiryDate
    );
}
