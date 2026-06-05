package com.gb.community.domain.qna.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.community.domain.qna.dto.response.QnaListResponse;
import com.gb.community.domain.qna.service.QnaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주요 QnA 목록 API (api-spec §8) — {@code GET /api/v1/community/qna}.
 *
 * <p>게시글 CRUD({@code /community/posts/...})와 분리된 별도 엔드포인트라 별도 컨트롤러로 둔다.
 * 작성자 정보를 포함하지 않는 가벼운 Top N 응답 + 페이지네이션 없음.
 */
@Tag(name = "Community QnA", description = "커뮤니티 주요 QnA 목록 API")
@RestController
@RequestMapping("/api/v1/community/qna")
@RequiredArgsConstructor
// @Validated: @RequestParam의 Bean Validation을 활성화. 위반 시 ConstraintViolationException →
// GlobalExceptionHandler에서 COMMON4001(400)으로 변환.
@Validated
public class QnaController {

    // 응답별 ErrorResponse 예시 JSON. ErrorCode enum의 (code, message)와 1:1 일치하도록 손으로 박는다.
    private static final String EX_COMMON4001 =
            "{\"success\":false,\"code\":\"COMMON4001\",\"message\":\"요청 값이 올바르지 않습니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";

    private final QnaService qnaService;

    /** 주요 QnA 목록 조회. 🔓 인증 불필요 — 비로그인 사용자도 인기 질문을 둘러볼 수 있다(SecurityConfig permitAll). */
    @Operation(
            summary = "주요 QnA 목록",
            description = "특정 카테고리의 활성 게시글을 답변(댓글) 수 내림차순으로 상위 N건 반환한다. "
                    + "page 메타는 없다(Top N 고정). 작성자 정보·본문은 응답에 포함하지 않으며 단건 조회 API로 별도 조회한다. "
                    + "category 미입력 시 QUESTION 카테고리만, 입력 시 해당 카테고리만 반환한다 "
                    + "(LIFE_INFO/JOB/VISA/COUNTRY/RESIDENCE/QUESTION/FREE). 잘못된 카테고리는 400 COMMON4001. "
                    + "본 API는 **인증 불필요** — 비로그인 사용자도 호출 가능.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data.posts는 답변 수 내림차순 정렬된 배열(최대 size건)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 잘못된 카테고리 / size 범위 위반 (1~100).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류(예상치 못한 예외).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @GetMapping
    public ApiResponse<QnaListResponse> getQnaPosts(
            // category는 선택 — 미입력 시 서비스에서 QUESTION 기본값. 형식만 가드(최대 30자, enum 검증은 서비스).
            @RequestParam(required = false) @Size(max = 30) String category,
            // size 가드: 1~100. 기본 5(명세 §8). 상한 100은 과도한 조회 방어용.
            @RequestParam(defaultValue = "5") @Min(1) @Max(100) int size) {
        return ApiResponse.success(qnaService.getQnaPosts(category, size));
    }
}
