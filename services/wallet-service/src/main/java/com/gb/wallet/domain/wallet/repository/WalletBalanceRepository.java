package com.gb.wallet.domain.wallet.repository;

import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletBalanceRepository extends JpaRepository<WalletBalance, Long> {

    /** 특정 지갑의 통화별 잔액 목록. 단방향 매핑이므로 wallet 엔티티로 조회한다(두 번 조회 전략). */
    List<WalletBalance> findByWallet(Wallet wallet);
}
