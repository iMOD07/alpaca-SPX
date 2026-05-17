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
 * عند startup — يكتشف ويعالج deals "العالقة" من crash سابق.
 *
 * الإجراءات:
 *  1. ENTRY_PENDING من قبل crash → علّمها FAILED للمراجعة اليدوية.
 *     السبب: لا نعرف هل الأمر فعلاً وصل لـ IBKR أم لا. الأنسب أن يُراجع المستخدم.
 *
 *  2. ENTERED بدون TP/SL orderId → log + alert (موقف خطر — position بلا حماية).
 *     لا نضع bracket تلقائياً لأن السعر تغيّر، يحتاج قرار بشري.
 *
 *  3. ENTERED مع TP/SL orderId — افترض إنها لا تزال نشطة في IBKR.
 *     OrderTrackingService سيتعامل مع callbacks عند fill.
 *
 * ⚠️ لإنتاجية أعلى:
 *   - استدعِ reqOpenOrders() و reqPositions() من IBKR للمطابقة الفعلية.
 *   - حالياً نعتمد على DB كـ source of truth — كافي للـ paper trading.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryService {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;

    @PostConstruct
    @Transactional
    public void onStartup() {
        log.info("🔄 RecoveryService: scanning for stranded deals from previous run...");

        // 1) ENTRY_PENDING — مات النظام أثناء انتظار fill
        List<Deal> pending = dealRepository.findByStatusIn(List.of(DealStatus.ENTRY_PENDING));
        for (Deal d : pending) {
            log.error("⚠️ RECOVERY: dealId={} was ENTRY_PENDING at shutdown | orderId={} signalAt={}",
                    d.getId(), d.getIbkrEntryOrderId(), d.getSignalReceivedAt());

            // علّمها FAILED — تحتاج مراجعة بشرية:
            //  - هل الأمر فعلاً نُفّذ في IBKR؟ (فحص يدوي في TWS)
            //  - لو نُفّذ، ضع bracket يدوياً
            //  - لو لم يُنفّذ، ألغِها
            d.setStatus(DealStatus.FAILED);
            dealRepository.save(d);
            recordEvent(d, DealEventType.RECOVERY_START,
                    "ENTRY_PENDING at startup — needs manual review");
        }

        // 2) ENTERED بدون bracket — position بلا حماية
        List<Deal> entered = dealRepository.findByStatusIn(List.of(DealStatus.ENTERED));
        for (Deal d : entered) {
            boolean unprotected = (d.getIbkrTpOrderId() == null) || (d.getIbkrSlOrderId() == null);
            if (unprotected) {
                log.error("⚠️ RECOVERY: dealId={} ENTERED without bracket! tpId={} slId={}",
                        d.getId(), d.getIbkrTpOrderId(), d.getIbkrSlOrderId());
                recordEvent(d, DealEventType.EXECUTION_ERROR,
                        "Recovery: ENTERED without bracket — manual intervention required");
                // ⚠️ لا تضع bracket تلقائياً عند startup —
                // السعر/السوق ربما تغيّرا، يحتاج قرار بشري
            } else {
                log.info("✓ RECOVERY: dealId={} ENTERED with bracket tpId={} slId={}",
                        d.getId(), d.getIbkrTpOrderId(), d.getIbkrSlOrderId());
                recordEvent(d, DealEventType.RECOVERY_DONE, "ENTERED+bracket — assumed healthy");
            }
        }

        // (PREPARE حالة أُزيلت — الـ pipeline الجديد لا يحتوي رسائل تجهيز تُخزَّن
        //  كـ Deal؛ رسائل "خليك جاهز/مراقب" تُسقَط في MessageShapeGate.)

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
