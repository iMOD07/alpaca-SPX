package com.mod98.alpaca.spx.repo;

import com.mod98.alpaca.spx.domain.DealEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealEventRepository extends JpaRepository<DealEvent, Long> {
}