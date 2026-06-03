package com.gb.member.domain.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "닉네임", example = "global_neighbor")
    @NotBlank(message = "닉네임은 필수입니다")
    private String nickname;

    @Schema(description = "주 사용 언어(BCP 47)", example = "ko")
    @NotBlank(message = "주 사용 언어는 필수입니다")
    private String language;

    @Schema(description = "자기소개(한 줄 소개). 선택값", nullable = true, example = "안녕하세요.")
    @Size(max = 200, message = "자기소개는 200자 이내여야 합니다")
    private String bio;
}
