package com.gb.document.domain.document.dto.request;

import com.gb.document.domain.document.entity.AnalysisDocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 분석 요청 본문(POST /api/v1/documents). v1.1 SSOT.
 *
 * <p>fileName은 S3 키 일부로 들어가므로 경로 조작 방지용으로 슬래시·역슬래시·제어문자를 막는다.
 */
public record SubmitRequest(

        @Schema(description = "분석 대상 문서 종류",
                allowableValues = {"LABOR_CONTRACT", "PAYSLIP", "EMPLOYMENT_CONTRACT"},
                example = "LABOR_CONTRACT")
        @NotNull
        AnalysisDocumentType analysisDocumentType,

        @Schema(description = "사용자 업로드 원본 파일명", example = "contract.pdf")
        @NotBlank
        @Size(max = 255)
        @Pattern(regexp = "^[^/\\\\\\x00-\\x1F]+$",
                message = "파일명에 경로 구분자나 제어문자를 포함할 수 없습니다.")
        String fileName
) {}
