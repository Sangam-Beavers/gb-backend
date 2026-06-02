package com.gb.member.domain.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 소셜(Google) 가입 후 추가 정보 요청.
 *
 * <p>Google 로그인은 email·name만 주므로(토큰 claim), 우리 회원에 필수인 닉네임·국적·언어를 이 요청으로
 * 보완받는다. email·name은 요청이 아니라 토큰(JWT claim)에서 채운다(사용자가 임의로 바꾸지 못하게).
 *
 * <p>입력 형식은 @NotBlank로만 1차 검증하고, 닉네임 중복은 Service에서 확인한다(회원가입과 동일 정책).
 */
@Getter
@NoArgsConstructor
public class SocialProfileRequest {

    @Schema(description = "닉네임 (앱 전체에서 중복 불가)", example = "gildong")
    @NotBlank(message = "닉네임은 필수입니다")
    private String nickname;

    @Schema(description = "국적 (ISO 3166-1 alpha-2, 예: VN)", example = "VN")
    @NotBlank(message = "국적은 필수입니다")
    private String nationality;

    @Schema(description = "주 사용 언어 (BCP 47 소문자, 예: vi)", example = "vi")
    @NotBlank(message = "주 사용 언어는 필수입니다")
    private String language;
}
