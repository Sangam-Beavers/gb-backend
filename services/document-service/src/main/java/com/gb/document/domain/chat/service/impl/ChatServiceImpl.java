package com.gb.document.domain.chat.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.document.domain.chat.dto.request.ChatRequest;
import com.gb.document.domain.chat.dto.request.ChatbotPayload;
import com.gb.document.domain.chat.service.ChatService;
import com.gb.document.domain.chat.service.ChatStreamListener;
import com.gb.document.domain.chat.service.ChatbotLambdaClient;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.repository.DocumentRepository;
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
 *   <li>{@link #streamChat}: sessionId 발급 → 첫 대화면 analysis_summary 추출(임시 하드코딩) →
 *       페이로드 조립 → {@link ChatbotLambdaClient#streamChat}로 토큰 흐름 위임.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatServiceImpl implements ChatService {

    private final DocumentRepository documentRepository;
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

        // 첫 대화에만 analysis_summary 주입(ai-chatbot-mcp §3-2). 이후 턴은 messages 맥락에 묻어 따라감.
        // TODO Phase 4 — document_results 테이블에서 진짜 추출 + result-json-schema-agreement.md §6 포맷으로 조립.
        String analysisSummary = isFirstTurn ? buildDummySummary() : null;

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

    /**
     * 임시 분석 요약. Phase 4에서 MySQL document_results의 v1.0 스키마 값을 읽어 다음 포맷으로 조립한다:
     * "위험도 {overall_risk_level}. {top risk_items.description}. 임금: ... 문서유형: ...".
     * (result-json-schema-agreement.md §6)
     */
    private String buildDummySummary() {
        return "위험도 HIGH. 최저임금 미달(시급 9,620원 기준 미충족), 주 50시간 초과근무 조항 존재. "
             + "임금: 월 2,000,000원 / 시급 9,620원. 문서유형: 근로계약서.";
    }
}
