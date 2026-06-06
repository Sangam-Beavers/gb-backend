package com.gb.document.domain.document.service;

import com.gb.document.domain.document.dto.request.SubmitRequest;
import com.gb.document.domain.document.dto.response.DocumentResultResponse;
import com.gb.document.domain.document.dto.response.DocumentStatusResponse;
import com.gb.document.domain.document.dto.response.DocumentSummaryResponse;
import com.gb.document.domain.document.dto.response.SubmissionResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 분석 요청/조회/재요청 비즈니스 로직 진입점. 1단계 범위(SQS Consumer 제외).
 */
public interface DocumentSubmissionService {

    SubmissionResponse submit(String userPublicId, SubmitRequest request);

    DocumentStatusResponse getStatus(String userPublicId, String publicId);

    DocumentResultResponse getResult(String userPublicId, String publicId);

    /**
     * 본인 분석 요청 목록 조회. statuses는 상태 필터(ANALYZING/COMPLETED/FAILED 문자열) —
     * null/빈 리스트면 전체 조회, 잘못된 값이면 COMMON4001.
     */
    Page<DocumentSummaryResponse> list(String userPublicId, List<String> statuses, Pageable pageable);

    SubmissionResponse retry(String userPublicId, String publicId);
}
