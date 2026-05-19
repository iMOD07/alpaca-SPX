package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Recovery on startup — handle stranded deals from previous crash/restart.
 *
 * v3.1 changes:
 *  ✅ Uses DealStateMachine for transitions (no direct setStatus)
 *  ⚠️ Still relies on DB as source of truth (IBKR reconciliation = future improvement)
 *
 * Actions:
 *  1. ENTRY_PENDING from before crash → mark FAILED (needs manual review in TWS)
 *  2. ENTERED without bracket → log error (position UNPROTECTED, manual intervention)
 *  3. ENTERED with bracket → assume healthy, OrderTrackingService picks up callbacks
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryService {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;
    private final DealStateMachine stateMachine;   // ⭐ NEW v3.1

    @PostConstruct
    @Transactional
    public void onStartup() {
        log.info("🔄 RecoveryService: scanning for stranded deals from previous run...");

        // 1) ENTRY_PENDING — crashed while awaiting fill
        List<Deal> pending = dealRepository.findByStatusIn(List.of(DealStatus.ENTRY_PENDING));
        for (Deal d : pending) {
            log.error("⚠️ RECOVERY: dealId={} was ENTRY_PENDING at shutdown | orderId={} signalAt={}",
                    d.getId(), d.getIbkrEntryOrderId(), d.getSignalReceivedAt());

            // ✅ Use state machine
            stateMachine.transition(d, DealStatus.FAILED);
            dealRepository.save(d);
            recordEvent(d, DealEventType.RECOVERY_START,
                    "ENTRY_PENDING at startup — needs manual review in TWS");
        }

        // 2) ENTERED without bracket — UNPROTECTED position
        List<Deal> entered = dealRepository.findByStatusIn(List.of(DealStatus.ENTERED));
        for (Deal d : entered) {
            boolean unprotected = (d.getIbkrTpOrderId() == null) || (d.getIbkrSlOrderId() == null);
            if (unprotected) {
                log.error("⚠️ RECOVERY: dealId={} ENTERED without bracket! tpId={} slId={}",
                        d.getId(), d.getIbkrTpOrderId(), d.getIbkrSlOrderId());
                recordEvent(d, DealEventType.EXECUTION_ERROR,
                        "Recovery: ENTERED without bracket — manual intervention required in TWS");
                // ⚠️ DO NOT auto-place bracket at startup — price has changed, needs human review
            } else {
                log.info("✓ RECOVERY: dealId={} ENTERED with bracket tpId={} slId={}",
                        d.getId(), d.getIbkrTpOrderId(), d.getIbkrSlOrderId());
                recordEvent(d, DealEventType.RECOVERY_DONE, "ENTERED+bracket — assumed healthy");
            }
        }

        log.info("🔄 RecoveryService: complete — {} pending→FAILED, {} entered checked",
                pending.size(), entered.size());
    }

    private void recordEvent(Deal deal, DealEventType type, String message) {
        DealEvent e = new DealEvent();
        e.setDeal(deal);
        e.setEventType(type);
        e.setRawMessage(message);
        eventRepository.save(e);
    }
}
