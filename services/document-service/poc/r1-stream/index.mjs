/**
 * R1 PoC — 더미 챗봇 Lambda (Function URL + Response Streaming)
 *
 * 목적: ai-chatbot-mcp.md §12 R1 검증. Spring SseEmitter ↔ Function URL 스트리밍 중계가
 *      실제로 토큰 단위로 흘러가는지만 확인하기 위한 더미 구현이다.
 *      진짜 챗봇 Lambda(Python + Bedrock Tool Use)는 Phase 4에서 따로 작성한다.
 *
 * 배포:
 *   1) Lambda 생성 (Node.js 20.x), 이 파일을 index.mjs로 업로드
 *   2) Function URL 활성화, InvokeMode = RESPONSE_STREAM, Auth type = AWS_IAM
 *   3) Spring 백엔드(계정 A 가정)의 IAM 역할에 lambda:InvokeFunctionUrl 허용
 *
 * 입력(POST body, application/json):
 *   { "message": "...", "session_id": "...", "user_lang": "ko",
 *     "document_public_id": "...", "user_public_id": "...",
 *     "source": "production", "environment": "prod", "analysis_summary": "..." }
 *
 * 출력(SSE): event: token / data: <text>   (마지막에) event: done / data: {"session_id":"..."}
 *           ai-chatbot-mcp.md §6 응답 포맷과 동일하게 맞췄음.
 */

export const handler = awslambda.streamifyResponse(async (event, responseStream, _context) => {
    // HTTP 응답 메타데이터: SSE 헤더 (text/event-stream + chunked).
    const metadata = {
        statusCode: 200,
        headers: {
            "Content-Type": "text/event-stream",
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    };
    responseStream = awslambda.HttpResponseStream.from(responseStream, metadata);

    // 페이로드 파싱 (Function URL은 event.body가 문자열).
    let payload = {};
    try {
        payload = event.body ? JSON.parse(event.body) : {};
    } catch (e) {
        // 파싱 실패해도 PoC는 그냥 진행.
    }
    const sessionId = payload.session_id || crypto.randomUUID();

    // 더미 토큰 시퀀스 — 진짜 Bedrock converse_stream 흐름을 모방.
    // 한 글자/한 단어 단위 토큰 점진 표시가 SSE로 흘러가는지 확인용.
    const tokens = [
        "안녕", "하세요", ". ",
        "분석", "된 ", "계약서", "를 ", "확인", "했어요", ". ",
        "추가로 ", "궁금한 ", "점을 ", "물어보세요", "."
    ];

    for (const token of tokens) {
        // SSE 프레임: "event: token\ndata: <text>\n\n"
        // data 안에 개행이 있으면 안 되므로 \n은 \\n으로 이스케이프.
        const escaped = token.replace(/\n/g, "\\n");
        responseStream.write(`event: token\ndata: ${escaped}\n\n`);
        await new Promise((r) => setTimeout(r, 300));
    }

    // 완료 이벤트 — Spring 쪽에서 SseEmitter.complete() 트리거.
    responseStream.write(`event: done\ndata: ${JSON.stringify({ session_id: sessionId })}\n\n`);
    responseStream.end();
});
