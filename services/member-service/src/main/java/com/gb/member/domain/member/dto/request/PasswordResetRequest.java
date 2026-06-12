package com.gb.member.domain.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    // 복잡도 규칙은 SignupRequest.password와 동일(Cognito 풀 기본 정책 미러링 — 환경 간 비대칭 방지).
    @Schema(description = "새 비밀번호", example = "NewP@ssw0rd!")
    @NotBlank(message = "새 비밀번호는 필수입니다")
    @Size(min = 8, max = 256, message = "비밀번호는 8자 이상 256자 이내여야 합니다")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).*$",
            message = "비밀번호는 대문자·소문자·숫자·특수문자를 각각 1자 이상 포함해야 합니다")
    private String newPassword;
}
