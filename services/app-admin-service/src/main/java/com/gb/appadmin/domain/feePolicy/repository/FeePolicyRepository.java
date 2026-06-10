package com.gb.appadmin.domain.feePolicy.repository;

import com.gb.appadmin.domain.feePolicy.entity.FeePolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeePolicyRepository extends JpaRepository<FeePolicy, Long> {
    Optional<FeePolicy> findByPublicId(String publicId);
    Optional<FeePolicy> findByServiceTypeAndCurrency(String serviceType, String currency);
    List<FeePolicy> findAllByOrderByServiceTypeAsc();
}
