package com.gb.wallet.domain.account.repository;

import com.gb.wallet.domain.account.entity.BankAccount;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    /**
     * 충전 대상 계좌를 조회한다 — public_id + 본인 소유(user_public_id) + 활성(is_active) 세 조건을 모두
     * 만족할 때만 반환한다. 셋 중 하나라도 어긋나면(미존재/타인 계좌/비활성) {@code empty}이며, 호출 측은
     * 어떤 사유든 구분 없이 ACCOUNT4001로 변환한다(타인 계좌 존재 여부 등 정보 누설 방지).
     *
     * <p>{@code @EntityGraph(bank)}는 같은 도메인의 다른 계좌 finder들과 동일한 컨벤션을 따른다 —
     * bank 참조가 LAZY라 호출 측에서 접근 시 N+1이 나지 않도록 즉시 페치한다.
     */
    @EntityGraph(attributePaths = "bank")
    Optional<BankAccount> findByPublicIdAndUserPublicIdAndIsActiveTrue(String publicId, String userPublicId);

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

    /**
     * 같은 회원이 동일 은행+계좌번호로 이미 활성 등록한 계좌가 있는지 확인한다(중복 등록 검사 → ACCOUNT4004).
     * 비활성(soft-delete) 레코드는 제외 — 같은 계좌 재등록 허용.
     */
    boolean existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
            String userPublicId, String bankCode, String accountNumber);

    /**
     * 회원의 활성 계좌 개수. 첫 계좌면 자동 {@code isPrimary=true}로 등록하기 위해 사용한다.
     */
    long countByUserPublicIdAndIsActiveTrue(String userPublicId);

    /**
     * 회원의 현재 주 계좌(활성)를 조회한다. 주 계좌 변경(PATCH /accounts/{id}/primary) 시 기존 주 계좌를
     * 해제하기 위해 사용한다. "사용자당 주 계좌 1개" 불변식상 최대 1건이라 {@code Optional}로 받는다.
     */
    Optional<BankAccount> findByUserPublicIdAndIsPrimaryTrueAndIsActiveTrue(String userPublicId);

    /**
     * 삭제 대상({@code excludedId})을 제외한 남은 활성 계좌 중 가장 최근 등록 1건을 조회한다.
     * 주 계좌 삭제(DELETE /accounts/{id}) 시 남은 계좌 1건을 자동으로 주 계좌 승격하기 위한 후보이며,
     * 남은 계좌가 없으면(마지막 계좌 삭제) {@code empty}다.
     */
    Optional<BankAccount> findFirstByUserPublicIdAndIsActiveTrueAndIdNotOrderByCreatedAtDesc(
            String userPublicId, Long excludedId);
}
