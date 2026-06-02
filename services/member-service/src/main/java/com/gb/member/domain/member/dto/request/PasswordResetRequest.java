package com.gb.member.domain.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 비밀번호 재설정 실행 요청. 메일 링크의 토큰과 새 비밀번호를 받아 비밀번호를 변경한다.
 */
@Getter
@NoArgsConstructor
public class PasswordResetRequest {

    @Schema(description = "재설정 링크에 포함된 토큰", example = "9f8e7d6c-1234-5678-abcd-ef0123456789")
    @NotBlank(message = "토큰은 필수입니다")
    private String token;

    @Schema(description = "새 비밀번호", example = "NewP@ssw0rd!")
    @NotBlank(message = "새 비밀번호는 필수입니다")
    private String newPassword;
}
