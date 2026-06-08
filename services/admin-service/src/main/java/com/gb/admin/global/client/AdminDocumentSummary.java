package com.gb.admin.global.client;

import java.time.LocalDateTime;

public record AdminDocumentSummary(
        String documentPublicId,
        String userPublicId,
        String userName,
        String analysisDocumentType,
        String language,            // 사용자 모국어(VI/TH/ZH 등)
        String overallRiskLevel,    // LOW / MEDIUM / HIGH
        String followUpAction,      // "변호사 상담 광고 클릭" 등 표시용 자유 텍스트
        LocalDateTime analyzedAt
) {
}
