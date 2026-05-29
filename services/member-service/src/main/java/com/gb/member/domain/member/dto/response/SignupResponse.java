package com.gb.member.domain.member.dto.response;

import com.gb.member.domain.member.entity.Member;
import lombok.Builder;
import lombok.Getter;

@Getter
public class SignupResponse {

    private final Long memberId;
    private final String email;
    private final String nickname;

    @Builder
    private SignupResponse(Long memberId, String email, String nickname) {
        this.memberId = memberId;
        this.email = email;
        this.nickname = nickname;
    }

    public static SignupResponse from(Member member) {
        return SignupResponse.builder()
                .memberId(member.getId())
                .email(member.getEmail())
                .nickname(member.getNickname())
                .build();
    }
}
