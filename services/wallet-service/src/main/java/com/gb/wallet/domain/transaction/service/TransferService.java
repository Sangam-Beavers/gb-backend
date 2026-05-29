package com.gb.wallet.domain.transaction.service;

import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
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
}
