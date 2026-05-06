package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.domain.DealEvent;
import com.mod98.alpaca.spx.domain.DealEventType;
import com.mod98.alpaca.spx.domain.DealStatus;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.service.alpaca.OptionSymbolBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrepareSignalHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;
    private final OptionSymbolBuilder optionSymbolBuilder;

    public PrepareSignalHandler(DealRepository dealRepository,
                                DealEventRepository eventRepository,
                                OptionSymbolBuilder optionSymbolBuilder) {
        this.dealRepository = dealRepository;
        this.eventRepository = eventRepository;
        this.optionSymbolBuilder = optionSymbolBuilder;
    }

    @Override
    @Transactional
    public Deal handle(ParsedSignal signal) {

        // نبني كيان الصفقة من JSON القادم من OpenAI
        Deal deal = new Deal();
        deal.setSymbol(signal.getSymbol());
        deal.setOptionType(signal.getOptionType());
        deal.setStrike(signal.getStrike());
        deal.setExpiryDate(signal.getExpiryDate());

        deal.setStatus(DealStatus.PREPARE);
        deal.setPreparePrice(signal.getPreparePrice());
        deal.setEntrySignalPrice(signal.getEntrySignalPrice()); // ممكن يكون نفس preparePrice
        deal.setTelegramMessageId(signal.getTelegramMessageId());

        // لو symbol فاضي، نبنيه من underlying + التاريخ + الاسترايك + CALL/PUT
        if (deal.getSymbol() == null && deal.getStrike() != null && deal.getExpiryDate() != null) {
            deal.setSymbol(
                    optionSymbolBuilder.buildSymbol(
                            "SPXW",
                            deal.getExpiryDate(),
                            deal.getStrike(),
                            deal.getOptionType()
                    )
            );
        }

        Deal saved = dealRepository.save(deal);

        // نسجّل حدث PREPARE_CREATED في جدول deal_events (اختياري بس مفيد)
        DealEvent event = new DealEvent();
        event.setDeal(saved);
        event.setEventType(DealEventType.PREPARE_CREATED);
        event.setEventPrice(signal.getPreparePrice());
        event.setRawMessage(signal.getRawText());
        eventRepository.save(event);

        return saved;
    }
}
