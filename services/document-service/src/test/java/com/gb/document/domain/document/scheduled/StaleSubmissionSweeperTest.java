package com.gb.document.domain.document.scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentStatus;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.global.config.AnalysisProperties;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StaleSubmissionSweeperTest {

    private static final int TIMEOUT_MINUTES = 30;

    @Mock DocumentRepository documentRepository;

    private StaleSubmissionSweeper sweeper() {
        AnalysisProperties props = new AnalysisProperties(
                "development", "", "", "gb-document-uploads-dev", 600, "ap-northeast-2",
                false, "", TIMEOUT_MINUTES);
        return new StaleSubmissionSweeper(documentRepository, props);
    }

    @Test
    @DisplayName("sweep: 임계 초과 ANALYZING 건을 모두 FAILED로 전환한다")
    void 초과건_FAILED_전환() {
        Document a = analyzingDoc("doc-a");
        Document b = analyzingDoc("doc-b");
        given(documentRepository.findAllByStatusAndUpdatedAtBefore(
                eq(DocumentStatus.ANALYZING), any(LocalDateTime.class)))
                .willReturn(List.of(a, b));

        sweeper().sweepStaleSubmissions();

        assertThat(a.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(b.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("sweep: 임계 시각이 now - staleTimeoutMinutes 로 계산된다")
    void 임계시각_계산() {
        given(documentRepository.findAllByStatusAndUpdatedAtBefore(
                eq(DocumentStatus.ANALYZING), any(LocalDateTime.class)))
                .willReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        sweeper().sweepStaleSubmissions();
        LocalDateTime after = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);

        ArgumentCaptor<LocalDateTime> cap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(documentRepository).findAllByStatusAndUpdatedAtBefore(
                eq(DocumentStatus.ANALYZING), cap.capture());
        assertThat(cap.getValue()).isBetween(before, after);
    }

    @Test
    @DisplayName("sweep: 대상이 없으면 아무 일도 일어나지 않는다(예외 없음)")
    void 대상없음_무동작() {
        given(documentRepository.findAllByStatusAndUpdatedAtBefore(
                eq(DocumentStatus.ANALYZING), any(LocalDateTime.class)))
                .willReturn(List.of());

        sweeper().sweepStaleSubmissions(); // 예외 없이 종료하면 성공
    }

    private Document analyzingDoc(String publicId) {
        return Document.builder()
                .publicId(publicId)
                .userPublicId("user-A")
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .fileName("c.pdf")
                .status(DocumentStatus.ANALYZING)
                .s3Key("original/2026-06-04/" + publicId + "/c.pdf")
                .build();
    }
}
