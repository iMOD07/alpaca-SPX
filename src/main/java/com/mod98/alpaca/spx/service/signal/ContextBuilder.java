package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.repo.DealRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * يبني context للرسالة الجديدة:
 *  - لو الرسالة reply: يجلب الـ Deal المرتبطة بـ replyToMessageId
 *  - يضيف معلومات السياق (strike, optionType, expiry, status) إلى الـ AI prompt
 *
 * هذا يساعد AI يفهم رسائل مختصرة مثل "تحديث 3.4" — يعرف لأي صفقة.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextBuilder {

    private final DealRepository dealRepository;

    public MessageContext buildContext(Long replyToMessageId) {
        if (replyToMessageId == null) {
            return MessageContext.empty();
        }
        Optional<Deal> opt = dealRepository.findByTelegramMessageId(replyToMessageId);
        if (opt.isEmpty()) {
            log.debug("No deal found for replyTo={}", replyToMessageId);
            return MessageContext.empty();
        }
        Deal d = opt.get();
        return new MessageContext(
                d.getId(),
                d.getSymbol(),
                d.getOptionType(),
                d.getStrike() != null ? d.getStrike().toPlainString() : null,
                d.getExpiryDate() != null ? d.getExpiryDate().toString() : null,
                d.getStatus() != null ? d.getStatus().name() : null,
                d.getPreparePrice() != null ? d.getPreparePrice().toPlainString() : null
        );
    }

    public record MessageContext(
            Long dealId,
            String symbol,
            String optionType,
            String strike,
            String expiryDate,
            String status,
            String preparePrice
    ) {
        public static MessageContext empty() {
            return new MessageContext(null, null, null, null, null, null, null);
        }
        public boolean isEmpty() { return dealId == null; }

        public String toPromptHint() {
            if (isEmpty()) return "(no prior context)";
            return String.format(
                    "PRIOR DEAL CONTEXT (replied-to message): " +
                            "symbol=%s | type=%s | strike=%s | expiry=%s | status=%s | preparePrice=%s",
                    symbol, optionType, strike, expiryDate, status, preparePrice);
        }
    }
}
