package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.ibkr.IbkrExecutionService;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntrySignalHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;
    private final IbkrExecutionService ibkrExecutionService;
    private final TradingProperties tradingProperties;

    @Override
    @Transactional
    public Deal handle(ParsedSignal signal) {

        // 1️⃣ إنشاء الصفقة
        Deal deal = new Deal();
        deal.setSymbol(signal.getSymbol());
        deal.setOptionType(signal.getOptionType());
        deal.setStrike(signal.getStrike());
        deal.setExpiryDate(signal.getExpiryDate());
        deal.setEntrySignalPrice(signal.getEntrySignalPrice());
        deal.setStatus(DealStatus.PREPARE);

        Deal saved = dealRepository.save(deal);

        // 2️⃣ تسجيل حدث استقبال إشارة الدخول
        DealEvent received = new DealEvent();
        received.setDeal(saved);
        received.setEventType(DealEventType.ENTRY_SIGNAL_RECEIVED);
        received.setEventPrice(signal.getEntrySignalPrice());
        received.setRawMessage(signal.getRawText());
        eventRepository.save(received);

        // 3️⃣ تنفيذ ENTRY الحقيقي عبر IBKR
        IbkrExecutionService.EntryDecisionResult result =
                ibkrExecutionService.placeEntrySpxwCall(
                        signal.getExpiryDate(),
                        signal.getStrike().doubleValue(),
                        signal.getEntrySignalPrice()
                );

        // 4️⃣ التعامل مع نتيجة القرار
        if (!result.placed()) {
            DealEvent blocked = new DealEvent();
            blocked.setDeal(saved);
            blocked.setEventType(DealEventType.ENTRY_BLOCKED_OUT_OF_RANGE);
            blocked.setEventPrice(result.ask());
            eventRepository.save(blocked);

            log.warn("ENTRY BLOCKED | dealId={} ask={} range=[{}..{}]",
                    saved.getId(), result.ask(), result.lower(), result.upper());

            return saved;
        }

        // 5️⃣ تم إرسال الأمر
        saved.setStatus(DealStatus.ENTERED);
        saved.setEntryMinPrice(result.lower());
        saved.setEntryMaxPrice(result.upper());
        saved.setCurrentPrice(result.ask());

        dealRepository.save(saved);

        DealEvent placed = new DealEvent();
        placed.setDeal(saved);
        placed.setEventType(DealEventType.ENTRY_ORDER_PLACED);
        placed.setEventPrice(result.ask());
        eventRepository.save(placed);

        return saved;
    }
}
