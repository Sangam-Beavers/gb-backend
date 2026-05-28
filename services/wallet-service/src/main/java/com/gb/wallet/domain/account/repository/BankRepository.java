package com.gb.wallet.domain.account.repository;

import com.gb.wallet.domain.account.entity.Bank;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankRepository extends JpaRepository<Bank, Long> {

    /** 활성화된 국내 은행을 이름 가나다순으로 반환한다(지원 은행 목록 조회용). */
    List<Bank> findAllByIsDomesticTrueAndIsActiveTrueOrderByNameAsc();
}
