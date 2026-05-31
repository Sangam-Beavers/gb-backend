package com.gb.member.domain.member.dto.response;

import com.gb.member.domain.member.entity.Member;
import lombok.Builder;
import lombok.Getter;

@Getter
public class SignupResponse {

    // 대외 식별자(UUID). 내부 id(BIGINT)는 응답에 노출하지 않는다.
    private final String publicId;
    private final String email;
    private final String nickname;

    @Builder
    private SignupResponse(String publicId, String email, String nickname) {
        this.publicId = publicId;
        this.email = email;
        this.nickname = nickname;
    }

    public static SignupResponse from(Member member) {
        return SignupResponse.builder()
                .publicId(member.getPublicId())
                .email(member.getEmail())
                .nickname(member.getNickname())
                .build();
    }
}
