package com.mod98.alpaca.spx.bot;

import com.mod98.alpaca.spx.parsing.ImageAnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class BotStateService {

    private static final Logger log = LoggerFactory.getLogger(BotStateService.class);

    private BotState state = BotState.IDLE;
    private final TradeSession session = new TradeSession();

    public BotState getState() {
        return state;
    }

    public TradeSession getSession() {
        return session;
    }

    // This function is the only point that TelegramLoginService calls
    public void handleImage(ImageAnalysisResult result) {
        String role = result.getImageRole();
        log.info("🤖 handleImage | state={} | role={}", state, role);

        switch (role) {
            case "PREPARE" -> handlePrepare(result);
            case "ENTRY"   -> handleEntry(result);
            case "IGNORE"  -> log.info("⚪ IGNORE image — no action.");
            default        -> log.warn("❓ Unknown imageRole: {}", role);
        }
    }


    private void handlePrepare(ImageAnalysisResult result) {
        if (state == BotState.IN_TRADE) {
            log.warn("⚠️ PREPARE received but we are already IN_TRADE. Ignoring.");
            return;
        }

        state = BotState.PREPARE_WAITING;
        session.reset();
        session.setPrepareAt(Instant.now());
        session.setNotes(result.getNotes());

        log.warn("🟣 STATE → PREPARE_WAITING | contracts={} | notes={}",
                result.getContractCount(),
                result.getNotes()
        );
    }

    private void handleEntry(ImageAnalysisResult result) {
        Double entryPrice = result.getEntryPrice();

        if (state != BotState.PREPARE_WAITING) {
            log.warn("⚠️ ENTRY received but state={} (expected PREPARE_WAITING). Ignoring. entryPrice={}",
                    state, entryPrice);
            return;
        }

        if (entryPrice == null) {
            log.warn("⚠️ ENTRY received but entryPrice is null. Ignoring.");
            return;
        }

        state = BotState.IN_TRADE;
        session.setEntryAt(Instant.now());
        session.setEntryPrice(entryPrice);

        log.warn("🟢 ENTRY CONFIRMED! STATE → IN_TRADE | entryPrice={} | notes={}",
                entryPrice,
                result.getNotes()
        );

        // Here we will later put the logic behind the actual entry into the deal.

    }

    // We use it later when the deal closes (TP/SL or manual).
    public void closeTrade(String reason) {
        log.warn("🏁 Closing trade. Reason={} | entryPrice={} | lastUpdated={}",
                reason,
                session.getEntryPrice(),
                session.getLastUpdatedPrice()
        );
        state = BotState.IDLE;
        session.reset();
    }
}
