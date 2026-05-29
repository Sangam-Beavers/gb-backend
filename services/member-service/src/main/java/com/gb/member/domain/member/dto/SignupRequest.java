package com.gb.member.domain.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SignupRequest {

    @NotBlank(message = "이메일은 필수입니다")
    @Email(message = "이메일 형식이 올바르지 않습니다")
    private String email;

    @NotBlank(message = "비밀번호는 필수입니다")
    private String password;

    @NotBlank(message = "이름은 필수입니다")
    private String name;

    @NotBlank(message = "닉네임은 필수입니다")
    private String nickname;

    @NotBlank(message = "국적은 필수입니다")
    private String nationality;

    @NotBlank(message = "주 사용 언어는 필수입니다")
    private String language;
}