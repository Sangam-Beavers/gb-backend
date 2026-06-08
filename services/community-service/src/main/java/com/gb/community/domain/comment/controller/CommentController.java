package com.gb.community.domain.comment.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.common.response.SuccessStatus;
import com.gb.community.domain.comment.dto.request.CreateCommentRequest;
import com.gb.community.domain.comment.dto.response.CommentListResponse;
import com.gb.community.domain.comment.dto.response.CommentResponse;
import com.gb.community.domain.comment.dto.response.CommentTranslationResponse;
import com.gb.community.domain.comment.service.CommentService;
import com.gb.community.domain.comment.service.CommentTranslationService;
import com.gb.community.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 커뮤니티 댓글 API — 목록 조회 / 작성 / 삭제 (api-spec §6·§7·§7-2).
 *
 * <p>{@code /posts/{id}/comments}는 {@code PostController}/{@code LikeController}와 같은 베이스 경로
 * ({@code /api/v1/community/posts})에 공존한다. 리터럴 세그먼트(/comments)가 path-variable과 충돌하지 않아
 * 별도 컨트롤러로 둔다(도메인형 패키지 분리, CLAUDE.md §3).
 *
 * <p>현재 댓글 목록 조회·작성·삭제를 제공한다. 대댓글({@code parent_comment_public_id} 항상 null)·수정은 별도 이슈다.
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
    private static final String EX_AUTH4011 =
            "{\"success\":false,\"code\":\"AUTH4011\",\"message\":\"인증이 필요합니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";
    private static final String EX_COMMUNITY4001 =
            "{\"success\":false,\"code\":\"COMMUNITY4001\",\"message\":\"존재하지 않는 게시글입니다.\"}";
    private static final String EX_COMMUNITY4002 =
            "{\"success\":false,\"code\":\"COMMUNITY4002\",\"message\":\"존재하지 않는 댓글입니다.\"}";
    private static final String EX_COMMON4031 =
            "{\"success\":false,\"code\":\"COMMON4031\",\"message\":\"접근 권한이 없습니다.\"}";
    private static final String EX_COMMUNITY4003 =
            "{\"success\":false,\"code\":\"COMMUNITY4003\",\"message\":\"지원하지 않는 언어입니다.\"}";
    private static final String EX_COMMUNITY4004 =
            "{\"success\":false,\"code\":\"COMMUNITY4004\",\"message\":\"본문이 너무 깁니다.\"}";

    private final CommentService commentService;
    private final CommentTranslationService commentTranslationService;

    /** 댓글 목록 조회. 🔒 JWT 필요(본인 식별은 토큰 public_id claim에서 추출). */
    @Operation(
            summary = "댓글 목록 조회",
            description = "게시글 public_id에 달린 댓글을 최신순(최근 작성 순)으로 페이지네이션해 반환한다. "
                    + "삭제된 댓글은 제외된다. 없거나 삭제된 게시글이면 404 COMMUNITY4001. "
                    + "각 항목의 is_author는 요청자(JWT public_id)와 작성자 일치 여부 — "
                    + "프론트의 수정·삭제 버튼 노출 판단용. "
                    + "대댓글은 별도 이슈로, 현재 parent_comment_public_id는 항상 null이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data에 CommentListResponse(comments + 페이지 메타)가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 잘못된 page/size 범위 위반, public_id 형식 위반.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
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
            @CurrentUserPublicId String userPublicId,
            @PathVariable("id") @NotBlank @Size(max = 36) String postPublicId,
            // page 상한(10000): 깊은 페이지네이션(거대한 OFFSET) 방어 가드. size와 대칭(둘 다 @Max).
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            // size 상한(100)은 명세에 없지만 과도한 조회를 막는 방어적 가드(Post/Like 목록과 동일).
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(commentService.getComments(postPublicId, userPublicId, page, size));
    }

    /** 댓글 작성. 🔒 JWT 필요. 본인 명의로 INSERT + 게시글 comment_count +1. */
    @Operation(
            summary = "댓글 작성",
            description = "게시글에 댓글을 작성한다. 작성자는 JWT public_id claim에서 식별된다. "
                    + "작성 성공 시 게시글의 comment_count가 1 증가한다. "
                    + "대댓글은 본 사이클 범위 밖 — 모든 댓글이 최상위로 INSERT된다(parent_comment_public_id 응답은 항상 null). "
                    + "없거나 삭제된 게시글이면 404 COMMUNITY4001.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "작성 성공. data에 CommentResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 잘못된 요청 (content 빈값/길이 초과/path variable 형식 위반).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
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
    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentResponse> createComment(
            @PathVariable("id") @NotBlank @Size(max = 36) String postPublicId,
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody CreateCommentRequest request) {
        return ApiResponse.success(SuccessStatus.CREATED,
                commentService.createComment(postPublicId, userPublicId, request));
    }

    /** 댓글 삭제(soft delete). 🔒 JWT 필요. 본인 댓글만 삭제 가능 + 게시글 comment_count -1. */
    @Operation(
            summary = "댓글 삭제",
            description = "본인이 작성한 댓글을 soft delete한다(deleted_at만 갱신, row 보존). "
                    + "삭제 성공 시 게시글의 comment_count가 1 감소한다(같은 트랜잭션 내). "
                    + "응답 body는 data: null. 본인이 아닌 경우 403 COMMON4031. "
                    + "이미 삭제된 댓글의 재삭제는 404 COMMUNITY4002로 응답한다(deleted_at IS NULL 필터). "
                    + "URL의 postId와 댓글의 실제 게시글이 다르면 404 COMMUNITY4002로 통일.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "삭제 성공. data는 null."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - path variable 형식 위반(빈값/36자 초과).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "COMMON4031 - 본인이 작성한 댓글이 아닙니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4031", value = EX_COMMON4031))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "COMMUNITY4001 - 존재하지 않는 게시글입니다. / "
                            + "COMMUNITY4002 - 존재하지 않는 댓글입니다(미존재·이미 삭제됨·URL 불일치).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                    @ExampleObject(name = "COMMUNITY4001", value = EX_COMMUNITY4001),
                                    @ExampleObject(name = "COMMUNITY4002", value = EX_COMMUNITY4002)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @DeleteMapping("/{postId}/comments/{commentId}")
    public ApiResponse<Void> deleteComment(
            @PathVariable("postId") @NotBlank @Size(max = 36) String postPublicId,
            @PathVariable("commentId") @NotBlank @Size(max = 36) String commentPublicId,
            @CurrentUserPublicId String userPublicId) {
        commentService.deleteComment(postPublicId, commentPublicId, userPublicId);
        return ApiResponse.success(null);
    }

    /** 댓글 번역 보기 (#161). 🔒 JWT 필요. 화이트리스트(ko/en/vi/fil) + 5000자 캡. */
    @Operation(
            summary = "댓글 번역 보기",
            description = "댓글 본문을 사용자 언어로 번역해 반환한다(lazy). 댓글에는 별도 작성 언어 컬럼이 없어 "
                    + "부모 게시글의 language를 기준으로 같은-언어 판정을 한다. 캐시 hit 시 즉시 반환, "
                    + "미스 시 계정 B Bedrock(Claude Haiku) Lambda 호출 후 comment_translations에 저장한다. "
                    + "지원 언어: ko/en/vi/fil. 본문 5000자 초과는 COMMUNITY4004로 거절.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "번역 성공. data에 CommentTranslationResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - language 누락/형식 위반 또는 path variable 형식 위반. / "
                            + "COMMUNITY4003 - 지원하지 않는 언어. / "
                            + "COMMUNITY4004 - 본문이 너무 깁니다(5000자 초과).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                    @ExampleObject(name = "COMMON4001", value = EX_COMMON4001),
                                    @ExampleObject(name = "COMMUNITY4003", value = EX_COMMUNITY4003),
                                    @ExampleObject(name = "COMMUNITY4004", value = EX_COMMUNITY4004)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "COMMUNITY4001 - 존재하지 않는 게시글입니다. / "
                            + "COMMUNITY4002 - 존재하지 않는 댓글입니다(미존재·이미 삭제·URL 불일치).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                    @ExampleObject(name = "COMMUNITY4001", value = EX_COMMUNITY4001),
                                    @ExampleObject(name = "COMMUNITY4002", value = EX_COMMUNITY4002)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류 (예: Bedrock Lambda 호출 실패 — 폴백 없음).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping("/{postId}/comments/{commentId}/translation")
    public ApiResponse<CommentTranslationResponse> getCommentTranslation(
            @CurrentUserPublicId String userPublicId,
            @PathVariable("postId") @NotBlank @Size(max = 36) String postPublicId,
            @PathVariable("commentId") @NotBlank @Size(max = 36) String commentPublicId,
            @RequestParam("language") @NotBlank @Size(max = 10) String language) {
        return ApiResponse.success(
                commentTranslationService.getOrTranslate(postPublicId, commentPublicId, language));
    }
}
