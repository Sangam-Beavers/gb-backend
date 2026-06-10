package com.gb.appadmin.domain.exchangeRatePolicy.repository;

import com.gb.appadmin.domain.exchangeRatePolicy.entity.ExchangeRatePolicyHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRatePolicyHistoryRepository extends JpaRepository<ExchangeRatePolicyHistory, Long> {
    List<ExchangeRatePolicyHistory> findByExchangeRatePolicyIdOrderByChangedAtDesc(Long policyId);
}
