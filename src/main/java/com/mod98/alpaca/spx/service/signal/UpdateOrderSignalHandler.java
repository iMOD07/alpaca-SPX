
package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class UpdateOrderSignalHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;

    public UpdateOrderSignalHandler(DealRepository dealRepository,
                                    DealEventRepository eventRepository) {
        this.dealRepository = dealRepository;
        this.eventRepository = eventRepository;
    }

    @Override
    @Transactional
    public Deal handle(ParsedSignal signal) {
        Long repliedId = signal.getReplyToMessageId();
        if (repliedId == null) {
            return null;
        }

        Deal deal = dealRepository.findByTelegramMessageId(repliedId)
                .orElse(null);

        if (deal == null || deal.getStatus() != DealStatus.PREPARE) {
            return deal;
        }

        BigDecimal newPreparePrice = signal.getUpdatePrice();
        deal.setPreparePrice(newPreparePrice);

        Deal saved = dealRepository.save(deal);

        DealEvent event = new DealEvent();
        event.setDeal(saved);
        event.setEventType(DealEventType.PREPARE_UPDATED);
        event.setEventPrice(newPreparePrice);
        event.setRawMessage(signal.getRawText());
        eventRepository.save(event);

        return saved;
    }

}
