package com.gb.member.domain.member.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.member.domain.member.dto.response.CreditResponse;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.member.service.MemberCreditService;
import com.gb.member.global.exception.code.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberCreditServiceImpl implements MemberCreditService {

    private final MemberRepository memberRepository;

    @Override
    @Transactional(readOnly = true)
    public CreditResponse getCredit(String userPublicId) {
        Member member = getActiveMemberOrThrow(userPublicId);
        return CreditResponse.from(member);
    }

    /**
     * 크레딧을 원자적으로 1 차감한다.
     *
     * <p>{@code decrementCreditIfPositive}는 UPDATE WHERE credit > 0 이므로 DB 레벨에서
     * race condition 없이 차감된다. 영향 행 수가 0이면 크레딧 부족 또는 회원 없음 — 순서대로 구분한다:
     * <ol>
     *   <li>회원 자체가 없으면 MEMBER4001</li>
     *   <li>회원은 있지만 크레딧이 0이면 MEMBER4007</li>
     * </ol>
     * {@code clearAutomatically = true}로 인해 이후 재조회는 UPDATE된 최신 값을 읽는다.
     */
    @Override
    @Transactional
    public CreditResponse useCredit(String userPublicId) {
        int updated = memberRepository.decrementCreditIfPositive(userPublicId);
        if (updated == 0) {
            // 크레딧 차감 실패 — 회원 없음 vs 크레딧 부족 구분
            Member member = memberRepository.findByPublicIdAndDeletedAtIsNull(userPublicId)
                    .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
            // 회원은 존재하지만 크레딧이 0
            throw new BusinessException(MemberErrorCode.INSUFFICIENT_CREDIT);
        }
        // 차감 성공 — 최신 값으로 재조회(clearAutomatically로 캐시 무효화됨)
        Member member = getActiveMemberOrThrow(userPublicId);
        return CreditResponse.from(member);
    }

    private Member getActiveMemberOrThrow(String userPublicId) {
        return memberRepository.findByPublicIdAndDeletedAtIsNull(userPublicId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
