package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.ibkr.IbkrExecutionService;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual exit (signal "خروج" / "بيع").
 *
 * Critical safety:
 *  - يلغي TP/SL bracket FIRST قبل إرسال SELL (لمنع short sell race)
 *  - idempotent: لو سبق إرسال exit للـ deal، يتجاهل
 *  - لا يغيّر status إلى CLOSED — ينتظر OrderStatusEvent("Filled") من OrderTrackingService
 */
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
        Deal deal = findActiveDeal(signal);
        if (deal == null) {
            log.warn("MANUAL_EXIT ignored — no active ENTERED deal");
            return null;
        }

        // ⚠️ Idempotency: لو exit already submitted لنفس الصفقة
        if (deal.getIbkrExitOrderId() != null) {
            log.warn("MANUAL_EXIT ignored — exit already submitted | dealId={} exitOrderId={}",
                    deal.getId(), deal.getIbkrExitOrderId());
            return deal;
        }

        // ⚠️ Sanity: status لازم ENTERED
        if (deal.getStatus() != DealStatus.ENTERED) {
            log.warn("MANUAL_EXIT ignored — dealId={} status={} (expected ENTERED)",
                    deal.getId(), deal.getStatus());
            return deal;
        }

        // ⚠️ CRITICAL: ألغ TP/SL bracket أولاً
        // لو ما ألغيناها، السيناريو الكارثي:
        //   1. Manual SELL ينفّذ → +0 contracts
        //   2. TP/SL لا يزالان نشطين في IBKR
        //   3. السعر يحرّك TP أو SL → SELL ثاني
        //   4. النتيجة: short -1 contract!
        cancelBracketIfPresent(deal);

        // أرسل SELL
        try {
            int exitOrderId = ibkrExecutionService.closeDealPosition(deal, "MANUAL_EXIT");
            deal.setIbkrExitOrderId(exitOrderId);

            // ⚠️ لا نغيّر status إلى CLOSED هنا — ننتظر confirmation من IBKR
            // عبر OrderTrackingService.handleExitFilled()
            dealRepository.save(deal);

            DealEvent ev = new DealEvent();
            ev.setDeal(deal);
            ev.setEventType(DealEventType.MANUAL_EXIT);
            ev.setRawMessage(signal.getRawText());
            eventRepository.save(ev);

            log.info("✅ MANUAL_EXIT submitted | dealId={} exitOrderId={} (waiting for fill)",
                    deal.getId(), exitOrderId);
            return deal;
        } catch (Exception e) {
            log.error("❌ MANUAL_EXIT failed | dealId={}", deal.getId(), e);
            DealEvent err = new DealEvent();
            err.setDeal(deal);
            err.setEventType(DealEventType.EXECUTION_ERROR);
            err.setRawMessage("MANUAL_EXIT failed: " + e.getMessage());
            eventRepository.save(err);
            return deal;
        }
    }

    private void cancelBracketIfPresent(Deal deal) {
        if (deal.getIbkrTpOrderId() != null) {
            try {
                ibkrExecutionService.cancelOrder(deal.getIbkrTpOrderId());
                log.info("Cancelled TP order {} for dealId={}", deal.getIbkrTpOrderId(), deal.getId());
            } catch (Exception e) {
                log.error("Failed to cancel TP order — proceed anyway | tpId={}",
                        deal.getIbkrTpOrderId(), e);
            }
        }
        if (deal.getIbkrSlOrderId() != null) {
            try {
                ibkrExecutionService.cancelOrder(deal.getIbkrSlOrderId());
                log.info("Cancelled SL order {} for dealId={}", deal.getIbkrSlOrderId(), deal.getId());
            } catch (Exception e) {
                log.error("Failed to cancel SL order — proceed anyway | slId={}",
                        deal.getIbkrSlOrderId(), e);
            }
        }
        // OCA group في IBKR يلغي الباقي تلقائياً عند إلغاء واحد، لكن الإلغاء الصريح أكثر أماناً.
    }

    private Deal findActiveDeal(ParsedSignal signal) {
        // أولوية 1: reply لرسالة محددة
        if (signal.getReplyToMessageId() != null) {
            Deal d = dealRepository.findByTelegramMessageId(signal.getReplyToMessageId())
                    .filter(x -> x.getStatus() == DealStatus.ENTERED)
                    .orElse(null);
            if (d != null) return d;
        }
        // أولوية 2: آخر صفقة ENTERED
        return dealRepository.findByStatusOrderByCreatedAtDesc(DealStatus.ENTERED)
                .stream().findFirst().orElse(null);
    }
}
