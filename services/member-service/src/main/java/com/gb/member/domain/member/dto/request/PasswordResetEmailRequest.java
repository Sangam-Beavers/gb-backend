package com.gb.member.domain.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 비밀번호 재설정 링크 발송 요청. 가입한 이메일을 받아 재설정 링크를 메일로 보낸다.
 */
@Getter
@NoArgsConstructor
public class PasswordResetEmailRequest {

    @Schema(description = "재설정 링크를 받을 가입 이메일", example = "user@example.com")
    @NotBlank(message = "이메일은 필수입니다")
    @Email(message = "이메일 형식이 올바르지 않습니다")
    private String email;
}
