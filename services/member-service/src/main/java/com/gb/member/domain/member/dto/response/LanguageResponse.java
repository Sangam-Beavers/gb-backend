package com.gb.member.domain.member.dto.response;

import com.gb.member.domain.member.entity.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 회원의 주 사용 언어 응답. 조회(GET)와 변경(PATCH) 응답 모두에 쓴다.
 */
@Getter
public class LanguageResponse {

    @Schema(description = "주 사용 언어 (BCP 47)", example = "vi")
    private final String language;

    @Builder
    private LanguageResponse(String language) {
        this.language = language;
    }

    public static LanguageResponse from(Member member) {
        return LanguageResponse.builder()
                .language(member.getLanguage())
                .build();
    }
}