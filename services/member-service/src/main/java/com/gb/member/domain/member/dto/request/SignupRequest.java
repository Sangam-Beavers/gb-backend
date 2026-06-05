package com.gb.member.domain.member.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    // 약관 동의(필수). 명세 auth/api-spec §2가 terms_agreed/privacy_agreed를 boolean 필수로 규정한다.
    // Boolean(래퍼) + @NotNull로 "필드 누락"(null)을 막고, @AssertTrue로 "false 동의 거부"를 막는다
    //  (@AssertTrue는 null을 통과시키므로 @NotNull과 함께 둬야 누락도 잡힌다). 둘 다 위반은 COMMON4001(400).
    @NotNull(message = "이용약관 동의 여부는 필수입니다")
    @AssertTrue(message = "이용약관에 동의해야 가입할 수 있습니다")
    private Boolean termsAgreed;

    @NotNull(message = "개인정보 처리방침 동의 여부는 필수입니다")
    @AssertTrue(message = "개인정보 처리방침에 동의해야 가입할 수 있습니다")
    private Boolean privacyAgreed;
}
