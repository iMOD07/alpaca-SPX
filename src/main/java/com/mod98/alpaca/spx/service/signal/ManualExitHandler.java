package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.service.alpaca.AlpacaExecutionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ManualExitHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;
    private final AlpacaExecutionService alpacaExecutionService;

    public ManualExitHandler(DealRepository dealRepository,
                             DealEventRepository eventRepository,
                             AlpacaExecutionService alpacaExecutionService) {
        this.dealRepository = dealRepository;
        this.eventRepository = eventRepository;
        this.alpacaExecutionService = alpacaExecutionService;
    }

    @Override
    @Transactional
    public Deal handle(ParsedSignal signal) {

        // أبسط تطبيق: خروج من آخر صفقة ENTERED
        List<Deal> enteredDeals = dealRepository.findByStatusIn(List.of(DealStatus.ENTERED));
        if (enteredDeals.isEmpty()) {
            return null;
        }

        Deal deal = enteredDeals.get(enteredDeals.size() - 1);

        // إرسال أمر بيع Market (خروج يدوي)
        alpacaExecutionService.closeDealPosition(deal, "MANUAL_EXIT");

        deal.setStatus(DealStatus.CLOSED);
        Deal saved = dealRepository.save(deal);

        DealEvent event = new DealEvent();
        event.setDeal(saved);
        event.setEventType(DealEventType.MANUAL_EXIT);
        event.setRawMessage(signal.getRawText());
        eventRepository.save(event);

        return saved;
    }
}
