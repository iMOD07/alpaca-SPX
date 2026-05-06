package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.ibkr.IbkrExecutionService;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.service.DealStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * المنطق الذهبي (لا تغيّر):
 *  - PREPARE: حفظ فقط، لا تنفيذ.
 *  - ENTRY: ينفّذ فقط لو فيه PREPARE صالح، ضمن الـ range [signal-min, signal+max].
 *  - TP/SL محسوبة على signal price فقط (preparePrice).
 *
 * هذا الكلاس مسؤول عن:
 *  1. إيجاد الـ PREPARE المرتبطة (عبر replyTo أو آخر PREPARE حي مطابق).
 *  2. التحقق من صلاحيتها (لم تنتهِ، لم تُلغَ، لم تُنفّذ سابقاً).
 *  3. حسابات TP/SL على preparePrice.
 *  4. تنفيذ الـ entry عبر IBKR.
 *  5. Status الحقيقي يتحدّث من OrderTrackingService بناءً على callbacks IBKR.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntrySignalHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;
    private final IbkrExecutionService ibkrExecutionService;
    private final TradingProperties trading;
    private final DealStateMachine stateMachine;

    @Override
    @Transactional
    public Deal handle(ParsedSignal signal) {
        if (!trading.isTradingEnabled()) {
            log.warn("ENTRY ignored — tradingEnabled=false (kill switch active)");
            return null;
        }

        // 1) إيجاد الـ PREPARE المطابقة
        Deal prepare = findMatchingPrepare(signal);
        if (prepare == null) {
            log.warn("ENTRY BLOCKED | no matching PREPARE found | signalMsgId={} replyTo={}",
                    signal.getTelegramMessageId(), signal.getReplyToMessageId());
            recordBlocked(null, DealEventType.ENTRY_BLOCKED_NO_PREPARE,
                    signal.getEntrySignalPrice(), signal.getRawText());
            return null;
        }

        // 2) التحقق من صلاحية الـ PREPARE
        if (prepare.getStatus() != DealStatus.PREPARE) {
            log.warn("ENTRY BLOCKED | deal not in PREPARE state | dealId={} status={}",
                    prepare.getId(), prepare.getStatus());
            recordBlocked(prepare, DealEventType.ENTRY_BLOCKED_DUPLICATE,
                    signal.getEntrySignalPrice(), signal.getRawText());
            return prepare;
        }

        Duration age = Duration.between(prepare.getCreatedAt(), Instant.now());
        if (age.compareTo(trading.getPrepareValidity()) > 0) {
            log.warn("ENTRY BLOCKED | PREPARE expired | dealId={} ageMin={}",
                    prepare.getId(), age.toMinutes());
            stateMachine.transition(prepare, DealStatus.CANCELLED);
            dealRepository.save(prepare);
            recordBlocked(prepare, DealEventType.CANCELLED,
                    signal.getEntrySignalPrice(), "PREPARE expired");
            return prepare;
        }

        // 3) حساب TP/SL على preparePrice (المنطق الذهبي)
        BigDecimal signalPrice = prepare.getPreparePrice();
        BigDecimal tp = signalPrice.add(trading.getTpOffset()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sl = signalPrice.subtract(trading.getSlOffset()).setScale(2, RoundingMode.HALF_UP);

        prepare.setEntrySignalPrice(signalPrice);
        prepare.setTpPrice(tp);
        prepare.setSlPrice(sl);
        prepare.setSignalReceivedAt(Instant.now());

        // 4) سجل استلام إشارة الدخول
        DealEvent received = new DealEvent();
        received.setDeal(prepare);
        received.setEventType(DealEventType.ENTRY_SIGNAL_RECEIVED);
        received.setEventPrice(signalPrice);
        received.setRawMessage(signal.getRawText());
        eventRepository.save(received);

        // 5) ارفع الحالة قبل إرسال الأمر (atomic)
        stateMachine.transition(prepare, DealStatus.ENTRY_PENDING);
        prepare.setOrderSentAt(Instant.now());
        Deal saved = dealRepository.save(prepare);

        // 6) أرسل الأمر إلى IBKR
        IbkrExecutionService.EntryDecisionResult result;
        try {
            result = ibkrExecutionService.placeEntry(saved);
        } catch (Exception e) {
            log.error("ENTRY EXECUTION FAILED | dealId={}", saved.getId(), e);
            stateMachine.transition(saved, DealStatus.FAILED);
            dealRepository.save(saved);
            DealEvent err = new DealEvent();
            err.setDeal(saved);
            err.setEventType(DealEventType.EXECUTION_ERROR);
            err.setRawMessage(e.getMessage());
            eventRepository.save(err);
            return saved;
        }

        // 7) معالجة قرار التنفيذ
        if (!result.placed()) {
            log.warn("ENTRY BLOCKED | dealId={} reason={} ask={} bid={} range=[{}..{}]",
                    saved.getId(), result.blockReason(), result.ask(), result.bid(),
                    result.lower(), result.upper());

            // ارجع للـ PREPARE حتى نسمح بمحاولة لاحقة (لو سعر ASK رجع للرينج)
            stateMachine.transition(saved, DealStatus.PREPARE);
            dealRepository.save(saved);

            recordBlocked(saved, DealEventType.ENTRY_BLOCKED_OUT_OF_RANGE,
                    result.ask(), "reason=" + result.blockReason());
            return saved;
        }

        // 8) أمر مُرسَل — الانتظار للـ orderStatus callback
        saved.setIbkrEntryOrderId(result.orderId());
        saved.setIbkrContractId(result.conId());
        saved.setEntryMinPrice(result.lower());
        saved.setEntryMaxPrice(result.upper());
        saved.setCurrentPrice(result.ask());
        dealRepository.save(saved);

        DealEvent placed = new DealEvent();
        placed.setDeal(saved);
        placed.setEventType(DealEventType.ENTRY_ORDER_PLACED);
        placed.setEventPrice(result.ask());
        eventRepository.save(placed);

        log.info("ENTRY PLACED | dealId={} orderId={} ask={} limit={} tp={} sl={}",
                saved.getId(), result.orderId(), result.ask(), result.upper(), tp, sl);

        return saved;
    }

    /**
     * استراتيجية الإيجاد:
     *  1. لو الإشارة reply لرسالة → ابحث عن Deal بنفس telegramMessageId.
     *  2. وإلا → ابحث عن آخر PREPARE مطابق على (strike, optionType, expiry).
     *  3. وإلا → آخر PREPARE حي بشكل عام (fallback آمن).
     */
    private Deal findMatchingPrepare(ParsedSignal signal) {
        // (1) reply صريح
        if (signal.getReplyToMessageId() != null) {
            Deal d = dealRepository.findByTelegramMessageId(signal.getReplyToMessageId()).orElse(null);
            if (d != null && d.getStatus() == DealStatus.PREPARE) return d;
        }

        // (2) match دقيق على contract
        if (signal.getStrike() != null && signal.getOptionType() != null && signal.getExpiryDate() != null) {
            List<Deal> candidates = dealRepository
                    .findByStatusAndSymbolAndStrikeAndOptionTypeAndExpiryDateOrderByCreatedAtDesc(
                            DealStatus.PREPARE,
                            signal.getSymbol() != null ? signal.getSymbol() : "SPXW",
                            signal.getStrike(),
                            signal.getOptionType(),
                            signal.getExpiryDate()
                    );
            if (!candidates.isEmpty()) return candidates.get(0);
        }

        // (3) آخر PREPARE حي
        List<Deal> open = dealRepository.findByStatusOrderByCreatedAtDesc(DealStatus.PREPARE);
        return open.stream().max(Comparator.comparing(Deal::getCreatedAt)).orElse(null);
    }

    private void recordBlocked(Deal deal, DealEventType type, BigDecimal price, String raw) {
        DealEvent e = new DealEvent();
        e.setDeal(deal);
        e.setEventType(type);
        e.setEventPrice(price);
        e.setRawMessage(raw);
        eventRepository.save(e);
    }
}
