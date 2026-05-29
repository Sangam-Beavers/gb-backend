package com.gb.wallet.domain.transaction.service;

import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
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
}
