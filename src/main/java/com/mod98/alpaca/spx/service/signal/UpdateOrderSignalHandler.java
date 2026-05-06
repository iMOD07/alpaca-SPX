package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateOrderSignalHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;

    @Override
    @Transactional
    public Deal handle(ParsedSignal signal) {
        Long repliedId = signal.getReplyToMessageId();
        if (repliedId == null) {
            log.warn("UPDATE ignored — no replyTo");
            return null;
        }

        Deal deal = dealRepository.findByTelegramMessageId(repliedId).orElse(null);
        if (deal == null) {
            log.warn("UPDATE ignored — replied-to deal not found | msgId={}", repliedId);
            return null;
        }

        if (deal.getStatus() != DealStatus.PREPARE) {
            log.warn("UPDATE ignored — dealId={} status={} (only PREPARE deals can be updated)",
                    deal.getId(), deal.getStatus());
            return deal;
        }

        BigDecimal newPrice = signal.getUpdatePrice();
        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("UPDATE ignored — invalid newPrice={} | dealId={}", newPrice, deal.getId());
            return deal;
        }

        BigDecimal oldPrice = deal.getPreparePrice();
        deal.setPreparePrice(newPrice);
        deal.setEntrySignalPrice(newPrice);

        // ⚠️ CRITICAL: جدّد signalReceivedAt حتى يرجع timer الـ prepare-validity للصفر
        // (بدون هذا، UPDATE ما يجدّد عمر الصفقة ولن تُقبَل ENTRY بعدها)
        deal.setSignalReceivedAt(Instant.now());

        Deal saved = dealRepository.save(deal);

        DealEvent event = new DealEvent();
        event.setDeal(saved);
        event.setEventType(DealEventType.PREPARE_UPDATED);
        event.setEventPrice(newPrice);
        event.setRawMessage(signal.getRawText());
        eventRepository.save(event);

        log.info("✅ PREPARE UPDATED | dealId={} oldPrice={} newPrice={}",
                saved.getId(), oldPrice, newPrice);
        return saved;
    }
}
