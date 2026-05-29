package com.gb.member.domain.member.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * POST /api/v1/auth/login 응답 data.
 *
 * <p>JSON 필드명은 전역 Jackson 설정(SNAKE_CASE)으로 변환된다.
 * 비밀번호 등 민감 정보는 절대 포함하지 않는다.
 */
@Getter
public class LoginResponse {

    @Schema(description = "JWT 액세스 토큰", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0...")
    private final String accessToken;

    @Schema(description = "토큰 타입", example = "Bearer")
    private final String tokenType;

    @Schema(description = "액세스 토큰 만료까지 남은 시간(초)", example = "3600")
    private final long expiresIn;

    @Builder
    private LoginResponse(String accessToken, String tokenType, long expiresIn) {
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.expiresIn = expiresIn;
    }

    public static LoginResponse of(String accessToken, long expiresInSeconds) {
        return LoginResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(expiresInSeconds)
                .build();
    }
}
