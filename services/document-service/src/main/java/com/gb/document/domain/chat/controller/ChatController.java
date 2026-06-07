package com.gb.document.domain.chat.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.document.domain.chat.dto.request.ChatRequest;
import com.gb.document.domain.chat.dto.response.ChatHistoryResponse;
import com.gb.document.domain.chat.service.ChatService;
import com.gb.document.domain.chat.util.SseRelayListener;
import com.gb.document.global.security.CurrentUserPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

/**
 * 후속 질문 챗봇 컨트롤러 — ai-chatbot-mcp.md §6 본 엔드포인트.
 *
 * <pre>POST /api/v1/documents/{publicId}/chat</pre>
 *
 * <h3>흐름</h3>
 * <ol>
 *   <li>{@link ChatService#verifyOwnership}을 <b>동기</b>로 호출 — 실패 시 BusinessException이
 *       GlobalExceptionHandler에 잡혀 HTTP 4xx + 공통 에러 envelope으로 응답된다(404 DOCUMENT4001,
 *       403 COMMON4031). SSE 시작 전이라 HTTP 상태 코드를 정상적으로 줄 수 있다.</li>
 *   <li>권한 통과 후 {@link SseEmitter} 생성. Lambda 호출은 블로킹이라 별도 스레드에서 실행.</li>
 *   <li>{@link SseRelayListener}가 Lambda 토큰을 SSE 이벤트(token/done)로 변환해 흘림.</li>
 * </ol>
 *
 * <h3>인증</h3>
 * OAuth2 Resource Server(방식 B) — 본인 식별자는 JWT custom claim {@code public_id}를
 * {@code @CurrentUserPublicId}로 추출한다(member/wallet/community와 동일, CLAUDE.md §9).
 */
@Tag(name = "Document Chat", description = "분석 결과 후속 질문 챗봇 (SSE 스트리밍)")
@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class ChatController {

    // 응답별 ErrorResponse 예시 — DocumentController와 동일 코드/메시지(권한·인증은 공통 코드 재사용).
    private static final String EX_DOCUMENT4001 =
            "{\"success\":false,\"code\":\"DOCUMENT4001\",\"message\":\"존재하지 않는 문서입니다.\"}";
    private static final String EX_COMMON4031 =
            "{\"success\":false,\"code\":\"COMMON4031\",\"message\":\"접근 권한이 없습니다.\"}";
    private static final String EX_AUTH4011 =
            "{\"success\":false,\"code\":\"AUTH4011\",\"message\":\"인증이 필요합니다.\"}";

    private final ChatService chatService;

    @Operation(
            summary = "분석 결과 후속 질문 챗봇 (SSE 스트리밍)",
            description = "분석이 끝난 문서에 대한 사용자의 후속 질문을 받아 답변을 SSE로 스트리밍한다. "
                    + "본문 처리(법령 KB · 환율/커뮤니티 MCP · 웹 검색)는 계정 B 챗봇 Lambda가 담당하고, 본체는 "
                    + "① 본인 문서 권한 검증 ② 첫 대화면 분석요약 추출 ③ Lambda 호출 ④ 토큰 SSE 중계만 한다. "
                    + "권한 검증은 SSE 시작 전 동기로 수행되어 실패 시 정상 HTTP 4xx 에러 envelope으로 응답한다. "
                    + "응답 본문은 text/event-stream — `event: token`(부분 토큰)이 반복된 뒤 `event: done`으로 종료된다. "
                    + "사용자는 인증된 JWT의 public_id claim으로 식별한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "스트리밍 시작. Content-Type: text/event-stream. event: token(부분 토큰) 반복 후 event: done.",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(type = "string",
                                    example = "event: token\ndata: 법령을\n\nevent: token\ndata: 확인했습니다\n\nevent: done\ndata: \n\n"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 요청 본문 검증 실패(message/user_lang 누락).",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다(토큰 누락·만료·위조).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "COMMON4031 - 다른 사용자의 문서.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4031", value = EX_COMMON4031))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "DOCUMENT4001 - 존재하지 않는 문서.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "DOCUMENT4001", value = EX_DOCUMENT4001)))
    })
    @PostMapping(value = "/{publicId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @Parameter(description = "분석 문서 식별자(UUID). dev 시드 완료문서 ...0001로 테스트(COMPLETED라야 챗봇 200)",
                    example = "00000000-0000-0000-0000-000000000001")
            @PathVariable("publicId") String documentPublicId,
            @CurrentUserPublicId String userPublicId,
            @Valid @RequestBody ChatRequest request
    ) {
        log.info("[chat] documentPublicId={} userPublicId={} firstTurn={}",
                documentPublicId, userPublicId, request.sessionId() == null);

        // 1) 권한 검증 — 동기. 실패 시 throw → GlobalExceptionHandler가 HTTP 4xx 응답.
        chatService.verifyOwnership(documentPublicId, userPublicId);

        // 2) SseEmitter 생성 + 콜백.
        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onCompletion(() -> log.debug("[chat] emitter completed"));
        emitter.onTimeout(() -> log.warn("[chat] emitter timeout"));
        emitter.onError(e -> log.warn("[chat] emitter error", e));

        // 3) Lambda 호출은 블로킹이라 별도 스레드. SseRelayListener가 토큰을 SSE 이벤트로 중계.
        CompletableFuture.runAsync(() -> {
            try {
                chatService.streamChat(documentPublicId, userPublicId, request,
                        new SseRelayListener(emitter));
            } catch (Exception e) {
                // verifyOwnership은 이미 통과한 상태라 여기 오는 예외는 페이로드 조립/Lambda 호출 단계의 시스템 오류.
                log.warn("[chat] streamChat failed", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @Operation(
            summary = "챗봇 대화 이력 조회 (재방문 복원)",
            description = "해당 문서에 대한 이전 챗봇 대화를 시간 오름차순으로 반환한다. 결과 화면 채팅 영역 "
                    + "마운트 시 1회 호출해 이전 대화를 복원(시드)하는 용도. 이력 데이터는 계정 B DynamoDB에 "
                    + "있으며 본체는 권한 검증 후 챗봇 Lambda /history로 릴레이만 한다(ai-chatbot-mcp.md §6-2). "
                    + "첫 턴에 주입된 분석요약 합성 턴은 내려오지 않는다. 이력이 없어도 본인 문서면 200 + 빈 배열.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공(이력 없으면 messages 빈 배열)."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다(토큰 누락·만료·위조).",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "AUTH4011", value = EX_AUTH4011))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "COMMON4031 - 다른 사용자의 문서.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "COMMON4031", value = EX_COMMON4031))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "DOCUMENT4001 - 존재하지 않는 문서.",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(name = "DOCUMENT4001", value = EX_DOCUMENT4001)))
    })
    @GetMapping("/{publicId}/chat/history")
    public ApiResponse<ChatHistoryResponse> history(
            @Parameter(description = "분석 문서 식별자(UUID)",
                    example = "00000000-0000-0000-0000-000000000001")
            @PathVariable("publicId") String documentPublicId,
            @CurrentUserPublicId String userPublicId,
            @Parameter(description = "1회 조회 메시지 수(기본 50, 최대 100 — 초과는 Lambda가 클램프)")
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @Parameter(description = "다음 페이지 커서(이전 응답의 next_cursor). 첫 조회 시 생략")
            @RequestParam(name = "cursor", required = false) String cursor
    ) {
        chatService.verifyOwnership(documentPublicId, userPublicId);
        return ApiResponse.success(chatService.getHistory(documentPublicId, userPublicId, limit, cursor));
    }
}
