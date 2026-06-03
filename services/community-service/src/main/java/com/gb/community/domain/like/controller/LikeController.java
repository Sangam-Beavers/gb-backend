package com.gb.community.domain.like.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.common.response.SuccessStatus;
import com.gb.community.domain.like.dto.response.LikedPostListResponse;
import com.gb.community.domain.like.dto.response.PostLikeResponse;
import com.gb.community.domain.like.service.LikeService;
import com.gb.community.global.security.CurrentUserPublicId;
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
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 커뮤니티 관심글(좋아요) API — 관심글 목록 / 저장 / 취소 (api-spec §4~§5).
 *
 * <p>{@code GET /posts/liked}는 {@code PostController}의 {@code GET /posts/{id}}와 같은 베이스 경로에
 * 공존한다. 스프링이 리터럴 경로(/liked)를 path-variable(/{id})보다 우선 매핑하므로 /liked가 단건 조회로
 * 새지 않는다.
 */
@Tag(name = "Community Like", description = "커뮤니티 관심글(좋아요) API")
@RestController
@RequestMapping("/api/v1/community/posts")
@RequiredArgsConstructor
// @Validated: @RequestParam/@PathVariable/@RequestHeader 파라미터 Bean Validation 활성화.
// 위반 시 ConstraintViolationException → GlobalExceptionHandler에서 COMMON4001(400)로 변환.
@Validated
public class LikeController {

    // 응답별 ErrorResponse 예시 JSON. ErrorCode enum의 (code, message)와 1:1 일치하도록 손으로 박는다.
    private static final String EX_COMMON4001 =
            "{\"success\":false,\"code\":\"COMMON4001\",\"message\":\"요청 값이 올바르지 않습니다.\"}";
    private static final String EX_AUTH4011 =
            "{\"success\":false,\"code\":\"AUTH4011\",\"message\":\"인증이 필요합니다.\"}";
    private static final String EX_COMMON4091 =
            "{\"success\":false,\"code\":\"COMMON4091\",\"message\":\"이미 존재하는 리소스입니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";
    private static final String EX_COMMUNITY4001 =
            "{\"success\":false,\"code\":\"COMMUNITY4001\",\"message\":\"존재하지 않는 게시글입니다.\"}";

    private final LikeService likeService;

    /** 관심글 목록 조회. 🔒 JWT 필요(본인 식별은 토큰 public_id claim에서 추출). */
    @Operation(
            summary = "관심글 목록 조회",
            description = "요청자가 좋아요한 게시글 목록을 sort(latest=좋아요 누른 시각순 / popular=좋아요 수순, "
                    + "기본 latest)로 정렬해 페이지네이션한다. 각 항목에 liked_at(좋아요 누른 시각)이 포함된다. "
                    + "삭제된 글은 제외된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data에 LikedPostListResponse(posts + 페이지 메타)가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 잘못된 sort 값 또는 page/size 범위 위반.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping("/liked")
    public ApiResponse<LikedPostListResponse> getLikedPosts(
            @CurrentUserPublicId String userPublicId,
            @RequestParam(required = false, defaultValue = "latest") String sort,
            // page 상한(10000): 깊은 페이지네이션(거대한 OFFSET) 방어 가드. size와 대칭(둘 다 @Max).
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            // size 상한(100)은 과도한 조회를 막는 방어적 가드.
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(likeService.getLikedPosts(userPublicId, sort, page, size));
    }

    /** 관심글 저장(좋아요). 🔒 JWT 필요. */
    @Operation(
            summary = "관심글 저장(좋아요)",
            description = "게시글 public_id로 좋아요를 저장한다. like_count +1, 성공 시 201. 이미 좋아요한 글이면 "
                    + "409 COMMON4091, 없거나 삭제된 글이면 404 COMMUNITY4001. "
                    + "응답 바디는 post_public_id/like_count/liked를 반환한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "저장 성공. data에 PostLikeResponse(like_count 갱신값, liked=true)가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 잘못된 요청(public_id 형식 위반).",
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
                    responseCode = "409",
                    description = "COMMON4091 - 이미 좋아요한 게시글입니다(중복).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4091", value = EX_COMMON4091))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @PostMapping("/{id}/likes")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PostLikeResponse> like(
            @CurrentUserPublicId String userPublicId,
            @PathVariable("id") @NotBlank @Size(max = 36) String postPublicId) {
        return ApiResponse.success(SuccessStatus.CREATED, likeService.like(userPublicId, postPublicId));
    }

    /** 관심글 취소(좋아요 취소). 🔒 JWT 필요. */
    @Operation(
            summary = "관심글 취소(좋아요 취소)",
            description = "게시글 public_id의 좋아요를 취소한다. like_count -1, 성공 시 200. 없거나 삭제된 글이면 "
                    + "404 COMMUNITY4001. 안 누른 글을 취소하면 멱등 no-op로 200(like_count 변화 없음).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "취소 성공(또는 멱등 no-op). data에 PostLikeResponse(liked=false)가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 잘못된 요청(public_id 형식 위반).",
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
    @DeleteMapping("/{id}/likes")
    public ApiResponse<PostLikeResponse> unlike(
            @CurrentUserPublicId String userPublicId,
            @PathVariable("id") @NotBlank @Size(max = 36) String postPublicId) {
        return ApiResponse.success(likeService.unlike(userPublicId, postPublicId));
    }
}
