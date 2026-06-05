package com.gb.member.domain.member.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

// @Size 상한 = members 컬럼 길이(database.md §members SSOT, 11D member-core-1) — 과길이 입력이
//   INSERT 단계 500(DataIntegrityViolation)으로 떨어지지 않고 COMMON4001(400)로 거절되게 한다.
@Getter
@NoArgsConstructor
public class SignupRequest {

    @NotBlank(message = "이메일은 필수입니다")
    @Email(message = "이메일 형식이 올바르지 않습니다")
    @Size(max = 255, message = "이메일은 255자 이내여야 합니다")
    private String email;

    @NotBlank(message = "비밀번호는 필수입니다")
    private String password;

    @NotBlank(message = "이름은 필수입니다")
    @Size(max = 100, message = "이름은 100자 이내여야 합니다")
    private String name;

    @NotBlank(message = "닉네임은 필수입니다")
    @Size(max = 50, message = "닉네임은 50자 이내여야 합니다")
    private String nickname;

    @NotBlank(message = "국적은 필수입니다")
    @Size(max = 10, message = "국적 코드는 10자 이내여야 합니다")
    private String nationality;

    @NotBlank(message = "주 사용 언어는 필수입니다")
    @Size(max = 10, message = "언어 코드는 10자 이내여야 합니다")
    private String language;

    // 약관 동의. 명세 auth §2는 terms_agreed/privacy_agreed를 필수로 규정하나, 프론트가 아직 전송하지 않아
    //   임시로 @NotNull을 제거해 "미전송(null) 허용"한다 — 미전송 시 Service가 동의로 처리한다(임의 동의).
    //   @AssertTrue는 유지해 "명시적 false(동의 거부)"만 400으로 막는다(거짓 동의 증적 방지). null은 통과한다.
    // TODO(약관): 프론트가 동의 값을 전송하면 @NotNull을 복구하고 Service의 null 기본처리를 제거한다.
    @AssertTrue(message = "이용약관에 동의해야 가입할 수 있습니다")
    private Boolean termsAgreed;

    @AssertTrue(message = "개인정보 처리방침에 동의해야 가입할 수 있습니다")
    private Boolean privacyAgreed;
}
