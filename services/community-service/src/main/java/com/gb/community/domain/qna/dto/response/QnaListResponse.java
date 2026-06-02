package com.gb.community.domain.qna.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;

/**
 * 주요 QnA 목록 응답 (api-spec §8) — {@code posts} 배열 래퍼.
 *
 * <p>페이지네이션 메타(page/size/total_*)는 없다 — Top N 고정 목록이라 클라이언트가 추가 페이지를
 * 요청할 수 없다(필요해지면 명세 확장 + 필드 추가). 배열 키는 SSOT(§10·§1)에 따라 snake_case
 * 도메인 복수형 {@code posts}로 둔다(qna_list 같은 비표준 키 회피).
 */
@Getter
public class QnaListResponse {

    @Schema(description = "QnA 게시글 목록 (답변 수 내림차순)")
    private final List<QnaPostResponse> posts;

    private QnaListResponse(List<QnaPostResponse> posts) {
        this.posts = posts;
    }

    public static QnaListResponse of(List<QnaPostResponse> posts) {
        return new QnaListResponse(posts);
    }
}
