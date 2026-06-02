package com.gb.member.domain.member.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주 사용 언어 변경 요청.
 *
 * <p>지원 언어 화이트리스트/enum이 명세에 정의돼 있지 않으므로 자유 문자열(BCP 47)로 받는다.
 * @Pattern/enum 검증은 추가하지 않는다(CLAUDE §6 — 추측 금지). 필수 여부만 @NotBlank로 검증한다.
 */
@Getter
@NoArgsConstructor
public class LanguageUpdateRequest {

    @NotBlank(message = "언어는 필수입니다")
    @Schema(description = "변경할 주 사용 언어 (BCP 47)", example = "ko")
    private String language;
}