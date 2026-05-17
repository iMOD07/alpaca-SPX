package com.mod98.alpaca.spx.service.processor;

import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.ibkr.IbkrConnectionManager;
import com.mod98.alpaca.spx.ibkr.IbkrContractService;
import com.mod98.alpaca.spx.ibkr.IbkrExecutionService;
import com.mod98.alpaca.spx.ibkr.IbkrOrderIdService;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import com.ib.client.Contract;
import com.mod98.alpaca.spx.domain.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the user's fixed rule: TP = entry + $1.00, SL = entry - $1.00
 * (PDF note: entry 3.30 → sell-profit 4.30, loss 2.30), with SL floored
 * at the minimum option price.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntryHandlerTpSlTest {

    @Mock DealRepository dealRepo;
    @Mock DealEventRepository eventRepo;
    @Mock IbkrConnectionManager conn;
    @Mock IbkrExecutionService execution;
    @Mock IbkrOrderIdService orderIds;
    @Mock IbkrContractService contractService;

    private EntryHandler handler;
    private final TradingProperties trading = new TradingProperties(); // real defaults: ±1.00

    @BeforeEach
    void setUp() {
        handler = new EntryHandler(dealRepo, eventRepo, conn, execution,
                orderIds, contractService, trading);

        when(conn.isConnected()).thenReturn(true);
        when(dealRepo.findByTelegramMessageId(any())).thenReturn(Optional.empty());
        when(dealRepo.save(any(Deal.class))).thenAnswer(inv -> {
            Deal d = inv.getArgument(0);
            if (d.getId() == null) d.setId(7L);
            return d;
        });
        when(dealRepo.findById(any())).thenAnswer(inv -> {
            Deal d = new Deal();
            d.setId(7L);
            return Optional.of(d);
        });
        when(orderIds.nextOrderId()).thenReturn(100);

        Contract contract = org.mockito.Mockito.mock(Contract.class);
        when(contract.conid()).thenReturn(424242);
        when(contractService.resolveSpxwOptionContract(any(LocalDate.class), anyDouble(), any()))
                .thenReturn(contract);

        when(execution.placeEntryWithReservedId(any(), any(), anyInt())).thenReturn(
                IbkrExecutionService.EntryDecisionResult.placed(
                        100, 424242,
                        new BigDecimal("3.30"), new BigDecimal("3.45"),
                        new BigDecimal("3.40"), new BigDecimal("3.35")));
    }

    private ParsedSignal entry(BigDecimal price) {
        return ParsedSignal.builder()
                .signalType(SignalType.ENTRY)
                .telegramMessageId(5001L)
                .symbol("SPXW")
                .optionType("CALL")
                .strike(new BigDecimal("6845"))
                .expiryDate(LocalDate.now().plusDays(30))
                .entryPrice(price)
                .rawText("دخول CALL")
                .build();
    }

    @Test
    @DisplayName("entry 3.30 → TP 4.30, SL 2.30 (PDF's stated rule)")
    void tpSl_fixedOneDollarOffset() {
        handler.handle(entry(new BigDecimal("3.30")));

        ArgumentCaptor<Deal> captor = ArgumentCaptor.forClass(Deal.class);
        verify(dealRepo, atLeastOnce()).save(captor.capture());
        Deal saved = captor.getAllValues().get(0);

        assertThat(saved.getEntrySignalPrice()).isEqualByComparingTo("3.30");
        assertThat(saved.getTpPrice()).isEqualByComparingTo("4.30");
        assertThat(saved.getSlPrice()).isEqualByComparingTo("2.30");
    }

    @Test
    @DisplayName("low entry 0.50 → SL clamped to min option price 0.05, TP still +1.00")
    void sl_clampedAtMinOptionPrice() {
        handler.handle(entry(new BigDecimal("0.50")));

        ArgumentCaptor<Deal> captor = ArgumentCaptor.forClass(Deal.class);
        verify(dealRepo, atLeastOnce()).save(captor.capture());
        Deal saved = captor.getAllValues().get(0);

        assertThat(saved.getTpPrice()).isEqualByComparingTo("1.50");
        assertThat(saved.getSlPrice()).isEqualByComparingTo("0.05"); // not -0.50
    }

    @Test
    @DisplayName("kill switch off → no order placed")
    void killSwitch_blocksEntry() {
        trading.setTradingEnabled(false);
        handler.handle(entry(new BigDecimal("3.30")));
        verify(execution, org.mockito.Mockito.never())
                .placeEntryWithReservedId(any(), any(), anyInt());
    }
}
