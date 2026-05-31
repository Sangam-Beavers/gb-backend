package com.gb.community.domain.comment.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.community.domain.comment.dto.response.CommentListResponse;
import com.gb.community.domain.comment.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 커뮤니티 댓글 API — 댓글 목록 조회 (api-spec §7).
 *
 * <p>{@code GET /posts/{id}/comments}는 {@code PostController}/{@code LikeController}와 같은 베이스 경로
 * ({@code /api/v1/community/posts})에 공존한다. 리터럴 세그먼트(/comments)가 path-variable과 충돌하지 않아
 * 별도 컨트롤러로 둔다(도메인형 패키지 분리, CLAUDE.md §3).
 *
 * <p>본 PR 범위는 댓글 목록 조회만 — 작성/수정/삭제·대댓글·좋아요는 별도 이슈다.
 */
@Tag(name = "Community Comment", description = "커뮤니티 댓글 API")
@RestController
@RequestMapping("/api/v1/community/posts")
@RequiredArgsConstructor
// @Validated: @RequestParam/@PathVariable/@RequestHeader 메서드 파라미터의 Bean Validation을 활성화한다.
// 위반 시 ConstraintViolationException → GlobalExceptionHandler에서 COMMON4001(400)으로 변환.
@Validated
public class CommentController {

    // 응답별 ErrorResponse 예시 JSON. ErrorCode enum의 (code, message)와 1:1 일치하도록 손으로 박는다.
    private static final String EX_COMMON4001 =
            "{\"success\":false,\"code\":\"COMMON4001\",\"message\":\"요청 값이 올바르지 않습니다.\"}";
    private static final String EX_COMMON4011 =
            "{\"success\":false,\"code\":\"COMMON4011\",\"message\":\"인증 정보가 유효하지 않습니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";
    private static final String EX_COMMUNITY4001 =
            "{\"success\":false,\"code\":\"COMMUNITY4001\",\"message\":\"존재하지 않는 게시글입니다.\"}";

    private final CommentService commentService;

    /** 댓글 목록 조회. 🔒 JWT 필요(현재 인증 미구현 — 헤더 임시 식별). */
    @Operation(
            summary = "댓글 목록 조회",
            description = "게시글 public_id에 달린 댓글을 작성순(오래된 순)으로 페이지네이션해 반환한다. "
                    + "삭제된 댓글은 제외된다. 없거나 삭제된 게시글이면 404 COMMUNITY4001. "
                    + "이 API는 본인 식별을 쓰지 않지만 인증 API라 헤더는 받아둔다. "
                    + "대댓글은 별도 이슈로, 현재 parent_comment_public_id는 항상 null이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data에 CommentListResponse(comments + 페이지 메타)가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 잘못된 page/size 범위 위반 또는 헤더 누락/공백, public_id 형식 위반.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "COMMON4011 - 인증 정보가 유효하지 않습니다. (인증 구현 후 활성화)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4011", value = EX_COMMON4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "COMMUNITY4001 - 존재하지 않는 게시글입니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMUNITY4001", value = EX_COMMUNITY4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping("/{id}/comments")
    public ApiResponse<CommentListResponse> getComments(
            // TODO: 인증 구현 후 JWT(sub/claim)에서 userPublicId 추출로 교체.
            //       현재는 인증 미구현으로 헤더(X-User-Public-Id)로 임시 수신.
            //       이 API는 목록 조회라 본인 식별 값 자체는 사용하지 않는다.
            @RequestHeader("X-User-Public-Id") @NotBlank String userPublicId,
            @PathVariable("id") @NotBlank @Size(max = 36) String postPublicId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            // size 상한(100)은 명세에 없지만 과도한 조회를 막는 방어적 가드(Post/Like 목록과 동일).
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(commentService.getComments(postPublicId, page, size));
    }
}
