package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelSignalHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;

    public CancelSignalHandler(DealRepository dealRepository,
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

        Deal deal = dealRepository.findByTelegramMessageId(repliedId).orElse(null);
        if (deal == null) {
            return null;
        }

        deal.setStatus(DealStatus.CANCELLED);
        Deal saved = dealRepository.save(deal);

        DealEvent ev = new DealEvent();
        ev.setDeal(saved);
        ev.setEventType(DealEventType.CANCELLED);
        ev.setRawMessage(signal.getRawText());
        eventRepository.save(ev);

        return saved;
    }
}
