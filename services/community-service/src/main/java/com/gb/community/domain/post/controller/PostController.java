package com.gb.community.domain.post.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.common.response.SuccessStatus;
import com.gb.community.domain.post.dto.request.PostCreateRequest;
import com.gb.community.domain.post.dto.request.PostUpdateRequest;
import com.gb.community.domain.post.dto.response.PostDetailResponse;
import com.gb.community.domain.post.dto.response.PostListResponse;
import com.gb.community.domain.post.dto.response.PostTranslationResponse;
import com.gb.community.domain.post.service.PostService;
import com.gb.community.domain.post.service.PostTranslationService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Community Post", description = "커뮤니티 게시글 CRUD API")
@RestController
@RequestMapping("/api/v1/community/posts")
@RequiredArgsConstructor
// @Validated: @RequestParam/@PathVariable/@RequestHeader 메서드 파라미터의 Bean Validation을 활성화한다.
// 위반 시 ConstraintViolationException → GlobalExceptionHandler에서 COMMON4001(400)으로 변환.
@Validated
public class PostController {

    // 응답별 ErrorResponse 예시 JSON. ErrorCode enum의 (code, message)와 1:1 일치하도록 손으로 박는다.
    // (common ErrorResponse 클래스 레벨 example을 응답별로 override 하기 위함.)
    private static final String EX_COMMON4001 =
            "{\"success\":false,\"code\":\"COMMON4001\",\"message\":\"요청 값이 올바르지 않습니다.\"}";
    private static final String EX_AUTH4011 =
            "{\"success\":false,\"code\":\"AUTH4011\",\"message\":\"인증이 필요합니다.\"}";
    private static final String EX_COMMON4031 =
            "{\"success\":false,\"code\":\"COMMON4031\",\"message\":\"접근 권한이 없습니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";
    private static final String EX_COMMUNITY4001 =
            "{\"success\":false,\"code\":\"COMMUNITY4001\",\"message\":\"존재하지 않는 게시글입니다.\"}";
    private static final String EX_COMMUNITY4003 =
            "{\"success\":false,\"code\":\"COMMUNITY4003\",\"message\":\"지원하지 않는 언어입니다.\"}";
    private static final String EX_COMMUNITY4004 =
            "{\"success\":false,\"code\":\"COMMUNITY4004\",\"message\":\"본문이 너무 깁니다.\"}";

    private final PostService postService;
    private final PostTranslationService postTranslationService;

    /** 게시글 목록·검색. 🔒 JWT 필요(본인 식별은 토큰 public_id claim에서 추출). */
    @Operation(
            summary = "게시글 목록·검색",
            description = "category(선택)·keyword(선택, 제목·본문 검색)·sort(latest/popular/accuracy)로 "
                    + "필터·정렬해 페이지네이션한다. 삭제된 글은 제외된다. "
                    + "각 항목의 is_author는 요청자(JWT public_id)와 작성자 일치 여부 — "
                    + "프론트의 수정·삭제 버튼 노출 판단용.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data에 PostListResponse(posts + 페이지 메타)가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 잘못된 category/sort 값 또는 page/size 범위 위반.",
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
    @GetMapping
    public ApiResponse<PostListResponse> getPosts(
            @CurrentUserPublicId String userPublicId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "latest") String sort,
            // page 상한(10000): 깊은 페이지네이션(거대한 OFFSET) 방어 가드. size와 대칭(둘 다 @Max).
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            // size 상한(100)은 명세에 없지만 과도한 조회를 막는 방어적 가드.
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(postService.getPosts(userPublicId, category, keyword, sort, page, size));
    }

    /** 게시글 단건 조회. 🔒 JWT 필요. */
    @Operation(
            summary = "게시글 단건 조회",
            description = "게시글 public_id로 본문 + 작성자(닉네임/인증배지) + 카운트를 반환한다. "
                    + "is_author는 요청자(JWT public_id)와 작성자 일치 여부 — 수정·삭제 버튼 노출 판단용. "
                    + "is_liked는 요청자의 좋아요 여부(항상 true/false) — 하트 상태 표시용. "
                    + "삭제됐거나 없는 글이면 404 COMMUNITY4001.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data에 PostDetailResponse가 담긴다."),
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
    @GetMapping("/{id}")
    public ApiResponse<PostDetailResponse> getPost(
            @CurrentUserPublicId String userPublicId,
            @PathVariable("id") @NotBlank @Size(max = 36) String postPublicId) {
        return ApiResponse.success(postService.getPost(userPublicId, postPublicId));
    }

    /** 게시글 작성. 🔒 JWT 필요. */
    @Operation(
            summary = "게시글 작성",
            description = "category·title·content(필수)로 게시글을 작성한다. 작성 언어는 현재 \"ko\"로 고정된다(인증/locale 연동 전).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "작성 성공. data에 PostDetailResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 필수값 누락 또는 잘못된 category 값. "
                            + "(공통 검증 핸들러가 Bean Validation 실패를 COMMON4001로 통일한다.)",
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
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PostDetailResponse> createPost(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody PostCreateRequest request) {
        return ApiResponse.success(SuccessStatus.CREATED, postService.createPost(userPublicId, request));
    }

    /** 게시글 수정(PATCH, 본인만). 🔒 JWT 필요. */
    @Operation(
            summary = "게시글 수정",
            description = "본인 게시글의 category/title/content를 부분 수정한다(보낸 필드만 변경). "
                    + "타인 글이면 403 COMMON4031, 없는 글이면 404 COMMUNITY4001.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "수정 성공. data에 갱신된 PostDetailResponse가 담긴다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 잘못된 category 값 또는 입력 형식 오류.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "COMMON4031 - 본인 게시글이 아닙니다(권한 없음).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4031", value = EX_COMMON4031))),
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
    @PatchMapping("/{id}")
    public ApiResponse<PostDetailResponse> updatePost(
            @CurrentUserPublicId String userPublicId,
            @PathVariable("id") @NotBlank @Size(max = 36) String postPublicId,
            @Valid @RequestBody PostUpdateRequest request) {
        return ApiResponse.success(postService.updatePost(userPublicId, postPublicId, request));
    }

    /** 게시글 삭제(soft delete, 본인만). 🔒 JWT 필요. */
    @Operation(
            summary = "게시글 삭제",
            description = "본인 게시글을 soft delete(deleted_at 세팅)한다. 타인 글이면 403 COMMON4031, "
                    + "없는 글이면 404 COMMUNITY4001. 성공 시 200 + data:null.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "삭제 성공. data는 null."),
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
                    responseCode = "403",
                    description = "COMMON4031 - 본인 게시글이 아닙니다(권한 없음).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4031", value = EX_COMMON4031))),
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
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deletePost(
            @CurrentUserPublicId String userPublicId,
            @PathVariable("id") @NotBlank @Size(max = 36) String postPublicId) {
        postService.deletePost(userPublicId, postPublicId);
        return ApiResponse.success(null);
    }

    /** 게시글 번역 보기 (#161). 🔒 JWT 필요. 화이트리스트(ko/en/vi/fil) + 5000자 캡. */
    @Operation(
            summary = "게시글 번역 보기",
            description = "게시글 본문을 사용자 언어로 번역해 반환한다(lazy). 동일 언어 요청 시 원문 반환, "
                    + "캐시(post_translations) hit 시 즉시 반환, 미스 시 계정 B Bedrock(Claude Haiku) Lambda 호출 후 캐시. "
                    + "지원 언어 화이트리스트: ko/en/vi/fil. 본문 5000자 초과는 COMMUNITY4004로 거절(번역 비용 캡). "
                    + "본문/제목 수정 시 해당 글의 모든 언어 번역 캐시가 무효화된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "번역 성공. data에 PostTranslationResponse가 담긴다."),
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
                    description = "COMMUNITY4001 - 존재하지 않는 게시글입니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMUNITY4001", value = EX_COMMUNITY4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류 (예: Bedrock Lambda 호출 실패 — 폴백 없음).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping("/{id}/translation")
    public ApiResponse<PostTranslationResponse> getPostTranslation(
            // 컨트롤러는 요청자 식별을 위해 인증된 JWT가 필요하다(authenticated()) — 값을 직접 쓰진 않지만
            // 본인 식별을 강제하려면 @CurrentUserPublicId를 둬야 한다(claim 누락 시 AUTH4011 fail-fast).
            @CurrentUserPublicId String userPublicId,
            @PathVariable("id") @NotBlank @Size(max = 36) String postPublicId,
            // language는 필수 — null/blank·길이 위반은 @NotBlank/@Size에서 COMMON4001로 컷.
            // 화이트리스트(ko/en/vi/fil) 검증은 Service에서(잘못된 값은 COMMUNITY4003 — 도메인 코드).
            @RequestParam("language") @NotBlank @Size(max = 10) String language) {
        return ApiResponse.success(postTranslationService.getOrTranslate(postPublicId, language));
    }
}
