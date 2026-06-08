package com.gb.document.domain.admin.service.impl;

import com.gb.document.domain.admin.dto.response.AdminDocumentPageResponse;
import com.gb.document.domain.admin.dto.response.AdminDocumentView;
import com.gb.document.domain.admin.dto.response.DocumentStatsResponse;
import com.gb.document.domain.admin.service.DocumentAdminInternalService;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.DocumentStatus;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.entity.RiskLevel;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentAdminInternalServiceImpl implements DocumentAdminInternalService {

    private final DocumentRepository documentRepository;
    private final DocumentResultRepository documentResultRepository;

    @Override
    public AdminDocumentPageResponse search(String userPublicId, String riskLevel, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        String upi = (userPublicId == null || userPublicId.isBlank()) ? null : userPublicId;
        Page<Document> docs = documentRepository.searchForAdmin(upi, pageable);

        List<Long> ids = docs.getContent().stream().map(Document::getId).toList();
        Map<Long, DocumentResult> resultBySubmission = new HashMap<>();
        if (!ids.isEmpty()) {
            documentResultRepository.findAllBySubmission_IdIn(ids)
                    .forEach(r -> resultBySubmission.put(r.getSubmission().getId(), r));
        }

        Page<AdminDocumentView> mapped = docs.map(d -> AdminDocumentView.from(d, resultBySubmission.get(d.getId())));

        if (riskLevel != null && !riskLevel.isBlank()) {
            String want = riskLevel.toUpperCase();
            List<AdminDocumentView> filtered = mapped.getContent().stream()
                    .filter(v -> want.equals(v.overallRiskLevel()))
                    .toList();
            mapped = new org.springframework.data.domain.PageImpl<>(filtered, pageable, docs.getTotalElements());
        }
        return AdminDocumentPageResponse.from(mapped);
    }

    @Override
    public DocumentStatsResponse stats(LocalDateTime from, LocalDateTime to) {
        LocalDateTime f = from != null ? from : LocalDate.now().atStartOfDay();
        LocalDateTime t = to != null ? to : LocalDateTime.of(LocalDate.now(), LocalTime.MAX);

        long todayAnalyzed = documentRepository.countByCreatedAtBetween(f, t);

        Map<String, Long> processingCounts = new LinkedHashMap<>();
        processingCounts.put(ProcessingStatus.COMPLETED.name(), 0L);
        processingCounts.put(ProcessingStatus.FAILED.name(), 0L);
        processingCounts.put(ProcessingStatus.PARTIAL.name(), 0L);
        documentResultRepository.countByProcessingStatusBetween(f, t)
                .forEach(p -> processingCounts.put(p.getStatus().name(), p.getCnt()));

        long success = processingCounts.getOrDefault(ProcessingStatus.COMPLETED.name(), 0L);
        long failed = processingCounts.getOrDefault(ProcessingStatus.FAILED.name(), 0L);
        long partial = processingCounts.getOrDefault(ProcessingStatus.PARTIAL.name(), 0L);

        Map<String, Long> byRisk = new LinkedHashMap<>();
        byRisk.put(RiskLevel.LOW.name(), 0L);
        byRisk.put(RiskLevel.MEDIUM.name(), 0L);
        byRisk.put(RiskLevel.HIGH.name(), 0L);
        documentResultRepository.countByRiskLevelBetween(f, t).forEach(p -> {
            if (p.getRisk() != null) byRisk.put(p.getRisk().name(), p.getCnt());
        });

        // 분석 성공률 = COMPLETED / (COMPLETED+FAILED+PARTIAL). 분석 자체가 결과를 생성한 비율로 보는 발표용 정의.
        long totalAttempts = success + failed + partial;
        String successRate = totalAttempts == 0 ? "0.0000"
                : BigDecimal.valueOf(success).divide(BigDecimal.valueOf(totalAttempts),
                        4, RoundingMode.HALF_UP).toPlainString();

        // failed 카운트는 document_results 가 만들어진 FAILED 만 잡힌다 — submission 자체가 FAILED 인 케이스도 포함하기 위해
        // document.status=FAILED 카운트를 추가로 합산한다(중복 우려는 낮음, 발표용 단순화).
        long submissionFailed = documentRepository.countByStatusAndCreatedAtBetween(DocumentStatus.FAILED, f, t);
        if (submissionFailed > failed) {
            failed = submissionFailed;
        }

        return new DocumentStatsResponse(todayAnalyzed, success, failed, partial, byRisk, successRate);
    }

    private static int normalizeSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, 200);
    }
}
