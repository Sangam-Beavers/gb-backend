package com.gb.document.domain.chat.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.document.domain.chat.dto.request.ChatbotPayload;
import com.gb.document.domain.chat.dto.response.ChatHistoryResponse;
import com.gb.document.domain.chat.service.ChatStreamListener;
import com.gb.document.domain.chat.service.ChatbotLambdaClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaAsyncClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.InvokeResponseStreamUpdate;
import software.amazon.awssdk.services.lambda.model.InvokeWithResponseStreamCompleteEvent;
import software.amazon.awssdk.services.lambda.model.InvokeWithResponseStreamRequest;
import software.amazon.awssdk.services.lambda.model.InvokeWithResponseStreamResponseHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * {@link ChatbotLambdaClient} 구현 — 챗봇 Lambda(계정 B, gb-chatbot)를 <b>표준 Lambda Invoke API</b>로 호출한다.
 *
 * <p><b>왜 Function URL이 아니라 Invoke API인가:</b> EKS 파드에서 Lambda Function URL(`*.lambda-url...on.aws`)을
 * IAM(SigV4)으로 호출하면 인가가 거부(403)되는 환경 이슈가 확인됐다(2026-06-13 인시던트). 같은 역할·같은 파드로
 * 표준 Lambda Invoke API는 정상이라 전송을 전환한다. <b>Lambda 코드(FastAPI + Lambda Web Adapter)는 무수정</b> —
 * 핸들러가 기대하는 Function URL v2 이벤트 형태로 페이로드를 감싸 invoke하면, LWA가 그대로 HTTP로 해석한다
 * (파드에서 invokeWithResponseStream으로 토큰 스트리밍 동작 검증 완료).
 *
 * <p>흐름(스트리밍):
 * <ol>
 *   <li>{@link ChatbotPayload}를 JSON 직렬화(전역 SNAKE_CASE) → Function URL v2 이벤트의 {@code body}로 래핑</li>
 *   <li>{@link LambdaAsyncClient#invokeWithResponseStream}로 호출(IAM = lambda:InvokeFunction). 응답 청크를
 *       블로킹 {@link InputStream}으로 브리지</li>
 *   <li>스트림 맨 앞 <b>prelude</b>({@code {"statusCode":200,...}} + null 8바이트 구분자)를 스킵 —
 *       RESPONSE_STREAM(LWA) 응답이 직접 invoke로 올 때만 붙는 메타 헤더</li>
 *   <li>이후 바이트를 기존 {@link #parseSseStream} 그대로 흘려 {@code event:token} → onToken,
 *       {@code event:done} → onDone, {@code event:error} → onError</li>
 * </ol>
 * 이력 조회는 {@link LambdaClient#invoke}(동기)로 GET /history 이벤트를 보내 {@code {statusCode, body}} 봉투에서 파싱.
 *
 * <p>자격 증명은 {@link DefaultCredentialsProvider} — EKS Pod의 Pod Identity/IRSA 자격을 자동 사용.
 */
@Slf4j
@Component
public class ChatbotLambdaClientImpl implements ChatbotLambdaClient {

    private final ObjectMapper objectMapper;
    private final String functionName;
    private final LambdaAsyncClient lambdaAsyncClient;
    private final LambdaClient lambdaClient;

    public ChatbotLambdaClientImpl(
            ObjectMapper objectMapper,
            @Value("${chatbot.function-name:gb-chatbot}") String functionName,
            @Value("${chatbot.aws-region:ap-northeast-2}") String awsRegion,
            @Value("${chatbot.request-timeout-seconds:120}") long requestTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.functionName = functionName;
        Region region = Region.of(awsRegion);
        // 스트리밍은 비동기 HTTP 구현체(netty) 필수. 챗봇 답변 길이를 감안해 read/write 타임아웃을 넉넉히.
        this.lambdaAsyncClient = LambdaAsyncClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClient(NettyNioAsyncHttpClient.builder()
                        .readTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                        .writeTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                        .build())
                .build();
        // 이력(GET /history)은 동기 — 동기 HTTP 구현체는 spring-cloud-aws가 클래스패스에 제공.
        this.lambdaClient = LambdaClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Override
    public void streamChat(ChatbotPayload payload, ChatStreamListener listener) {
        ResponseStreamBridge bridge = new ResponseStreamBridge();
        try {
            byte[] event = wrapFunctionUrlEvent("POST", "/", null, objectMapper.writeValueAsBytes(payload));
            InvokeWithResponseStreamRequest request = InvokeWithResponseStreamRequest.builder()
                    .functionName(functionName)
                    .payload(SdkBytes.fromByteArray(event))
                    .build();

            InvokeWithResponseStreamResponseHandler handler = InvokeWithResponseStreamResponseHandler.builder()
                    .subscriber(streamEvent -> {
                        // 타입은 subscriber(Consumer<T>)의 T로 추론된다(베이스 타입을 직접 명명하지 않음).
                        if (streamEvent instanceof InvokeResponseStreamUpdate update) {
                            bridge.offer(update.payload().asByteArray());
                        } else if (streamEvent instanceof InvokeWithResponseStreamCompleteEvent) {
                            bridge.complete();
                        }
                    })
                    .onError(bridge::fail)
                    .build();

            // 비동기 호출 시작 — 청크는 위 subscriber가 bridge로 흘린다(완료/에러도 bridge가 read 시점에 전달).
            lambdaAsyncClient.invokeWithResponseStream(request, handler);

            InputStream in = bridge.inputStream();
            skipPrelude(in);                  // {statusCode,...} + null*8 제거 → 스트림이 event: 로 시작
            parseSseStream(in, listener);     // 기존 SSE 파서 그대로 재사용
        } catch (Exception e) {
            log.warn("streamChat failed", e);
            listener.onError(e);
        }
    }

    @Override
    public ChatHistoryResponse fetchHistory(String userPublicId, String documentPublicId,
                                            int limit, String cursor) {
        try {
            StringBuilder qs = new StringBuilder()
                    .append("user_public_id=").append(enc(userPublicId))
                    .append("&document_public_id=").append(enc(documentPublicId))
                    .append("&limit=").append(limit);
            if (cursor != null && !cursor.isBlank()) {
                qs.append("&cursor=").append(enc(cursor));
            }
            byte[] event = wrapFunctionUrlEvent("GET", "/history", qs.toString(), null);

            InvokeResponse response = lambdaClient.invoke(InvokeRequest.builder()
                    .functionName(functionName)
                    .payload(SdkBytes.fromByteArray(event))
                    .build());
            if (response.functionError() != null) {
                throw new IllegalStateException("Chatbot Lambda history functionError: " + response.functionError());
            }

            JsonNode root = objectMapper.readTree(response.payload().asUtf8String());
            int statusCode = root.path("statusCode").asInt(0);
            String body = root.path("body").isMissingNode() ? null : root.path("body").asText(null);
            if (statusCode != 200 || body == null) {
                throw new IllegalStateException(
                        "Chatbot Lambda history HTTP " + statusCode + " — " + truncateForLog(body));
            }
            return objectMapper.readValue(body, ChatHistoryResponse.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Chatbot Lambda history call failed", e);
        }
    }

    /* ─────────── invoke 페이로드(Function URL v2 이벤트) 래핑 ─────────── */

    /**
     * 백엔드 요청을 Function URL v2 이벤트로 감싼다. Lambda(LWA/FastAPI)가 이를 HTTP 요청으로 해석하므로
     * <b>Lambda 코드 무수정</b>으로 직접 invoke와 호환된다.
     *
     * @param method         GET/POST
     * @param path           rawPath (예: "/", "/history")
     * @param rawQueryString null 가능 (GET 쿼리)
     * @param body           null 가능 (POST 본문 바이트)
     */
    private byte[] wrapFunctionUrlEvent(String method, String path, String rawQueryString, byte[] body)
            throws IOException {
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", method);
        http.put("path", path);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", "2.0");
        event.put("rawPath", path);
        event.put("rawQueryString", rawQueryString != null ? rawQueryString : "");
        event.put("requestContext", Map.of("http", http));
        event.put("headers", Map.of("content-type", "application/json"));
        if (body != null) {
            event.put("body", new String(body, StandardCharsets.UTF_8));
            event.put("isBase64Encoded", false);
        }
        return objectMapper.writeValueAsBytes(event);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String truncateForLog(String body) {
        if (body == null) return "(no body)";
        return body.length() > 2000 ? body.substring(0, 2000) : body;
    }

    /* ─────────── 응답 스트림 브리지 (비동기 청크 → 블로킹 InputStream) ─────────── */

    /** EOF 신호용 센티넬(빈 배열, 참조 동등으로 구분). */
    private static final byte[] SENTINEL = new byte[0];

    /**
     * invokeWithResponseStream의 비동기 청크를 블로킹 {@link InputStream}으로 잇는 브리지.
     * SDK 콜백 스레드가 {@link #offer}/{@link #complete}/{@link #fail}로 채우고,
     * 호출 스레드가 {@link #inputStream}을 읽는다. 전송 에러는 read 시점에 {@link IOException}으로 표면화된다.
     */
    private static final class ResponseStreamBridge {
        private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private volatile Throwable error;

        void offer(byte[] bytes) {
            if (bytes != null && bytes.length > 0) {
                queue.add(bytes);
            }
        }

        void complete() {
            queue.add(SENTINEL);
        }

        void fail(Throwable t) {
            this.error = t;
            queue.add(SENTINEL);
        }

        InputStream inputStream() {
            return new InputStream() {
                private byte[] cur;
                private int pos;
                private boolean done;

                @Override
                public int read() throws IOException {
                    if (!ensure()) return -1;
                    return cur[pos++] & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (!ensure()) return -1;
                    int n = Math.min(len, cur.length - pos);
                    System.arraycopy(cur, pos, b, off, n);
                    pos += n;
                    return n;
                }

                private boolean ensure() throws IOException {
                    while (cur == null || pos >= cur.length) {
                        if (done) return false;
                        byte[] next;
                        try {
                            next = queue.take();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("interrupted while reading chatbot stream", e);
                        }
                        if (next == SENTINEL) {
                            done = true;
                            if (error != null) {
                                throw new IOException("chatbot stream transport error", error);
                            }
                            return false;
                        }
                        cur = next;
                        pos = 0;
                    }
                    return true;
                }
            };
        }
    }

    /**
     * Lambda Response Streaming(LWA)을 직접 invokeWithResponseStream으로 받을 때 본문 앞에 붙는 prelude를 스킵.
     * 형식: {@code {"statusCode":200,"headers":{...},"cookies":[]}} + null 바이트 8개 구분자 + 실제 본문(SSE).
     * SSE 본문에는 null 바이트가 없으므로 "최초의 연속 null 8바이트"까지 읽어 버리면 스트림이 {@code event:} 로 시작한다.
     */
    private static void skipPrelude(InputStream in) throws IOException {
        int nullRun = 0;
        int b;
        while ((b = in.read()) != -1) {
            if (b == 0) {
                if (++nullRun == 8) {
                    return;
                }
            } else {
                nullRun = 0;
            }
        }
        // 구분자 없이 EOF — 본문 없음(정상 응답이면 발생하지 않음). parseSseStream이 onDone("")로 마무리.
    }

    /* ─────────── SSE 파서 (원본 유지) ─────────── */

    /**
     * SSE 스펙 단순 구현:
     * <pre>
     *   event: token
     *   data: 안녕
     *   (빈 줄 = 프레임 종료)
     * </pre>
     * data가 여러 줄이면 \n으로 결합한다. id:/retry:/주석은 PoC에서 무시.
     */
    private void parseSseStream(InputStream body, ChatStreamListener listener) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {

            String currentEvent = "message";
            StringBuilder data = new StringBuilder();
            boolean sawDone = false;
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // 프레임 종료 → 디스패치
                    if (data.length() > 0 || !"message".equals(currentEvent)) {
                        if ("done".equals(currentEvent)) {
                            listener.onDone(extractSessionId(data.toString()));
                            sawDone = true;
                            return; // done 받으면 즉시 종료(서버가 더 보낼 것 없음)
                        }
                        if ("token".equals(currentEvent)) {
                            listener.onToken(data.toString());
                        }
                        if ("error".equals(currentEvent)) {
                            // Lambda가 예외 시 event:error 한 번 흘리고 종료(app.py sse_stream).
                            // 무시하면 EOF에서 onDone("")로 끝나 클라이언트엔 "빈 답변"으로 보인다 —
                            // 반드시 onError로 표면화한다.
                            listener.onError(new IllegalStateException(
                                    "Chatbot Lambda stream error — " + data));
                            return;
                        }
                    }
                    currentEvent = "message";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(stripLeadingSpace(line.substring(5)));
                }
                // id:, retry:, ":주석" 등은 무시
            }

            // 스트림이 done 없이 끝났을 때 — 안전하게 onDone(빈 sessionId)으로 마무리.
            if (!sawDone) listener.onDone("");
        }
    }

    private static String stripLeadingSpace(String s) {
        return (!s.isEmpty() && s.charAt(0) == ' ') ? s.substring(1) : s;
    }

    private String extractSessionId(String dataJson) {
        try {
            return objectMapper.readTree(dataJson).path("session_id").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
