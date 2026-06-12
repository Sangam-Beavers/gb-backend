package com.gb.member.domain.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 마이페이지 프로필 수정 요청. 닉네임·주 사용 언어·자기소개를 수정한다.
 *
 * <p>닉네임 중복(다른 회원이 사용 중)은 형식 문제가 아니라 Service에서 확인해 MEMBER4003으로 처리한다.
 * 프로필 사진은 별도 API, 국적/이메일은 이 화면에서 변경하지 않는다.
 */
@Getter
@NoArgsConstructor
public class ProfileUpdateRequest {

    // @Size 상한 = members 컬럼 길이(database.md §members SSOT) — SignupRequest와 동일.
    @Schema(description = "닉네임", example = "global_neighbor", maxLength = 50)
    @NotBlank(message = "닉네임은 필수입니다")
    @Size(max = 50, message = "닉네임은 50자 이내여야 합니다")
    private String nickname;

    @Schema(description = "주 사용 언어(BCP 47)", example = "ko", maxLength = 10)
    @NotBlank(message = "주 사용 언어는 필수입니다")
    @Size(max = 10, message = "언어 코드는 10자 이내여야 합니다")
    private String language;

    @Schema(description = "자기소개(한 줄 소개). 선택값", nullable = true, example = "안녕하세요.")
    @Size(max = 200, message = "자기소개는 200자 이내여야 합니다")
    private String bio;

    // 아바타 색조(hue) 회전 각도(0~359°). 선택값 — 미전송(null)이면 Service에서 기존 값을 유지한다.
    @Schema(description = "프로필 아바타 색조 회전 각도(0~359°). 선택값", nullable = true, example = "120")
    @Min(value = 0, message = "색조 값은 0 이상이어야 합니다")
    @Max(value = 359, message = "색조 값은 359 이하여야 합니다")
    private Integer avatarHue;
}
