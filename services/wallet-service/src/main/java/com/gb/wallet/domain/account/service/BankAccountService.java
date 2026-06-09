package com.gb.wallet.domain.account.service;

import com.gb.wallet.domain.account.dto.request.ConfirmAccountRequest;
import com.gb.wallet.domain.account.dto.request.RegisterAccountRequest;
import com.gb.wallet.domain.account.dto.request.VerifyAccountRequest;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.ConfirmAccountResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;
import com.gb.wallet.domain.account.entity.Bank;

public interface BankAccountService {

    AccountListResponse getMyAccounts(String userPublicId);

    /**
     * 외부 Mock 은행에 1원 소액이체 인증을 요청한다(1단계).
     * 해당 계좌에 1원이 입금되며 적요에 4자리 인증번호가 기재된다.
     * account_token은 발급되지 않으며 {@link #confirmAccount}에서 코드 검증 후 발급된다.
     *
     * <p>사용자 단위 rate-limit 적용(WACC-02).
     */
    VerifyAccountResponse verifyAccount(VerifyAccountRequest request, String userPublicId);

    /**
     * 인증번호 4자리 검증(2단계). Mock 은행에 코드를 제출해 account_token을 발급받고
     * Redis 세션({@code verify-session:account:{userPublicId}:{bankCode}:{accountNumber}})에
     * 저장한다. 이후 {@code POST /accounts}(계좌 등록) 시 서버가 세션에서 직접 소비한다.
     *
     * <p>코드 불일치 → ACCOUNT4008. 세션 없음/만료 → ACCOUNT4009.
     */
    ConfirmAccountResponse confirmAccount(ConfirmAccountRequest request, String userPublicId);

    /**
     * 계좌 등록 최종 확정. user 단위 분산락으로 race를 막은 뒤 {@link #registerAccountLocked}에 위임.
     * 락 획득 실패 시 COMMON5031(503).
     */
    AccountResponse registerAccount(String userPublicId, RegisterAccountRequest request);

    /**
     * 계좌 등록 실제 처리(쓰기 트랜잭션). self-proxy 전용 — 직접 호출 금지.
     *
     * @param bank         은행(락 밖에서 확정)
     * @param holderName   은행 권위 예금주명(inquiry, 락 밖에서 확정 — WACC-05)
     * @param accountToken confirm 완료 후 Redis에서 소비한 token(락 밖에서 확정 — WACC-05/charge-3)
     */
    AccountResponse registerAccountLocked(String userPublicId, RegisterAccountRequest request,
                                          Bank bank, String holderName, String accountToken);

    AccountResponse changePrimary(String userPublicId, String accountPublicId);

    AccountResponse changePrimaryLocked(String userPublicId, String accountPublicId);

    void deleteAccount(String userPublicId, String accountPublicId);

    void deleteAccountLocked(String userPublicId, String accountPublicId);
}
