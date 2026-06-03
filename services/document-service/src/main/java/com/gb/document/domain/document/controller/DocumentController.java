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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Document", description = "문서 분석 API (제출/상태/결과/목록/재요청)")
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    // 응답별 ErrorResponse 예시 — wallet 패턴(WalletController) 그대로.
    private static final String EX_DOCUMENT4001 =
            "{\"success\":false,\"code\":\"DOCUMENT4001\",\"message\":\"존재하지 않는 문서입니다.\"}";
    private static final String EX_COMMON4031 =
            "{\"success\":false,\"code\":\"COMMON4031\",\"message\":\"접근 권한이 없습니다.\"}";
    private static final String EX_COMMON4001 =
            "{\"success\":false,\"code\":\"COMMON4001\",\"message\":\"요청 값이 올바르지 않습니다.\"}";
    private static final String EX_COMMON4221 =
            "{\"success\":false,\"code\":\"COMMON4221\",\"message\":\"처리할 수 없는 요청입니다.\"}";
    private static final String EX_COMMON5000 =
            "{\"success\":false,\"code\":\"COMMON5000\",\"message\":\"서버 오류가 발생했습니다.\"}";

    private final DocumentSubmissionService documentSubmissionService;

    @Operation(
            summary = "문서 분석 요청",
            description = "분석 대상 문서 종류와 파일명을 받아 분석 요청을 생성하고, "
                    + "사용자가 원본 파일을 업로드할 S3 Pre-signed PUT URL을 발급한다. "
                    + "Pre-signed URL은 약 10분 동안 유효하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "생성 성공. data에 SubmissionResponse(public_id, upload_url, expires_at)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 본문 검증 실패(파일명 형식/필드 누락 등) 또는 X-User-Public-Id 헤더 누락.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "COMMON5000 - 서버 오류.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON5000", value = EX_COMMON5000)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SubmissionResponse> submit(
            // TODO: 인증 구현 후 JWT 토큰(sub/claim)에서 userPublicId 추출로 교체.
            //       현재는 인증 미구현으로 헤더(X-User-Public-Id)로 임시 수신.
            @RequestHeader("X-User-Public-Id") String userPublicId,
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "COMMON4001 - X-User-Public-Id 헤더 누락(필수 헤더 미전송).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
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
            @RequestHeader("X-User-Public-Id") String userPublicId,
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "COMMON4001 - X-User-Public-Id 헤더 누락(필수 헤더 미전송).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
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
            @RequestHeader("X-User-Public-Id") String userPublicId,
            @PathVariable String publicId) {
        return ApiResponse.success(documentSubmissionService.getResult(userPublicId, publicId));
    }

    @Operation(summary = "내 분석 요청 목록 조회",
            description = "본인이 제출한 분석 요청을 최근순으로 페이지 조회한다. "
                    + "기본 페이지 크기 20, 정렬 createdAt DESC.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공. data는 Spring Page 구조(content, totalElements, totalPages, ...)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "COMMON4001 - X-User-Public-Id 헤더 누락(필수 헤더 미전송).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001)))
    })
    @GetMapping
    public ApiResponse<Page<DocumentSummaryResponse>> list(
            @RequestHeader("X-User-Public-Id") String userPublicId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.success(documentSubmissionService.list(userPublicId, pageable));
    }

    @Operation(summary = "분석 재요청",
            description = "FAILED 상태의 문서를 다시 분석한다. S3 원본을 재사용하므로 재업로드는 불필요. "
                    + "비-FAILED 상태에서 호출 시 422.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "재요청 접수. status가 ANALYZING으로 전환된다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "COMMON4001 - X-User-Public-Id 헤더 누락(필수 헤더 미전송).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4001", value = EX_COMMON4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "COMMON4031 - 다른 사용자의 문서.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4031", value = EX_COMMON4031))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "DOCUMENT4001 - 존재하지 않는 문서.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "DOCUMENT4001", value = EX_DOCUMENT4001))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "COMMON4221 - FAILED 상태가 아닌 문서.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4221", value = EX_COMMON4221)))
    })
    @PostMapping("/{publicId}/retry")
    public ApiResponse<SubmissionResponse> retry(
            @RequestHeader("X-User-Public-Id") String userPublicId,
            @PathVariable String publicId) {
        return ApiResponse.success(documentSubmissionService.retry(userPublicId, publicId));
    }
}
