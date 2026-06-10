package com.gb.appadmin.domain.feePolicy.repository;

import com.gb.appadmin.domain.feePolicy.entity.FeePolicyHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeePolicyHistoryRepository extends JpaRepository<FeePolicyHistory, Long> {
    List<FeePolicyHistory> findByFeePolicyIdOrderByChangedAtDesc(Long feePolicyId);
}
