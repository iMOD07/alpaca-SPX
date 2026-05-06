package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class PriceAlertSignalHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;

    public PriceAlertSignalHandler(DealRepository dealRepository,
                                   DealEventRepository eventRepository) {
        this.dealRepository = dealRepository;
        this.eventRepository = eventRepository;
    }

    @Override
    @Transactional
    public Deal handle(ParsedSignal signal) {
        BigDecimal alertPrice = signal.getPriceAlert();
        if (alertPrice == null) {
            return null;
        }

        Deal deal = dealRepository.findByTelegramMessageId(signal.getReplyToMessageId())
                .orElse(null);

        if (deal == null) {
            return null;
        }

        deal.setCurrentPrice(alertPrice);
        Deal saved = dealRepository.save(deal);

        DealEvent event = new DealEvent();
        event.setDeal(saved);
        event.setEventType(DealEventType.PRICE_ALERT);
        event.setEventPrice(alertPrice);
        event.setRawMessage(signal.getRawText());
        eventRepository.save(event);

        return saved;
    }

}
