// src/main/java/com/mod98/alpaca/spx/service/RecoveryService.java
package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RecoveryService {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;

    public RecoveryService(DealRepository dealRepository,
                           DealEventRepository eventRepository) {
        this.dealRepository = dealRepository;
        this.eventRepository = eventRepository;
    }

    @PostConstruct
    @Transactional
    public void onStartup() {
        List<Deal> openDeals = dealRepository.findByStatusIn(
                List.of(DealStatus.PREPARE, DealStatus.ENTERED));

        for (Deal d : openDeals) {
            DealEvent start = new DealEvent();
            start.setDeal(d);
            start.setEventType(DealEventType.RECOVERY_START);
            eventRepository.save(start);

            // Here you can re-subscribe to WebSocket for the symbol/node
            // It rebuilds TP/SL orders if they are missing – placeholder only.

            DealEvent done = new DealEvent();
            done.setDeal(d);
            done.setEventType(DealEventType.RECOVERY_DONE);
            eventRepository.save(done);
        }
    }
}
