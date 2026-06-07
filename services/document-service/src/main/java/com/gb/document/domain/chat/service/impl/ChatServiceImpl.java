package com.gb.document.domain.chat.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.document.domain.chat.dto.request.ChatRequest;
import com.gb.document.domain.chat.dto.request.ChatbotPayload;
import com.gb.document.domain.chat.dto.response.ChatHistoryResponse;
import com.gb.document.domain.chat.service.AnalysisSummaryBuilder;
import com.gb.document.domain.chat.service.ChatService;
import com.gb.document.domain.chat.service.ChatStreamListener;
import com.gb.document.domain.chat.service.ChatbotLambdaClient;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import com.gb.document.global.exception.code.DocumentErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@link ChatService} 구현. ai-chatbot-mcp.md §5/§6 흐름 그대로:
 * <ol>
 *   <li>{@link #verifyOwnership}: DocumentRepository.findByPublicId → 없으면 DOCUMENT4001,
 *       userPublicId 불일치면 COMMON4031.</li>
 *   <li>{@link #streamChat}: sessionId 발급 → 첫 대화면 document_results에서 analysis_summary 추출
 *       ({@link AnalysisSummaryBuilder}) → 페이로드 조립 → {@link ChatbotLambdaClient#streamChat}로
 *       토큰 흐름 위임.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatServiceImpl implements ChatService {

    private final DocumentRepository documentRepository;
    private final DocumentResultRepository documentResultRepository;
    private final AnalysisSummaryBuilder analysisSummaryBuilder;
    private final ChatbotLambdaClient chatbotLambdaClient;

    /** source: development | production. 페이로드 메타로 함께 전송(ai-chatbot-mcp §6 주석). */
    @Value("${chatbot.source:development}")
    private String source;

    /** environment: dev | stage | prod. MCP URL 라우팅에 사용된다(Lambda 측). */
    @Value("${chatbot.environment:dev}")
    private String environment;

    @Override
    public void verifyOwnership(String documentPublicId, String userPublicId) {
        Document doc = documentRepository.findByPublicId(documentPublicId)
                .orElseThrow(() -> new BusinessException(DocumentErrorCode.DOCUMENT_NOT_FOUND));
        if (!doc.getUserPublicId().equals(userPublicId)) {
            log.warn("Forbidden chat access — documentPublicId={} requester={} owner={}",
                    documentPublicId, userPublicId, doc.getUserPublicId());
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    @Override
    public void streamChat(String documentPublicId, String userPublicId,
                           ChatRequest request, ChatStreamListener listener) {
        // sessionId가 비어 있으면 새로 발급 → 클라이언트 응답 done 이벤트에 실어 보냄.
        boolean isFirstTurn = (request.sessionId() == null || request.sessionId().isBlank());
        String sessionId = isFirstTurn ? UUID.randomUUID().toString() : request.sessionId();

        // 첫 대화에만 analysis_summary 첨부(ai-chatbot-mcp §3-2). 이후 턴은 messages 맥락에 묻어 따라감.
        // "첫 턴" 최종 판정 권한은 Lambda에 있다(§6) — DynamoDB에 기존 스레드가 있으면 Lambda가 요약을
        // 무시하므로, 여기서는 session_id 부재 시 첨부만 한다(재방문 시 중복 주입 없음 — 멱등).
        String analysisSummary = isFirstTurn ? buildAnalysisSummary(documentPublicId) : null;

        ChatbotPayload payload = ChatbotPayload.builder()
                .message(request.message())
                .sessionId(sessionId)
                .userLang(request.userLang())
                .documentPublicId(documentPublicId)
                .userPublicId(userPublicId)
                .source(source)
                .environment(environment)
                .analysisSummary(analysisSummary)
                .build();

        chatbotLambdaClient.streamChat(payload, listener);
    }

    @Override
    public ChatHistoryResponse getHistory(String documentPublicId, String userPublicId,
                                          int limit, String cursor) {
        // 백엔드는 검증·중계만 — 이력 데이터 접근은 Lambda(계정 B DynamoDB) 소관(ai-chatbot-mcp §6-2).
        return chatbotLambdaClient.fetchHistory(userPublicId, documentPublicId, limit, cursor);
    }

    /**
     * document_results에서 압축 요약 추출(ai-chatbot-mcp.md §6 — MySQL 접근은 세션당 이 1회뿐).
     * 결과가 없거나 미완료(COMPLETED 아님)면 null — 페이로드에서 생략되고 챗봇은 일반 응대한다.
     */
    private String buildAnalysisSummary(String documentPublicId) {
        return documentResultRepository.findBySubmission_PublicId(documentPublicId)
                .map(analysisSummaryBuilder::build)
                .orElse(null);
    }
}
