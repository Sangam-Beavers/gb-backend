package com.gb.wallet.domain.transaction.service;

import com.gb.wallet.domain.transaction.dto.request.TransferFeeRequest;
import com.gb.wallet.domain.transaction.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferFeeResponse;
import com.gb.wallet.domain.transaction.dto.response.ValidateMemberResponse;

public interface TransferService {

    /**
     * "최근 송금 앱 사용자" 조회. 내가 송신자였던 INTERNAL_TRANSFER(COMPLETED) 중
     * 수신자별 가장 최근 송금 1건씩, 최근순으로 최대 10명 반환한다.
     */
    RecentRecipientsResponse getRecentInternalRecipients(String userPublicId);

    /**
     * 이메일로 앱 사용자 존재 여부 검증. 없으면 MEMBER_NOT_FOUND(MEMBER4001).
     * wallet DB는 조회하지 않고 MemberClient만 사용한다.
     */
    ValidateMemberResponse validateMember(String email);

    /**
     * 지원 통화 목록 조회(KRW/USD/PHP/VND). CurrencyType enum이 SSOT라 DB/외부 호출 없이
     * enum 순회로 응답을 만든다.
     */
    SupportedCurrenciesResponse getSupportedCurrencies();

    /**
     * "최근 송금 계좌" 조회. 내가 송신자였던 REMITTANCE(COMPLETED) 중 bank_account별로 가장 최근
     * 1건씩, 최근순으로 size건 반환. size는 1~50, 기본 10. wallet 도메인 내부 DB만 사용한다.
     */
    RecentAccountsResponse getRecentRemittanceAccounts(String userPublicId, int size);

    /**
     * 예금주 실명 조회. {@code BankClient.inquiry}로 외부 Mock 은행만 호출하며 DB는 보지 않는다.
     * 외부 에러는 BankErrorMapper가 BusinessException으로 변환해 던지므로 Service는 그대로 전파한다.
     */
    AccountHolderResponse getAccountHolder(String bankCode, String accountNumber);

    /**
     * 송금 수수료 계산. 순수 계산 로직 — DB·외부 호출 없음.
     * 정책은 docs/remittance/api-spec.md §4 SSOT: INTERNAL_TRANSFER=0, REMITTANCE=amount×0.5%(HALF_UP 4자리).
     * 미지원 통화는 {@code TransferErrorCode.UNSUPPORTED_CURRENCY}(TRANSFER4002).
     */
    TransferFeeResponse getTransferFee(TransferFeeRequest request);
}
