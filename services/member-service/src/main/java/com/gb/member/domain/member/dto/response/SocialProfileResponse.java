package com.gb.member.domain.member.dto.response;

import com.gb.member.domain.member.entity.Member;
import lombok.Builder;
import lombok.Getter;

/**
 * 소셜 가입 후 추가 정보 보완 결과. 생성된 회원의 대외 식별자/이메일/닉네임을 반환한다.
 * (회원가입 응답 {@code SignupResponse}와 동일 형태 — 소셜 회원도 같은 스키마로 만들어진다.)
 */
@Getter
public class SocialProfileResponse {

    // 대외 식별자(UUID). 내부 id(BIGINT)는 응답에 노출하지 않는다.
    private final String publicId;
    private final String email;
    private final String nickname;

    @Builder
    private SocialProfileResponse(String publicId, String email, String nickname) {
        this.publicId = publicId;
        this.email = email;
        this.nickname = nickname;
    }

    public static SocialProfileResponse from(Member member) {
        return SocialProfileResponse.builder()
                .publicId(member.getPublicId())
                .email(member.getEmail())
                .nickname(member.getNickname())
                .build();
    }
}
