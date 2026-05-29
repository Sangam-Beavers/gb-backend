package com.gb.document.domain.document.service;

import com.gb.document.domain.document.dto.request.SubmitRequest;
import com.gb.document.domain.document.dto.response.DocumentResultResponse;
import com.gb.document.domain.document.dto.response.DocumentStatusResponse;
import com.gb.document.domain.document.dto.response.DocumentSummaryResponse;
import com.gb.document.domain.document.dto.response.SubmissionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 분석 요청/조회/재요청 비즈니스 로직 진입점. 1단계 범위(SQS Consumer 제외).
 */
public interface DocumentSubmissionService {

    SubmissionResponse submit(String userPublicId, SubmitRequest request);

    DocumentStatusResponse getStatus(String userPublicId, String publicId);

    DocumentResultResponse getResult(String userPublicId, String publicId);

    Page<DocumentSummaryResponse> list(String userPublicId, Pageable pageable);

    SubmissionResponse retry(String userPublicId, String publicId);
}
