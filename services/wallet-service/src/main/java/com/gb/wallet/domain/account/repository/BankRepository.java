package com.gb.wallet.domain.account.repository;

import com.gb.wallet.domain.account.entity.Bank;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankRepository extends JpaRepository<Bank, Long> {

    /**
     * 활성화된 모든 파트너 은행을 국가코드 → 이름 순으로 반환한다(계좌 등록용 드롭다운).
     *
     * <p>GlobalBridge는 한국 거주 외국인 근로자(KR 계좌 충전)와 해외 수취인(VN/PH/US 계좌 수령)
     * 모두 동일한 계좌 추가 플로우를 사용한다. 따라서 전체 파트너 은행을 노출한다.
     */
    List<Bank> findAllByIsActiveTrueOrderByCountryAscNameAsc();

    /** 은행 코드로 단건 조회(계좌 등록 시 bank_id 매핑용). */
    Optional<Bank> findByCode(String code);
}
