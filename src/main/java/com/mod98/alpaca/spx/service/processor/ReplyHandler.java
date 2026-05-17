package com.mod98.alpaca.spx.service.processor;

import com.mod98.alpaca.spx.domain.PendingEntry;
import com.mod98.alpaca.spx.domain.SignalType;
import com.mod98.alpaca.spx.repo.PendingEntryRepository;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * يعالج ردود admin على PREP messages.
 *
 * Handles 3 cases:
 *   1. CANCEL → علّم PREP consumed مع reason='admin_cancel'
 *   2. UPDATE expiry → حدّث expiry_date في PREP
 *   3. UPDATE strike → حدّث strike في PREP
 *
 * Note: SL/TP updates يتم تجاهلها (نلتزم بـ $100 ثابت).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyHandler {

    private final PendingEntryRepository pendingRepo;

    @Transactional
    public void handle(ParsedSignal signal) {
        if (signal.getReplyToMessageId() == null) {
            log.debug("REPLY ignored — no replyToId");
            return;
        }

        // ابحث الـ PREP الأصلية بـ replyToMessageId
        PendingEntry prep = pendingRepo
                .findByTelegramMessageId(signal.getReplyToMessageId())
                .orElse(null);

        if (prep == null) {
            log.debug("REPLY ignored — no PREP found for replyTo={}",
                    signal.getReplyToMessageId());
            return;
        }

        if (!prep.isActive()) {
            log.warn("REPLY ignored — PREP already consumed | prepId={} reason={}",
                    prep.getId(), prep.getConsumedReason());
            return;
        }

        // Dispatch
        if (signal.getSignalType() == SignalType.CANCEL) {
            handleCancel(prep, signal);
        } else if (signal.getSignalType() == SignalType.UPDATE) {
            handleUpdate(prep, signal);
        }
    }

    private void handleCancel(PendingEntry prep, ParsedSignal signal) {
        prep.markConsumed("admin_cancel", null);
        pendingRepo.save(prep);
        log.info("❌ PREP cancelled by admin | prepId={} replyMsgId={}",
                prep.getId(), signal.getTelegramMessageId());
    }

    private void handleUpdate(PendingEntry prep, ParsedSignal signal) {
        boolean updated = false;
        StringBuilder changes = new StringBuilder();

        // Strike update
        BigDecimal newStrike = signal.getStrike();
        if (newStrike != null && !newStrike.equals(prep.getStrike())) {
            changes.append("strike: ").append(prep.getStrike())
                    .append(" → ").append(newStrike).append(" ");
            prep.setStrike(newStrike);
            updated = true;
        }

        // Expiry update
        LocalDate newExpiry = signal.getExpiryDate();
        if (newExpiry != null && !newExpiry.equals(prep.getExpiryDate())) {
            changes.append("expiry: ").append(prep.getExpiryDate())
                    .append(" → ").append(newExpiry).append(" ");
            prep.setExpiryDate(newExpiry);
            updated = true;
        }

        if (updated) {
            pendingRepo.save(prep);
            log.info("🔧 PREP updated | prepId={} changes=[{}]",
                    prep.getId(), changes.toString().trim());
        } else {
            log.debug("UPDATE no-op (no changes) | prepId={}", prep.getId());
        }
    }
}
