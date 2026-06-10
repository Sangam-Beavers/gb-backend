package com.gb.appadmin.domain.exchangeRatePolicy.repository;

import com.gb.appadmin.domain.exchangeRatePolicy.entity.ExchangeRatePolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRatePolicyRepository extends JpaRepository<ExchangeRatePolicy, Long> {
    Optional<ExchangeRatePolicy> findByPublicId(String publicId);
    Optional<ExchangeRatePolicy> findByCurrencyCode(String currencyCode);
    List<ExchangeRatePolicy> findAllByOrderByCurrencyCodeAsc();
}
