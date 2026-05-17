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

import java.math.BigDecimal;

/**
 * UPDATE handler — تعديل SL/TP/Entry على Deal موجودة عبر Reply.
 *
 * Rules:
 *  - الـ Deal يجب تكون ENTRY_PENDING أو ENTERED
 *  - لو ENTERED: نعدّل TP/SL في IBKR (modifyOrder)
 *  - لو ENTRY_PENDING: نعدّل في DB فقط (الـ TP/SL لم تُرسل بعد)
 *  - تعديل Entry فقط مسموح إذا ENTRY_PENDING (مش ENTERED — السعر تم تنفيذه)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateHandler {

    private final DealRepository dealRepo;
    private final DealEventRepository eventRepo;
    private final IbkrExecutionService execution;

    @Transactional
    public void handle(ParsedSignal signal) {
        if (!signal.isValidReply() || !signal.hasUpdateData()) {
            log.warn("UPDATE skipped — invalid payload | msgId={}", signal.getTelegramMessageId());
            return;
        }

        Deal deal = dealRepo.findByTelegramMessageId(signal.getReplyToMessageId()).orElse(null);
        if (deal == null) {
            log.warn("UPDATE skipped — no deal for replyTo={}", signal.getReplyToMessageId());
            return;
        }

        // Validate state — لا يجوز تعديل على terminal status
        if (deal.getStatus().isTerminal()) {
            log.warn("UPDATE rejected — deal in terminal state | dealId={} status={}",
                    deal.getId(), deal.getStatus());
            return;
        }

        StringBuilder changes = new StringBuilder();

        // تعديل Entry — فقط لو الصفقة لم تُنفّذ بعد
        if (signal.getNewEntry() != null) {
            if (deal.getStatus() == DealStatus.ENTRY_PENDING) {
                BigDecimal old = deal.getEntrySignalPrice();
                deal.setEntrySignalPrice(signal.getNewEntry());
                changes.append("entry: ").append(old).append("→").append(signal.getNewEntry()).append("; ");
                // TODO: cancel + replace IBKR order with new limit price
                log.warn("Entry modification requested but order modification on IBKR not yet implemented | dealId={}",
                        deal.getId());
            } else {
                log.warn("UPDATE entry ignored — deal already ENTERED | dealId={}", deal.getId());
            }
        }

        // تعديل SL
        if (signal.getNewStopLoss() != null) {
            BigDecimal old = deal.getSlPrice();
            deal.setSlPrice(signal.getNewStopLoss());
            changes.append("SL: ").append(old).append("→").append(signal.getNewStopLoss()).append("; ");

            // لو الصفقة مفتوحة، عدّل أمر SL في IBKR
            if (deal.getStatus() == DealStatus.ENTERED && deal.getIbkrSlOrderId() != null) {
                try {
                    execution.modifyStopLoss(deal, signal.getNewStopLoss());
                } catch (Exception e) {
                    log.error("Failed to modify SL on IBKR | dealId={}", deal.getId(), e);
                    recordError(deal, "modifyStopLoss failed: " + e.getMessage());
                    return;
                }
            }
        }

        // تعديل TP
        if (signal.getNewTakeProfit() != null) {
            BigDecimal old = deal.getTpPrice();
            deal.setTpPrice(signal.getNewTakeProfit());
            changes.append("TP: ").append(old).append("→").append(signal.getNewTakeProfit()).append("; ");

            if (deal.getStatus() == DealStatus.ENTERED && deal.getIbkrTpOrderId() != null) {
                try {
                    execution.modifyTakeProfit(deal, signal.getNewTakeProfit());
                } catch (Exception e) {
                    log.error("Failed to modify TP on IBKR | dealId={}", deal.getId(), e);
                    recordError(deal, "modifyTakeProfit failed: " + e.getMessage());
                    return;
                }
            }
        }

        dealRepo.save(deal);

        DealEvent ev = new DealEvent();
        ev.setDeal(deal);
        ev.setEventType(DealEventType.PREPARE_UPDATED);   // reuse enum, or add UPDATE_APPLIED
        ev.setRawMessage("UPDATE: " + changes + " | raw=" + signal.getRawText());
        eventRepo.save(ev);

        log.info("✅ UPDATE applied | dealId={} changes=[{}]", deal.getId(), changes);
    }

    private void recordError(Deal deal, String msg) {
        DealEvent err = new DealEvent();
        err.setDeal(deal);
        err.setEventType(DealEventType.EXECUTION_ERROR);
        err.setRawMessage(msg);
        eventRepo.save(err);
    }
}
