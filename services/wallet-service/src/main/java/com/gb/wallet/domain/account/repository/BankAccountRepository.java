package com.gb.wallet.domain.account.repository;

import com.gb.wallet.domain.account.entity.BankAccount;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    /**
     * 요청 회원의 활성 계좌 목록을 주 계좌 우선, 최신 등록순으로 반환한다.
     * 응답에서 bank.code/name을 함께 노출하므로 {@code @EntityGraph}로 즉시 페치해 N+1을 방지한다.
     */
    @EntityGraph(attributePaths = "bank")
    List<BankAccount> findAllByUserPublicIdAndIsActiveTrueOrderByIsPrimaryDescCreatedAtDesc(String userPublicId);

    /**
     * id IN 일괄 조회. JpaRepository 내장 {@code findAllById}는 {@code @EntityGraph}가 안 붙어
     * bank 참조가 LAZY로 남아 호출 측에서 N+1이 난다. 응답에서 bank.code/name을 같이 쓰는
     * "최근 송금 계좌" 조회용으로 별도 메서드를 둔다.
     */
    @EntityGraph(attributePaths = "bank")
    List<BankAccount> findAllByIdIn(Collection<Long> ids);
}
