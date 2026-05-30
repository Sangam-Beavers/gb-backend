package com.gb.wallet.domain.account.repository;

import com.gb.wallet.domain.account.entity.Bank;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankRepository extends JpaRepository<Bank, Long> {

    /** 활성화된 국내 은행을 이름 가나다순으로 반환한다(지원 은행 목록 조회용). */
    List<Bank> findAllByIsDomesticTrueAndIsActiveTrueOrderByNameAsc();

    /** 은행 코드로 단건 조회(계좌 등록 시 bank_id 매핑용). */
    Optional<Bank> findByCode(String code);
}
