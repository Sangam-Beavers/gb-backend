package com.gb.member.domain.member.dto;

import com.gb.member.domain.member.entity.Member;
import lombok.Getter;

@Getter
public class SignupResponse {

    private final Long memberId;
    private final String email;
    private final String nickname;

    public SignupResponse(Member member) {
        this.memberId = member.getId();
        this.email = member.getEmail();
        this.nickname = member.getNickname();
    }
}