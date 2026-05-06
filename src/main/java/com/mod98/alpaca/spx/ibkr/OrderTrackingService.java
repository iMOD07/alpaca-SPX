package com.mod98.alpaca.spx.ibkr;

import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.ibkr.events.OrderStatusEvent;
import com.mod98.alpaca.spx.ibkr.events.ExecutionEvent;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.service.DealStateMachine;
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
 * يستهلك أحداث IBKR (OrderStatusEvent, ExecutionEvent) ويحدّث الـ Deal state بشكل صحيح.
 *
 * هذا هو "العقل" الذي يربط ما يصير في IBKR مع DB:
 *  - Filled       → ENTERED + place TP/SL bracket
 *  - Cancelled    → CANCELLED
 *  - Rejected     → CANCELLED + ENTRY_ORDER_REJECTED event
 *  - Timeout      → cancel + CANCELLED
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

    /**
     * يُستدعى من ApplicationEventPublisher داخل IbkrApiWrapper.orderStatus().
     * @Async حتى لا نحجز thread الـ ibkr-reader.
     */
    @EventListener
    @Async("ibkrEventExecutor")
    @Transactional
    public void onOrderStatus(OrderStatusEvent ev) {
        Deal deal = findDealByOrderId(ev.orderId());
        if (deal == null) {
            log.debug("Order status for unknown orderId={} — ignoring", ev.orderId());
            return;
        }

        // ENTRY order
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrEntryOrderId())) {
            handleEntryStatus(deal, ev);
            return;
        }

        // TP order
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrTpOrderId())) {
            if (ev.isFilled()) {
                handleExitFilled(deal, DealEventType.TP_HIT, ev);
            }
            return;
        }

        // SL order
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrSlOrderId())) {
            if (ev.isFilled()) {
                handleExitFilled(deal, DealEventType.SL_HIT, ev);
            }
            return;
        }

        // Manual exit
        if (Integer.valueOf(ev.orderId()).equals(deal.getIbkrExitOrderId())) {
            if (ev.isFilled()) {
                handleExitFilled(deal, DealEventType.EXIT_FILLED, ev);
            }
            return;
        }
    }

    @EventListener
    @Async("ibkrEventExecutor")
    public void onExecution(ExecutionEvent ev) {
        log.info("Execution received | orderId={} price={} qty={}", ev.orderId(), ev.price(), ev.quantity());
        // Optional: store execution details for reconciliation/audit
    }

    private void handleEntryStatus(Deal deal, OrderStatusEvent ev) {
        if (ev.isFilled()) {
            BigDecimal avg = BigDecimal.valueOf(ev.avgFillPrice()).setScale(2, RoundingMode.HALF_UP);
            deal.setEntryPrice(avg);
            deal.setFilledQty((int) ev.filled());
            deal.setFilledAt(Instant.now());

            stateMachine.transition(deal, DealStatus.ENTERED);
            dealRepository.save(deal);

            DealEvent filled = new DealEvent();
            filled.setDeal(deal);
            filled.setEventType(DealEventType.ENTRY_FILLED);
            filled.setEventPrice(avg);
            eventRepository.save(filled);

            long latencyMs = Duration.between(deal.getOrderSentAt(), Instant.now()).toMillis();
            log.info("ENTRY FILLED | dealId={} signalPrice={} fillPrice={} qty={} latencyMs={}",
                    deal.getId(), deal.getEntrySignalPrice(), avg, ev.filled(), latencyMs);

            // ضع TP/SL bracket
            if (trading.isPlaceBracketOrders()) {
                try {
                    var bracket = ibkrExecutionService.placeBracket(deal);
                    deal.setIbkrTpOrderId(bracket.tpOrderId());
                    deal.setIbkrSlOrderId(bracket.slOrderId());
                    dealRepository.save(deal);

                    eventRepository.save(buildEvent(deal, DealEventType.TP_PLACED, deal.getTpPrice()));
                    eventRepository.save(buildEvent(deal, DealEventType.SL_PLACED, deal.getSlPrice()));
                } catch (Exception e) {
                    log.error("BRACKET PLACEMENT FAILED | dealId={}", deal.getId(), e);
                    eventRepository.save(buildEvent(deal, DealEventType.EXECUTION_ERROR, null,
                            "Bracket placement failed: " + e.getMessage()));
                }
            }
            return;
        }

        if (ev.isCancelled() || ev.isInactive()) {
            log.warn("ENTRY CANCELLED/REJECTED | dealId={} status={} whyHeld={}",
                    deal.getId(), ev.status(), ev.whyHeld());
            stateMachine.transition(deal, DealStatus.CANCELLED);
            dealRepository.save(deal);
            eventRepository.save(buildEvent(deal, DealEventType.ENTRY_ORDER_REJECTED, null, ev.status()));
        }
    }

    private void handleExitFilled(Deal deal, DealEventType type, OrderStatusEvent ev) {
        BigDecimal exitPrice = BigDecimal.valueOf(ev.avgFillPrice()).setScale(2, RoundingMode.HALF_UP);

        if (deal.getStatus() != DealStatus.CLOSED) {
            stateMachine.transition(deal, DealStatus.CLOSED);
            dealRepository.save(deal);
        }

        eventRepository.save(buildEvent(deal, type, exitPrice));

        BigDecimal pnl = deal.getEntryPrice() != null
                ? exitPrice.subtract(deal.getEntryPrice())
                .multiply(new BigDecimal(trading.getOptionQty() * 100))
                : null;

        log.info("DEAL CLOSED | dealId={} type={} entry={} exit={} pnl={}",
                deal.getId(), type, deal.getEntryPrice(), exitPrice, pnl);
    }

    /**
     * Scheduled timeout watchdog:
     * كل ثانية — أي صفقة في ENTRY_PENDING > entryFillTimeout → ألغها.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void checkPendingTimeouts() {
        List<Deal> pending = dealRepository.findByStatusIn(List.of(DealStatus.ENTRY_PENDING));
        if (pending.isEmpty()) return;

        Instant cutoff = Instant.now().minus(trading.getEntryFillTimeout());
        for (Deal d : pending) {
            if (d.getOrderSentAt() != null && d.getOrderSentAt().isBefore(cutoff)) {
                log.warn("ENTRY TIMEOUT | dealId={} orderId={} sentAt={}",
                        d.getId(), d.getIbkrEntryOrderId(), d.getOrderSentAt());
                try {
                    if (d.getIbkrEntryOrderId() != null) {
                        ibkrExecutionService.cancelOrder(d.getIbkrEntryOrderId());
                    }
                } catch (Exception e) {
                    log.error("Cancel failed | orderId={}", d.getIbkrEntryOrderId(), e);
                }
                stateMachine.transition(d, DealStatus.CANCELLED);
                dealRepository.save(d);
                eventRepository.save(buildEvent(d, DealEventType.ENTRY_ORDER_TIMEOUT, null));
            }
        }
    }

    private Deal findDealByOrderId(int orderId) {
        return dealRepository.findByAnyOrderId(orderId).orElse(null);
    }

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
