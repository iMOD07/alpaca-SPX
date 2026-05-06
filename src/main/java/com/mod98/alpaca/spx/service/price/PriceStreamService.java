package com.mod98.alpaca.spx.service.price;

import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class PriceStreamService {

    private static final Logger log = LoggerFactory.getLogger(PriceStreamService.class);

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;

    public PriceStreamService(DealRepository dealRepository,
                              DealEventRepository eventRepository ) {

        this.dealRepository = dealRepository;
        this.eventRepository = eventRepository;
    }

    // This function calls it from the WebSocket for symbol+strike when a price update occurs.

    @Transactional
    public void onPriceUpdate(String symbol, BigDecimal strike, String optionType, BigDecimal price) {

        List<Deal> deals = dealRepository.findByStatusIn(
                List.of(DealStatus.PREPARE, DealStatus.ENTERED));

        for (Deal d : deals) {
            if (!symbol.equals(d.getSymbol())) continue;
            if (strike != null && d.getStrike() != null && strike.compareTo(d.getStrike()) != 0)
                continue;
            if (optionType != null && !optionType.equalsIgnoreCase(d.getOptionType()))
                continue;

            d.setCurrentPrice(price);
            dealRepository.save(d);

            // TP/SL checks are only for ENTERED trades.
            if (d.getStatus() == DealStatus.ENTERED) {
                checkTpSl(d, price);
            }
        }
    }

    private void checkTpSl(Deal deal, BigDecimal price) {

        BigDecimal tp = deal.getTpPrice();
        BigDecimal sl = deal.getSlPrice();

        if (tp != null && price.compareTo(tp) >= 0) {

            DealEvent ev = new DealEvent();
            ev.setDeal(deal);
            ev.setEventType(DealEventType.TP_HIT);
            ev.setEventPrice(price);
            eventRepository.save(ev);

            ibkrExecutionService.closeDealPosition(deal, "TP_HIT");

            deal.setStatus(DealStatus.CLOSED);
            dealRepository.save(deal);

            log.info("TP hit → IBKR close | deal={}", deal.getId());

        } else if (sl != null && price.compareTo(sl) <= 0) {

            DealEvent ev = new DealEvent();
            ev.setDeal(deal);
            ev.setEventType(DealEventType.SL_HIT);
            ev.setEventPrice(price);
            eventRepository.save(ev);

            ibkrExecutionService.closeDealPosition(deal, "SL_HIT");

            deal.setStatus(DealStatus.CLOSED);
            dealRepository.save(deal);

            log.info("SL hit → IBKR close | deal={}", deal.getId());
        }
    }


}
