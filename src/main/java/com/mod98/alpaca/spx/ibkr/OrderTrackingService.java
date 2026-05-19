package com.mod98.alpaca.spx.ibkr;

import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.ibkr.events.ExecutionEvent;
import com.mod98.alpaca.spx.ibkr.events.OrderStatusEvent;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.service.DealStateMachine;
import com.mod98.alpaca.spx.service.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * يربط callbacks IBKR (OrderStatus, Execution) مع state machine الـ Deal.
 *
 * مبادئ التصميم:
 *  - idempotent: نفس event مرات متعددة لا يعدّل state بشكل خاطئ
 *  - market-aware: لا يلغي pending orders أثناء إغلاق السوق
 *  - state-strict: يعتمد على DealStateMachine لمنع transitions غير شرعية
 *  - resilient: لو bracket placement فشل، deal تُعلَّم للمراجعة (لا يُترك مفتوحاً بلا حماية)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTrackingService {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;
    private final IbkrExecutionService ibkrExecutionService;
    private final DealStateMachine stateMachine;
    private final TradingProperties trading;
    private final MarketHoursService marketHours;

    @EventListener
    @Async("ibkrEventExecutor")
    @Transactional
    public void onOrderStatus(OrderStatusEvent ev) {
        log.info("🔔 OrderStatusEvent | orderId={} status={} filled={} remaining={}",
                ev.orderId(), ev.status(), ev.filled(), ev.remaining());

        Deal deal = dealRepository.findByAnyOrderId(ev.orderId()).orElse(null);
        if (deal == null) {
            log.debug("Order status for unknown orderId={} — ignored", ev.orderId());
            return;
        }

        // ENTRY order
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrEntryOrderId())) {
            handleEntryStatus(deal, ev);
            return;
        }
        // TP order
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrTpOrderId())) {
            if (ev.isFilled()) handleExitFilled(deal, DealEventType.TP_HIT, ev);
            return;
        }
        // SL order
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrSlOrderId())) {
            if (ev.isFilled()) handleExitFilled(deal, DealEventType.SL_HIT, ev);
            return;
        }
        // Manual exit
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrExitOrderId())) {
            if (ev.isFilled()) handleExitFilled(deal, DealEventType.EXIT_FILLED, ev);
        }
    }

    @EventListener
    @Async("ibkrEventExecutor")
    public void onExecution(ExecutionEvent ev) {
        log.info("🔔 ExecutionEvent | orderId={} price={} qty={} conId={}",
                ev.orderId(), ev.price(), ev.quantity(), ev.conId());
        // For audit/reconciliation — يمكن إضافة جدول executions لاحقاً
    }

    /**
     * Idempotent ENTRY status handler:
     *  - Filled مرة واحدة فقط (يفحص الحالة قبل المعالجة)
     *  - Bracket يُوضع مرة واحدة فقط (يفحص ibkrTpOrderId)
     */
    private void handleEntryStatus(Deal deal, OrderStatusEvent ev) {
        if (ev.isFilled()) {
            // ⚠️ Idempotency guard: لو الـ deal سبق ما اعتُبر filled، تجاهل
            if (deal.getStatus() != DealStatus.ENTRY_PENDING) {
                log.debug("Ignoring duplicate Filled event | dealId={} currentStatus={}",
                        deal.getId(), deal.getStatus());
                return;
            }

            BigDecimal avg = BigDecimal.valueOf(ev.avgFillPrice()).setScale(2, RoundingMode.HALF_UP);
            deal.setEntryPrice(avg);
            deal.setFilledQty((int) ev.filled());
            deal.setFilledAt(Instant.now());

            stateMachine.transition(deal, DealStatus.ENTERED);
            dealRepository.save(deal);

            eventRepository.save(buildEvent(deal, DealEventType.ENTRY_FILLED, avg));

            long latencyMs = deal.getOrderSentAt() != null
                    ? Duration.between(deal.getOrderSentAt(), Instant.now()).toMillis() : -1;
            log.info("✅ ENTRY FILLED | dealId={} signal={} fill={} qty={} latencyMs={}",
                    deal.getId(), deal.getEntrySignalPrice(), avg, ev.filled(), latencyMs);

            // Place bracket — مرة واحدة فقط
            if (trading.isPlaceBracketOrders() && deal.getIbkrTpOrderId() == null) {
                placeBracketSafely(deal);
            }
            return;
        }

        if (ev.isCancelled() || ev.isInactive()) {
            // ⚠️ Idempotency: لو الـ deal سبق وانتقل لحالة أخرى، تجاهل
            if (deal.getStatus() != DealStatus.ENTRY_PENDING) {
                log.debug("Ignoring cancel event | dealId={} currentStatus={}",
                        deal.getId(), deal.getStatus());
                return;
            }
            log.warn("❌ ENTRY CANCELLED/INACTIVE | dealId={} status={} whyHeld={}",
                    deal.getId(), ev.status(), ev.whyHeld());
            stateMachine.transition(deal, DealStatus.CANCELLED);
            dealRepository.save(deal);
            eventRepository.save(buildEvent(deal, DealEventType.ENTRY_ORDER_REJECTED, null, ev.status()));
        }
    }

    private void placeBracketSafely(Deal deal) {
        try {
            IbkrExecutionService.BracketResult bracket = ibkrExecutionService.placeBracket(deal);
            deal.setIbkrTpOrderId(bracket.tpOrderId());
            deal.setIbkrSlOrderId(bracket.slOrderId());
            dealRepository.save(deal);

            eventRepository.save(buildEvent(deal, DealEventType.TP_PLACED, deal.getTpPrice()));
            eventRepository.save(buildEvent(deal, DealEventType.SL_PLACED, deal.getSlPrice()));
            log.info("📌 BRACKET PLACED | dealId={} tpId={}@{} slId={}@{}",
                    deal.getId(), bracket.tpOrderId(), deal.getTpPrice(),
                    bracket.slOrderId(), deal.getSlPrice());
        } catch (Exception e) {
            // ⚠️ Critical: ENTERED بدون bracket = position بلا حماية!
            log.error("⚠️ BRACKET PLACEMENT FAILED — POSITION UNPROTECTED | dealId={}", deal.getId(), e);
            eventRepository.save(buildEvent(deal, DealEventType.EXECUTION_ERROR, null,
                    "BRACKET_FAILED: " + e.getMessage()));
            // النظام يجب يطلق alert هنا — TODO: integrate with Telegram alert sender
        }
    }



    private void handleExitFilled(Deal deal, DealEventType type, OrderStatusEvent ev) {
        if (deal.getStatus() == DealStatus.CLOSED) {
            log.debug("Ignoring duplicate exit event | dealId={} type={}", deal.getId(), type);
            return;
        }

        BigDecimal exitPrice = BigDecimal.valueOf(ev.avgFillPrice()).setScale(2, RoundingMode.HALF_UP);

        stateMachine.transition(deal, DealStatus.CLOSED);
        dealRepository.save(deal);
        eventRepository.save(buildEvent(deal, type, exitPrice));

        BigDecimal pnl = (deal.getEntryPrice() != null)
                ? exitPrice.subtract(deal.getEntryPrice())
                .multiply(BigDecimal.valueOf(trading.getOptionQty() * 100L))
                : null;
        log.info("🏁 DEAL CLOSED | dealId={} type={} entry={} exit={} qty={} pnl=${}",
                deal.getId(), type, deal.getEntryPrice(), exitPrice, deal.getFilledQty(), pnl);
    }



    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void checkPendingTimeouts() {
        List<Deal> pending = dealRepository.findByStatusIn(List.of(DealStatus.ENTRY_PENDING));
        if (pending.isEmpty()) return;

        // ⚠️ لا تلغ pending orders لو السوق مغلق
        if (!marketHours.isMarketOpen()) {
            log.debug("Market closed — skip pending timeout check ({} pending deals)", pending.size());
            return;
        }

        Instant cutoff = Instant.now().minus(trading.getEntryFillTimeout());
        for (Deal d : pending) {
            if (d.getOrderSentAt() == null) continue;
            if (!d.getOrderSentAt().isBefore(cutoff)) continue;

            log.warn("⏱️ ENTRY TIMEOUT | dealId={} orderId={} sentAt={} ageSec={}",
                    d.getId(), d.getIbkrEntryOrderId(), d.getOrderSentAt(),
                    Duration.between(d.getOrderSentAt(), Instant.now()).toSeconds());

            try {
                if (d.getIbkrEntryOrderId() != null) {
                    ibkrExecutionService.cancelOrder(d.getIbkrEntryOrderId());
                }
            } catch (Exception e) {
                log.error("Cancel failed | orderId={}", d.getIbkrEntryOrderId(), e);
            }
            // ملاحظة: لا ننقل الحالة هنا — انتظر OrderStatusEvent("Cancelled")
            // لو ما وصل بعد دقيقة، fallback transition سيحصل في scheduled cleanup منفصل
        }
    }

    // ========= Helpers =========
    private DealEvent buildEvent(Deal deal, DealEventType type, BigDecimal price) {
        return buildEvent(deal, type, price, null);
    }

    private DealEvent buildEvent(Deal deal, DealEventType type, BigDecimal price, String raw) {
        DealEvent e = new DealEvent();
        e.setDeal(deal);
        e.setEventType(type);
        e.setEventPrice(price);
        e.setRawMessage(raw);
        return e;
    }
}
