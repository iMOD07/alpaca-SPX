package com.mod98.alpaca.spx.ibkr;

import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.ibkr.events.ExecutionEvent;
import com.mod98.alpaca.spx.ibkr.events.OrderStatusEvent;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.service.DealStateMachine;
import com.mod98.alpaca.spx.service.MarketHoursService;
import com.mod98.alpaca.spx.service.RiskLimitService;
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
 * Bridges IBKR callbacks (OrderStatus, Execution) → Deal state machine.
 *
 * Principles:
 *  - Idempotent: same event multiple times is safe
 *  - Market-aware: don't cancel pending during off-hours
 *  - State-strict: all transitions via DealStateMachine
 *  - Resilient: log + alert on bracket failure
 *
 * v3.1 changes:
 *  ✅ recordOutcome() on every CLOSED deal (feeds RiskLimitService)
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
    private final RiskLimitService riskLimit;   // ⭐ NEW v3.1

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

        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrEntryOrderId())) {
            handleEntryStatus(deal, ev);
            return;
        }
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrTpOrderId())) {
            if (ev.isFilled()) handleExitFilled(deal, DealEventType.TP_HIT, ev);
            return;
        }
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrSlOrderId())) {
            if (ev.isFilled()) handleExitFilled(deal, DealEventType.SL_HIT, ev);
            return;
        }
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrExitOrderId())) {
            if (ev.isFilled()) handleExitFilled(deal, DealEventType.EXIT_FILLED, ev);
        }
    }

    @EventListener
    @Async("ibkrEventExecutor")
    public void onExecution(ExecutionEvent ev) {
        log.info("🔔 ExecutionEvent | orderId={} price={} qty={} conId={}",
                ev.orderId(), ev.price(), ev.quantity(), ev.conId());
    }

    private void handleEntryStatus(Deal deal, OrderStatusEvent ev) {
        if (ev.isFilled()) {
            if (deal.getStatus() != DealStatus.ENTRY_PENDING) {
                log.debug("Ignoring duplicate Filled event | dealId={} status={}",
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

            if (trading.isPlaceBracketOrders() && deal.getIbkrTpOrderId() == null) {
                placeBracketSafely(deal);
            }
            return;
        }

        if (ev.isCancelled() || ev.isInactive()) {
            if (deal.getStatus() != DealStatus.ENTRY_PENDING) {
                log.debug("Ignoring cancel event | dealId={} status={}",
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
            log.error("⚠️ BRACKET PLACEMENT FAILED — POSITION UNPROTECTED | dealId={}",
                    deal.getId(), e);
            eventRepository.save(buildEvent(deal, DealEventType.EXECUTION_ERROR, null,
                    "BRACKET_FAILED: " + e.getMessage()));
        }
    }

    /**
     * Handle TP/SL/Manual exit fill.
     *
     * v3.1: feeds RiskLimitService — kill switch after 4 consecutive losses.
     */
    private void handleExitFilled(Deal deal, DealEventType type, OrderStatusEvent ev) {
        if (deal.getStatus() == DealStatus.CLOSED) {
            log.debug("Ignoring duplicate exit event | dealId={} type={}", deal.getId(), type);
            return;
        }

        BigDecimal exitPrice = BigDecimal.valueOf(ev.avgFillPrice()).setScale(2, RoundingMode.HALF_UP);

        stateMachine.transition(deal, DealStatus.CLOSED);
        dealRepository.save(deal);
        eventRepository.save(buildEvent(deal, type, exitPrice));

        // Compute PnL = (exit - entry) × qty × multiplier(100)
        BigDecimal pnl = null;
        if (deal.getEntryPrice() != null) {
            pnl = exitPrice.subtract(deal.getEntryPrice())
                    .multiply(BigDecimal.valueOf(trading.getOptionQty() * 100L))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        log.info("🏁 DEAL CLOSED | dealId={} type={} entry={} exit={} qty={} pnl=${}",
                deal.getId(), type, deal.getEntryPrice(), exitPrice, deal.getFilledQty(), pnl);

        // ⭐ v3.1: feed risk service (kill switch after 4 losses)
        if (pnl != null) {
            try {
                riskLimit.recordOutcome(deal.getId(), pnl);
            } catch (Exception e) {
                log.error("Failed to record risk outcome | dealId={}", deal.getId(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void checkPendingTimeouts() {
        List<Deal> pending = dealRepository.findByStatusIn(List.of(DealStatus.ENTRY_PENDING));
        if (pending.isEmpty()) return;

        if (!marketHours.isMarketOpen()) {
            log.debug("Market closed — skip pending timeout check ({} pending)", pending.size());
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
        }
    }

    // Helpers
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
