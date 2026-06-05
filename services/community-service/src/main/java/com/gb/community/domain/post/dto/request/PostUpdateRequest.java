package com.gb.community.domain.post.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code PATCH /api/v1/community/posts/{id}} 요청 본문 (api-spec §3, 부분 수정).
 *
 * <p>모든 필드가 선택이다. 보낸 필드만 변경하고, 보내지 않은(또는 빈) 필드는 기존값을 유지한다
 * — null/blank 정규화와 category 파싱·검증은 서비스가 처리한다(잘못된 category → COMMON4001).
 */
@Getter
@NoArgsConstructor
public class PostUpdateRequest {

    @Schema(description = "변경할 카테고리(선택)", example = "QUESTION",
            allowableValues = {"LIFE_INFO", "JOB", "VISA", "COUNTRY", "RESIDENCE", "QUESTION", "FREE"})
    private String category;

    @Schema(description = "변경할 제목(선택)", example = "제목을 수정합니다", maxLength = 255)
    @Size(max = 255)
    private String title;

    // 상한 10,000자 — 작성(PostCreateRequest)과 동일 정책(api-spec §2, 11D community-1).
    @Schema(description = "변경할 본문(선택, 1~10,000자)", example = "본문을 수정합니다", maxLength = 10000)
    @Size(max = 10000)
    private String content;
}