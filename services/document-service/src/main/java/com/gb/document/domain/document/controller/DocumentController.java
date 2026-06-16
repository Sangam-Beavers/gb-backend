package com.gb.document.domain.document.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.common.response.SuccessStatus;
import com.gb.document.domain.document.dto.request.SubmitRequest;
import com.gb.document.domain.document.dto.response.DocumentResultResponse;
import com.gb.document.domain.document.dto.response.DocumentStatusResponse;
import com.gb.document.domain.document.dto.response.DocumentSummaryResponse;
import com.gb.document.domain.document.dto.response.SubmissionResponse;
import com.gb.document.domain.document.service.DocumentSubmissionService;
import com.gb.document.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Document", description = "문서 분석 API (제출/상태/결과/목록)")
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    // 응답별 ErrorResponse 예시 — wallet 패턴(WalletController) 그대로.
    private static final String EX_DOCUMENT4001 =
            "{\"success\":false,\"code\":\"DOCUMENT4001\",\"message\":\"존재하지 않는 문서입니다.\"}";
    private static final String EX_COMMON4031 =
            "{\"success\":false,\"code\":\"COMMON4031\",\"message\":\"접근 권한이 없습니다.\"}";
    private static final String EX_AUTH4011 =
            "{\"success\":false,\"code\":\"AUTH4011\",\"message\":\"인증이 필요합니다.\"}";
    private static final String EX_COMMON4221 =
            "{\"success\":false,\"code\":\"COMMON4221\",\"message\":\"처리할 수 없는 요청입니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";
    private static final String EX_DOCUMENT4002 =
            "{\"success\":false,\"code\":\"DOCUMENT4002\",\"message\":\"서류 분석 크레딧이 부족합니다.\"}";

    private final DocumentSubmissionService documentSubmissionService;

    @Operation(
            summary = "문서 분석 요청",
            description = "분석 대상 문서 종류와 파일명을 받아 분석 요청을 생성하고, "
                    + "사용자가 원본 파일을 업로드할 S3 Pre-signed PUT URL을 발급한다. "
                    + "Pre-signed URL은 약 10분 동안 유효하다. 사용자는 JWT의 public_id claim으로 식별한다.")
    // 비즈니스 코드(DOCUMENT4001 등)는 HTTP 상태와 별개이므로 responseCode에는 HTTP 상태를,
    // description에 "비즈니스 코드 - 의미"를 적는다. 실패 응답 본문은 공통 ErrorResponse 구조.
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "생성 성공. data에 SubmissionResponse(public_id, upload_url, expires_at)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 본문 검증 실패(파일명 형식/필드 누락 등).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다(토큰 누락·만료·위조).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "DOCUMENT4002 - 서류 분석 크레딧이 부족합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "DOCUMENT4002", value = EX_DOCUMENT4002))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SubmissionResponse> submit(
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody SubmitRequest request) {
        SubmissionResponse data = documentSubmissionService.submit(userPublicId, request);
        return ApiResponse.success(SuccessStatus.CREATED, data);
    }

    @Operation(
            summary = "분석 진행 상태 조회",
            description = "프론트가 폴링하는 가벼운 엔드포인트. ANALYZING/COMPLETED/FAILED 중 하나를 반환한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "COMMON4031 - 다른 사용자의 문서.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4031", value = EX_COMMON4031))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "DOCUMENT4001 - 존재하지 않는 문서.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "DOCUMENT4001", value = EX_DOCUMENT4001)))
    })
    @GetMapping("/{publicId}/status")
    public ApiResponse<DocumentStatusResponse> getStatus(
            @CurrentUserPublicId String userPublicId,
            @Parameter(description = "문서 식별자(UUID). dev 시드: ...0001=완료, ...0002=FAILED, ...0003=분석중",
                    example = "00000000-0000-0000-0000-000000000001")
            @PathVariable String publicId) {
        return ApiResponse.success(documentSubmissionService.getStatus(userPublicId, publicId));
    }

    @Operation(
            summary = "분석 결과 상세 조회",
            description = "분석이 완료된(또는 PARTIAL) 문서의 상세 결과를 반환한다. "
                    + "결과가 아직 없는 경우(ANALYZING/결과 미생성) 422.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공. data에 DocumentResultResponse."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "COMMON4031 - 다른 사용자의 문서.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4031", value = EX_COMMON4031))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "DOCUMENT4001 - 존재하지 않는 문서.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "DOCUMENT4001", value = EX_DOCUMENT4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "COMMON4221 - 분석 결과가 아직 없습니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4221", value = EX_COMMON4221)))
    })
    @GetMapping("/{publicId}/result")
    public ApiResponse<DocumentResultResponse> getResult(
            @CurrentUserPublicId String userPublicId,
            @Parameter(description = "문서 식별자(UUID). dev 시드 ...0001=완료+결과(200), ...0003=분석중(422)",
                    example = "00000000-0000-0000-0000-000000000001")
            @PathVariable String publicId) {
        return ApiResponse.success(documentSubmissionService.getResult(userPublicId, publicId));
    }

    @Operation(summary = "내 분석 요청 목록 조회",
            description = "본인이 제출한 분석 요청을 최근순으로 페이지 조회한다. "
                    + "기본 페이지 크기 20, 정렬 createdAt DESC. "
                    + "status 파라미터(복수 허용, 콤마 구분)로 상태 필터링 — 생략 시 전체.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공. data는 Spring Page 구조(content, totalElements, totalPages, ...)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "COMMON4001 - status 필터 값이 올바르지 않습니다"
                            + "(ANALYZING/COMPLETED/FAILED 외의 값).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011)))
    })
    @GetMapping
    public ApiResponse<Page<DocumentSummaryResponse>> list(
            @CurrentUserPublicId String userPublicId,
            @Parameter(description = "상태 필터(복수 허용, 콤마 구분). ANALYZING/COMPLETED/FAILED. 생략 시 전체.",
                    example = "ANALYZING,COMPLETED")
            @RequestParam(name = "status", required = false) List<String> status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.success(documentSubmissionService.list(userPublicId, status, pageable));
    }
}
