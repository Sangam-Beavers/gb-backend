package com.gb.wallet.domain.wallet.repository;

import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.entity.WalletBalance;
import com.gb.wallet.global.common.enums.CurrencyType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletBalanceRepository extends JpaRepository<WalletBalance, Long> {

    /** 특정 지갑의 통화별 잔액 목록. 단방향 매핑이므로 wallet 엔티티로 조회한다(두 번 조회 전략). */
    List<WalletBalance> findByWallet(Wallet wallet);

    /**
     * 잔액 갱신 직전, 동일 (wallet, currency) 행을 비관적 쓰기 잠금(SELECT … FOR UPDATE)으로 조회한다.
     * 같은 사용자가 동시에 충전·송금을 일으켜도 잔액 행을 한 번에 한 트랜잭션만 잡도록 직렬화해
     * lost update(읽고-쓰는 사이 다른 트랜잭션이 끼어드는 갱신 분실)를 막는다.
     *
     * <p>{@code (wallet_id, currency_code)} 복합 UNIQUE라 최대 1건이다. 첫 충전 등 행이 아직 없으면
     * {@code empty}이며, 이때는 호출 측에서 0 잔액 행을 새로 만든다(ChargeServiceImpl §5-5).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b FROM WalletBalance b
            WHERE b.wallet = :wallet AND b.currencyCode = :currencyCode
            """)
    Optional<WalletBalance> findForUpdateByWalletAndCurrency(
            @Param("wallet") Wallet wallet,
            @Param("currencyCode") CurrencyType currencyCode);
}
