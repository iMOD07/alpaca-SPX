package com.mod98.alpaca.spx.service.processor;

import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.domain.DealEvent;
import com.mod98.alpaca.spx.domain.DealEventType;
import com.mod98.alpaca.spx.domain.DealStatus;
import com.mod98.alpaca.spx.ibkr.IbkrExecutionService;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CANCEL handler — يتعامل مع حالتين:
 *
 * 1) Deal في ENTRY_PENDING → ألغِ الأمر في IBKR و علّمها CANCELLED
 * 2) Deal في ENTERED → exit مفتوحة (أغلق البوزيشن)
 *
 * Critical safety:
 *  - في حالة exit مفتوحة: ألغِ TP/SL bracket FIRST قبل إرسال SELL
 *    (وإلا short -1 contract)
 *  - Idempotency: لو exit مرسل مسبقاً، تجاهل
 *  - حالات terminal تُتجاهل
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelHandler {

    private final DealRepository dealRepo;
    private final DealEventRepository eventRepo;
    private final IbkrExecutionService execution;

    @Transactional
    public void handle(ParsedSignal signal) {
        if (!signal.isValidReply()) {
            log.warn("CANCEL skipped — no replyTo | msgId={}", signal.getTelegramMessageId());
            return;
        }

        Deal deal = dealRepo.findByTelegramMessageId(signal.getReplyToMessageId()).orElse(null);
        if (deal == null) {
            log.warn("CANCEL skipped — no deal for replyTo={}", signal.getReplyToMessageId());
            return;
        }

        if (deal.getStatus().isTerminal()) {
            log.info("CANCEL ignored — already terminal | dealId={} status={}",
                    deal.getId(), deal.getStatus());
            return;
        }

        switch (deal.getStatus()) {
            case ENTRY_PENDING -> cancelPendingOrder(deal, signal);
            case ENTERED       -> closeOpenPosition(deal, signal);
            default -> log.warn("CANCEL ignored — unexpected status | dealId={} status={}",
                    deal.getId(), deal.getStatus());
        }
    }

    /**
     * إلغاء أمر معلّق — IBKR cancelOrder ثم نعلّمها CANCELLED بعد وصول callback.
     */
    private void cancelPendingOrder(Deal deal, ParsedSignal signal) {
        if (deal.getIbkrEntryOrderId() == null) {
            // ما تم إرسال الأمر أصلاً — علّمها CANCELLED مباشرة
            deal.setStatus(DealStatus.CANCELLED);
            dealRepo.save(deal);
            recordEvent(deal, DealEventType.CANCELLED, "cancelled before order send | raw=" + signal.getRawText());
            log.info("✅ CANCEL applied (no order sent) | dealId={}", deal.getId());
            return;
        }

        try {
            execution.cancelOrder(deal.getIbkrEntryOrderId());
            // ⚠️ لا نغيّر status هنا — ننتظر OrderStatusEvent("Cancelled") من IBKR
            // Watchdog سيتعامل مع timeout لو الـ callback ما وصل
            recordEvent(deal, DealEventType.CANCELLED,
                    "cancel requested | orderId=" + deal.getIbkrEntryOrderId() + " | raw=" + signal.getRawText());
            log.info("✅ CANCEL request sent to IBKR | dealId={} orderId={} (waiting for confirmation)",
                    deal.getId(), deal.getIbkrEntryOrderId());
        } catch (Exception e) {
            log.error("CANCEL request failed | dealId={}", deal.getId(), e);
            recordEvent(deal, DealEventType.EXECUTION_ERROR, "cancel failed: " + e.getMessage());
        }
    }

    /**
     * إغلاق صفقة مفتوحة — Manual exit.
     *
     * Sequence:
     *  1. ألغِ TP و SL (OCA bracket)
     *  2. أرسل SELL LMT
     *  3. لا تغيّر status — ننتظر Filled callback
     */
    private void closeOpenPosition(Deal deal, ParsedSignal signal) {
        // Idempotency: لو exit مرسل مسبقاً
        if (deal.getIbkrExitOrderId() != null) {
            log.warn("CLOSE ignored — exit already submitted | dealId={} exitOrderId={}",
                    deal.getId(), deal.getIbkrExitOrderId());
            return;
        }

        // 1) ألغِ bracket FIRST
        cancelBracketSafely(deal);

        // 2) أرسل SELL
        try {
            int exitOrderId = execution.closeDealPosition(deal, "CANCEL_REPLY");
            deal.setIbkrExitOrderId(exitOrderId);
            dealRepo.save(deal);
            recordEvent(deal, DealEventType.MANUAL_EXIT,
                    "exit submitted | orderId=" + exitOrderId + " | raw=" + signal.getRawText());
            log.info("✅ CLOSE submitted | dealId={} exitOrderId={} (waiting for fill)",
                    deal.getId(), exitOrderId);
        } catch (Exception e) {
            log.error("CLOSE failed | dealId={}", deal.getId(), e);
            recordEvent(deal, DealEventType.EXECUTION_ERROR, "close failed: " + e.getMessage());
        }
    }

    private void cancelBracketSafely(Deal deal) {
        if (deal.getIbkrTpOrderId() != null) {
            try {
                execution.cancelOrder(deal.getIbkrTpOrderId());
                log.info("Cancelled TP | dealId={} tpId={}", deal.getId(), deal.getIbkrTpOrderId());
            } catch (Exception e) {
                log.error("Failed to cancel TP — proceed anyway | tpId={}", deal.getIbkrTpOrderId(), e);
            }
        }
        if (deal.getIbkrSlOrderId() != null) {
            try {
                execution.cancelOrder(deal.getIbkrSlOrderId());
                log.info("Cancelled SL | dealId={} slId={}", deal.getId(), deal.getIbkrSlOrderId());
            } catch (Exception e) {
                log.error("Failed to cancel SL — proceed anyway | slId={}", deal.getIbkrSlOrderId(), e);
            }
        }
    }

    private void recordEvent(Deal deal, DealEventType type, String msg) {
        DealEvent ev = new DealEvent();
        ev.setDeal(deal);
        ev.setEventType(type);
        ev.setRawMessage(msg);
        eventRepo.save(ev);
    }
}
