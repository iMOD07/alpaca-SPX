package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.domain.DealEvent;
import com.mod98.alpaca.spx.domain.DealEventType;
import com.mod98.alpaca.spx.domain.DealStatus;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrepareSignalHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;

    @Override
    @Transactional
    public Deal handle(ParsedSignal signal) {
        Deal deal = new Deal();
        deal.setSymbol(signal.getSymbol() != null ? signal.getSymbol() : "SPXW");
        deal.setOptionType(signal.getOptionType());
        deal.setStrike(signal.getStrike());
        deal.setExpiryDate(signal.getExpiryDate());

        deal.setStatus(DealStatus.PREPARE);
        deal.setPreparePrice(signal.getPreparePrice());
        deal.setEntrySignalPrice(signal.getEntrySignalPrice() != null
                ? signal.getEntrySignalPrice()
                : signal.getPreparePrice());
        deal.setTelegramMessageId(signal.getTelegramMessageId());

        // Symbol fallback (OCC-style): SPXW + YYMMDD + C/P + strike*1000 (8 digits)
        if (deal.getSymbol() == null && deal.getStrike() != null && deal.getExpiryDate() != null) {
            deal.setSymbol(buildOptionSymbol("SPXW", deal.getExpiryDate(),
                    deal.getStrike(), deal.getOptionType()));
        }

        Deal saved = dealRepository.save(deal);

        DealEvent event = new DealEvent();
        event.setDeal(saved);
        event.setEventType(DealEventType.PREPARE_CREATED);
        event.setEventPrice(signal.getPreparePrice());
        event.setRawMessage(signal.getRawText());
        eventRepository.save(event);

        log.info("PREPARE created | dealId={} strike={} expiry={} type={} price={}",
                saved.getId(), saved.getStrike(), saved.getExpiryDate(),
                saved.getOptionType(), saved.getPreparePrice());
        return saved;
    }

    /** OCC-style: SPXW251128C06810000 */
    private String buildOptionSymbol(String underlying, LocalDate expiry,
                                     BigDecimal strike, String optionType) {
        if (underlying == null || expiry == null || strike == null || optionType == null) {
            return null;
        }
        String date = expiry.format(DateTimeFormatter.ofPattern("yyMMdd"));
        String cp = "CALL".equalsIgnoreCase(optionType) ? "C" : "P";
        long strike8 = strike.multiply(BigDecimal.valueOf(1000))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
        return String.format("%s%s%s%08d", underlying, date, cp, strike8);
    }
}
