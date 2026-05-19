package com.mod98.alpaca.spx.repo;

import com.mod98.alpaca.spx.domain.RiskState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskStateRepository extends JpaRepository<RiskState, Integer> {
    // Singleton id=1 — use findById(1)
}
