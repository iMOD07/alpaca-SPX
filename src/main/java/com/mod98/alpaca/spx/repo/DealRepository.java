package com.mod98.alpaca.spx.repo;

import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.domain.DealStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DealRepository extends JpaRepository<Deal, Long> {

    Optional<Deal> findByTelegramMessageId(Long telegramMessageId);

    List<Deal> findByStatusIn(List<DealStatus> statuses);

    List<Deal> findByStatusOrderByCreatedAtDesc(DealStatus status);

    List<Deal> findByStatusAndSymbolAndStrikeAndOptionTypeAndExpiryDateOrderByCreatedAtDesc(
            DealStatus status,
            String symbol,
            BigDecimal strike,
            String optionType,
            LocalDate expiryDate
    );

    /**
     * يبحث عن صفقة بأي من order ids الأربعة (entry/tp/sl/exit).
     * مفيد للـ OrderTrackingService.
     */
    @Query("""
           SELECT d FROM Deal d
           WHERE d.ibkrEntryOrderId = :orderId
              OR d.ibkrTpOrderId    = :orderId
              OR d.ibkrSlOrderId    = :orderId
              OR d.ibkrExitOrderId  = :orderId
           """)
    Optional<Deal> findByAnyOrderId(@Param("orderId") Integer orderId);
}
