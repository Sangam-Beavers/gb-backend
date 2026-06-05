package com.gb.community.domain.post.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/community/posts} 요청 본문 (api-spec §2).
 *
 * <p>{@code category}는 String으로 받아 서비스에서 {@code PostCategory}로 파싱한다. 엔티티/enum에
 * 직접 바인딩하면 잘못된 값이 Jackson 역직렬화 단계에서 깨져 500으로 떨어지므로, String으로 받아
 * 서비스에서 검증해 COMMON4001(400)로 변환하기 위함이다.
 */
@Getter
@NoArgsConstructor
public class PostCreateRequest {

    @Schema(description = "카테고리", example = "JOB",
            allowableValues = {"LIFE_INFO", "JOB", "VISA", "COUNTRY", "RESIDENCE", "QUESTION", "FREE"})
    @NotBlank
    private String category;

    @Schema(description = "제목", example = "시급 9,000원 받고 일했는데 최저임금 미달인가요?", maxLength = 255)
    @NotBlank
    @Size(max = 255)
    private String title;

    // 상한 10,000자(api-spec §2) — 컬럼이 TEXT(65,535바이트)라 무제한 입력이 INSERT 단계 500으로
    // 떨어지는 것을 입력단 400(COMMON4001)으로 막는다(11D community-1). 10,000자는 한글(3바이트)
    // 기준 30KB로, 4바이트 문자(이모지)가 섞여도 컬럼 한계 안이다.
    @Schema(description = "본문 (1~10,000자)", example = "베트남에서 온 외국인입니다. 같은 경험 있는 분 계시면 알려주세요.",
            maxLength = 10000)
    @NotBlank
    @Size(max = 10000)
    private String content;
}