package com.gb.document.global.client.sqs;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.entity.RiskItem;
import com.gb.document.domain.document.entity.RiskLevel;
import com.gb.document.domain.document.entity.WageSummary;
import com.gb.document.domain.document.service.AnalysisResultIngestService;
import com.gb.document.global.client.sqs.dto.AnalysisResultMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SQS 결과 메시지 리스너 단위 테스트.
 *
 * <p>리스너는 spring-cloud-aws 컨테이너가 풀어 넘긴 body/attribute를 받아 일관성을 검증하고
 * {@link AnalysisResultIngestService}로 위임만 한다. 컨테이너 자체(폴링·ack·visibility)는 라이브러리
 * 영역이라 여기서는 다루지 않고, "body↔attribute 검증 → 위임" 경계만 잠근다.
 *
 * <h3>검증 포인트</h3>
 * <ul>
 *   <li>정상: {@code body.documentPublicId == attribute.document_public_id} → ingestService에 위임.</li>
 *   <li>불일치: attribute의 routing key가 본문과 다르면 {@link IllegalStateException} →
 *       컨테이너가 deleteMessage를 호출하지 않아 visibility timeout 이후 재수신, maxReceiveCount 초과 시
 *       DLQ로 이동. 위임은 일어나지 않는다(스키마 §1 경고).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AnalysisResultListenerTest {

    private static final String DOC_PUBLIC_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock AnalysisResultIngestService ingestService;

    @InjectMocks AnalysisResultListener listener;

    @Test
    @DisplayName("정상: body·attribute documentPublicId 일치 → ingestService.ingest 위임")
    void 정상수신은_ingest로_위임() {
        AnalysisResultMessage msg = message(DOC_PUBLIC_ID);

        listener.onAnalysisResult(msg, "production", DOC_PUBLIC_ID);

        verify(ingestService, times(1)).ingest(msg);
    }

    @Test
    @DisplayName("source=development(온프렘)도 라우팅 메타로 통과 — 본문 검증과 무관")
    void source_development도_정상위임() {
        AnalysisResultMessage msg = message(DOC_PUBLIC_ID);

        listener.onAnalysisResult(msg, "development", DOC_PUBLIC_ID);

        verify(ingestService, times(1)).ingest(msg);
    }

    @Test
    @DisplayName("body·attribute documentPublicId 불일치 → IllegalStateException + ingest 미호출 (DLQ 유도)")
    void 라우팅키_불일치는_예외_그리고_위임없음() {
        AnalysisResultMessage msg = message(DOC_PUBLIC_ID);
        String routingMismatch = "00000000-0000-0000-0000-000000000000";

        assertThatThrownBy(() -> listener.onAnalysisResult(msg, "production", routingMismatch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DOC_PUBLIC_ID)
                .hasMessageContaining(routingMismatch);

        verifyNoInteractions(ingestService);
    }

    // ---- helpers ----

    private AnalysisResultMessage message(String documentPublicId) {
        return new AnalysisResultMessage(
                "1.1",
                documentPublicId,
                AnalysisDocumentType.LABOR_CONTRACT,
                ProcessingStatus.COMPLETED,
                RiskLevel.HIGH,
                new BigDecimal("0.92"),
                new WageSummary(
                        "KRW",
                        new BigDecimal("2000000"),
                        new BigDecimal("9620"),
                        List.of()),
                List.of(new RiskItem(RiskLevel.HIGH, "제8조", "최저임금 미달")),
                "번역 전문",
                "ko",
                "s3://gb-document-masked-test/2026-05-29/x.png",
                null,
                Instant.parse("2026-05-29T09:00:00Z"));
    }
}
