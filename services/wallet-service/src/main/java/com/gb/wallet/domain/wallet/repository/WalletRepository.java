package com.gb.wallet.domain.wallet.repository;

import com.gb.wallet.domain.wallet.entity.Wallet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /** 회원의 지갑 조회. 회원은 member-service의 user_public_id로 식별한다(사용자당 지갑 1개). */
    Optional<Wallet> findByUserPublicId(String userPublicId);

    /** 지갑 자체의 외부 식별자(public_id)로 조회. */
    Optional<Wallet> findByPublicId(String publicId);
}
