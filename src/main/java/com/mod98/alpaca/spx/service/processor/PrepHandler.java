package com.mod98.alpaca.spx.service.processor;

import com.mod98.alpaca.spx.domain.PendingEntry;
import com.mod98.alpaca.spx.repo.PendingEntryRepository;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * يستقبل رسائل PREP من admin ويحفظها في pending_entries.
 *
 * لا يرسل أي شي لـ IBKR. فقط يخزّن "ready-to-execute context"
 * حتى تجي رسالة ENTRY مطابقة.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrepHandler {

    private final PendingEntryRepository pendingRepo;

    @Transactional
    public void handle(ParsedSignal signal) {
        // Idempotency: لو نفس messageId سبق ما حُفظ، تجاهل
        if (pendingRepo.findByTelegramMessageId(signal.getTelegramMessageId()).isPresent()) {
            log.warn("PREP duplicate — already saved | msgId={}", signal.getTelegramMessageId());
            return;
        }

        PendingEntry pe = new PendingEntry();
        pe.setTelegramMessageId(signal.getTelegramMessageId());
        pe.setOptionType(signal.getOptionType());
        pe.setStrike(signal.getStrike());
        pe.setEntryPrice(signal.getEntryPrice());
        pe.setExpiryDate(signal.getExpiryDate());
        pe.setRawText(signal.getRawText());

        pendingRepo.save(pe);

        log.info("📋 PREP saved | id={} msgId={} type={} strike={} price={} expiry={}",
                pe.getId(),
                pe.getTelegramMessageId(),
                pe.getOptionType(),
                pe.getStrike(),
                pe.getEntryPrice(),
                pe.getExpiryDate());
    }
}
