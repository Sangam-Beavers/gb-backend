package com.gb.community.domain.post.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/community/posts} 요청 본문 (api-spec §2).
 *
 * <p>요청 JSON은 snake_case({@code image_urls})로 오고, 전역 Jackson 설정
 * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE})이 camelCase 필드로 매핑한다 —
 * 그래서 {@code @JsonProperty}는 붙이지 않는다.
 *
 * <p>{@code category}는 String으로 받아 서비스에서 {@code PostCategory}로 파싱한다. 엔티티/enum에
 * 직접 바인딩하면 잘못된 값이 Jackson 역직렬화 단계에서 깨져 500으로 떨어지므로, String으로 받아
 * 서비스에서 검증해 COMMON4001(400)로 변환하기 위함이다.
 */
@Getter
@NoArgsConstructor
public class PostCreateRequest {

    @Schema(description = "카테고리", example = "JOB",
            allowableValues = {"LIFE_INFO", "JOB", "VISA", "COUNTRY", "RESIDENCE", "QUESTION"})
    @NotBlank
    private String category;

    @Schema(description = "제목", example = "시급 9,000원 받고 일했는데 최저임금 미달인가요?", maxLength = 255)
    @NotBlank
    @Size(max = 255)
    private String title;

    @Schema(description = "본문", example = "베트남에서 온 외국인입니다. 같은 경험 있는 분 계시면 알려주세요.")
    @NotBlank
    private String content;

    /**
     * 사전 업로드된 이미지 URL 목록(선택). 현재 posts 스키마에 이미지 컬럼이 없고 post_images 테이블이
     * 미정이라 <b>받기만 하고 영속화하지 않는다</b>. 응답의 image_urls는 항상 빈 배열로 나간다.
     *
     * <p>TODO: post_images 테이블 확정 시 저장 로직 추가.
     */
    @Schema(description = "사전 업로드된 이미지 URL 목록(현재 미저장)",
            example = "[\"https://cdn.example.com/a.jpg\"]")
    private List<String> imageUrls;
}