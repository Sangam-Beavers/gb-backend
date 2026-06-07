package com.gb.document.domain.chat.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.document.domain.chat.dto.request.ChatRequest;
import com.gb.document.domain.chat.dto.request.ChatbotPayload;
import com.gb.document.domain.chat.dto.response.ChatHistoryResponse;
import com.gb.document.domain.chat.service.AnalysisSummaryBuilder;
import com.gb.document.domain.chat.service.ChatStreamListener;
import com.gb.document.domain.chat.service.ChatbotLambdaClient;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ChatServiceImpl} 단위 테스트(CLAUDE.md §10 — @Mock + @InjectMocks, 컨텍스트 없음).
 * 핵심 검증: 첫 턴에만 analysis_summary가 페이로드에 실리는가(ai-chatbot-mcp.md §6),
 * 소유자 검증 예외 분기(§5).
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock DocumentRepository documentRepository;
    @Mock DocumentResultRepository documentResultRepository;
    @Mock AnalysisSummaryBuilder analysisSummaryBuilder;
    @Mock ChatbotLambdaClient chatbotLambdaClient;
    @Mock ChatStreamListener listener;

    @InjectMocks ChatServiceImpl chatService;

    private static final String DOC_ID = "doc-public-id";
    private static final String USER_ID = "user-public-id";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(chatService, "source", "development");
        ReflectionTestUtils.setField(chatService, "environment", "dev");
    }

    @Test
    @DisplayName("첫 턴(session_id 없음)이면 document_results에서 요약을 추출해 페이로드에 싣는다")
    void 첫_턴_요약_첨부() {
        DocumentResult result = mock(DocumentResult.class);
        given(documentResultRepository.findBySubmission_PublicId(DOC_ID)).willReturn(Optional.of(result));
        given(analysisSummaryBuilder.build(result)).willReturn("문서유형: 근로계약서. 종합 위험도: HIGH.");

        chatService.streamChat(DOC_ID, USER_ID, new ChatRequest("질문", null, "ko"), listener);

        ChatbotPayload payload = capturePayload();
        assertThat(payload.analysisSummary()).isEqualTo("문서유형: 근로계약서. 종합 위험도: HIGH.");
        assertThat(payload.sessionId()).isNotBlank();   // 새 UUID 발급
        assertThat(payload.documentPublicId()).isEqualTo(DOC_ID);
        assertThat(payload.userPublicId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("이어가는 턴(session_id 있음)이면 요약을 싣지 않고 MySQL도 보지 않는다")
    void 이어가는_턴_요약_생략() {
        chatService.streamChat(DOC_ID, USER_ID, new ChatRequest("후속 질문", "existing-session", "ko"), listener);

        ChatbotPayload payload = capturePayload();
        assertThat(payload.analysisSummary()).isNull();
        assertThat(payload.sessionId()).isEqualTo("existing-session");
        verifyNoInteractions(documentResultRepository, analysisSummaryBuilder);
    }

    @Test
    @DisplayName("첫 턴인데 분석 결과가 없으면(미완료 등) 요약 null로 진행 — 챗봇은 일반 응대")
    void 첫_턴_결과_없으면_null_요약() {
        given(documentResultRepository.findBySubmission_PublicId(DOC_ID)).willReturn(Optional.empty());

        chatService.streamChat(DOC_ID, USER_ID, new ChatRequest("질문", "", "ko"), listener);

        assertThat(capturePayload().analysisSummary()).isNull();
    }

    @Test
    @DisplayName("문서가 없으면 DOCUMENT4001, 소유자가 다르면 COMMON4031")
    void 소유자_검증() {
        given(documentRepository.findByPublicId("missing")).willReturn(Optional.empty());
        assertThatThrownBy(() -> chatService.verifyOwnership("missing", USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("존재하지 않는 문서");

        Document doc = mock(Document.class);
        given(doc.getUserPublicId()).willReturn("someone-else");
        given(documentRepository.findByPublicId(DOC_ID)).willReturn(Optional.of(doc));
        assertThatThrownBy(() -> chatService.verifyOwnership(DOC_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("접근 권한");
    }

    @Test
    @DisplayName("이력 조회는 Lambda 릴레이만 한다 — DynamoDB 직접 접근 없음")
    void 이력_조회_릴레이() {
        ChatHistoryResponse expected = new ChatHistoryResponse(
                List.of(new ChatHistoryResponse.ChatHistoryMessage(
                        "user", "질문", "2026-06-06T09:00:00Z")), null);
        given(chatbotLambdaClient.fetchHistory(USER_ID, DOC_ID, 50, null)).willReturn(expected);

        ChatHistoryResponse actual = chatService.getHistory(DOC_ID, USER_ID, 50, null);

        assertThat(actual).isSameAs(expected);
        verifyNoInteractions(documentResultRepository, analysisSummaryBuilder);
    }

    private ChatbotPayload capturePayload() {
        ArgumentCaptor<ChatbotPayload> captor = ArgumentCaptor.forClass(ChatbotPayload.class);
        verify(chatbotLambdaClient).streamChat(captor.capture(), any());
        return captor.getValue();
    }
}
