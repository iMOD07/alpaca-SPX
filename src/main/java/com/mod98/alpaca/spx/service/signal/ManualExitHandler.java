package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.ibkr.IbkrExecutionService;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ManualExitHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;
    private final IbkrExecutionService ibkrExecutionService;

    @Override
    @Transactional
    public Deal handle(ParsedSignal signal) {
        // أولوية 1: الإشارة reply لرسالة محددة
        Deal deal = null;
        if (signal.getReplyToMessageId() != null) {
            deal = dealRepository.findByTelegramMessageId(signal.getReplyToMessageId())
                    .filter(d -> d.getStatus() == DealStatus.ENTERED)
                    .orElse(null);
        }
        // أولوية 2: آخر صفقة ENTERED مرتبة بالوقت
        if (deal == null) {
            deal = dealRepository.findByStatusOrderByCreatedAtDesc(DealStatus.ENTERED)
                    .stream().findFirst().orElse(null);
        }
        if (deal == null) {
            log.warn("MANUAL_EXIT ignored — no ENTERED deal found");
            return null;
        }

        try {
            int exitOrderId = ibkrExecutionService.closeDealPosition(deal, "MANUAL_EXIT");
            deal.setIbkrExitOrderId(exitOrderId);
            // ⚠️ لا تغيّر status هنا — انتظر confirmation من OrderTrackingService
            dealRepository.save(deal);

            DealEvent ev = new DealEvent();
            ev.setDeal(deal);
            ev.setEventType(DealEventType.MANUAL_EXIT);
            ev.setRawMessage(signal.getRawText());
            eventRepository.save(ev);

            log.info("MANUAL_EXIT placed | dealId={} exitOrderId={}", deal.getId(), exitOrderId);
            return deal;
        } catch (Exception e) {
            log.error("MANUAL_EXIT failed | dealId={}", deal.getId(), e);
            DealEvent err = new DealEvent();
            err.setDeal(deal);
            err.setEventType(DealEventType.EXECUTION_ERROR);
            err.setRawMessage("MANUAL_EXIT failed: " + e.getMessage());
            eventRepository.save(err);
            return deal;
        }
    }
}
