package com.gb.document.domain.admin.dto.response;

import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "관리자 문서 단건(/internal/admin)")
public record AdminDocumentView(
        String documentPublicId,
        String userPublicId,
        String analysisDocumentType,
        @Schema(description = "사용자 모국어(VI/TH/ZH 등) — DocumentResult.translatedLang. 없으면 null.", nullable = true)
        String language,
        @Schema(description = "전체 위험도(LOW/MEDIUM/HIGH).", nullable = true)
        String overallRiskLevel,
        @Schema(description = "발표 표시용 자유 텍스트(현재는 \"-\"). 후속 스프린트에서 후속 행동 추적 컬럼 도입.")
        String followUpAction,
        @Schema(description = "분석 완료 시각 또는 업로드 시각(완료 안 됐을 때).")
        LocalDateTime analyzedAt
) {

    public static AdminDocumentView from(Document d, DocumentResult result) {
        String lang = result != null ? result.getTranslatedLang() : null;
        String risk = result != null && result.getOverallRiskLevel() != null
                ? result.getOverallRiskLevel().name() : null;
        LocalDateTime analyzedAt = result != null && result.getCompletedAt() != null
                ? result.getCompletedAt() : d.getCreatedAt();
        return new AdminDocumentView(
                d.getPublicId(),
                d.getUserPublicId(),
                d.getAnalysisDocumentType() != null ? d.getAnalysisDocumentType().name() : null,
                lang,
                risk,
                "-",
                analyzedAt
        );
    }
}
