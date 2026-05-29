package com.gb.document.domain.chat.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.document.domain.chat.dto.request.ChatbotPayload;
import com.gb.document.domain.chat.service.ChatStreamListener;
import com.gb.document.domain.chat.service.ChatbotLambdaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@link ChatbotLambdaClient} 구현.
 *
 * <p>흐름:
 * <ol>
 *   <li>페이로드를 JSON 직렬화(전역 SNAKE_CASE 적용)</li>
 *   <li>localhost가 아니면 AWS SDK v2 {@link Aws4Signer}로 SigV4 서명 (signing name = "lambda")</li>
 *   <li>Java 17 {@link HttpClient}로 POST, {@code BodyHandlers.ofInputStream()}으로 스트림 수신</li>
 *   <li>SSE 프레임을 라인 단위로 파싱해 {@code event:token} → onToken, {@code event:done} → onDone</li>
 * </ol>
 *
 * <p>R1 PoC 단계에서는 더미 Lambda(poc/r1-stream/index.mjs) 또는 로컬 모킹 서버
 * (poc/r1-stream/local-mock-server.py)와 연동해 토큰 점진 표시 동작만 검증한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotLambdaClientImpl implements ChatbotLambdaClient {

    private final ObjectMapper objectMapper;

    @Value("${chatbot.function-url}")
    private String functionUrl;

    @Value("${chatbot.aws-region:ap-northeast-2}")
    private String awsRegion;

    /** false로 두면 localhost 여부와 무관하게 서명 스킵. 운영에선 반드시 true. */
    @Value("${chatbot.auth-enabled:true}")
    private boolean authEnabled;

    /** HTTP 요청 전체 타임아웃(스트림 종료까지). 챗봇 답변 길이를 감안해 넉넉히. */
    @Value("${chatbot.request-timeout-seconds:120}")
    private long requestTimeoutSeconds;

    // Spring 빈으로 등록되는 동안 재사용. HttpClient는 thread-safe.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public void streamChat(ChatbotPayload payload, ChatStreamListener listener) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            HttpRequest request = buildRequest(body);

            HttpResponse<InputStream> response = http.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() / 100 != 2) {
                String errMsg = readErrorBody(response.body());
                listener.onError(new IllegalStateException(
                        "Chatbot Lambda HTTP " + response.statusCode() + " — " + errMsg));
                return;
            }

            parseSseStream(response.body(), listener);
        } catch (Exception e) {
            log.warn("streamChat failed", e);
            listener.onError(e);
        }
    }

    /* ─────────── 요청 빌드 + 서명 ─────────── */

    private HttpRequest buildRequest(byte[] body) {
        URI uri = URI.create(functionUrl);
        boolean local = isLocalHost(uri.getHost());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        if (!authEnabled || local) {
            // 로컬 모킹: 서명 없이 전송 (poc/r1-stream/local-mock-server.py 검증용).
            log.debug("Function URL signing skipped (host={}, authEnabled={})",
                    uri.getHost(), authEnabled);
            return builder.build();
        }

        applySigV4(builder, uri, body);
        return builder.build();
    }

    /**
     * AWS SDK v2 Aws4Signer로 SigV4 서명 → 결과 헤더를 HttpRequest에 복사.
     *
     * <p>주의:
     * <ul>
     *   <li>signing name은 {@code "lambda"} (Function URL은 Lambda 서비스의 자원)</li>
     *   <li>Java {@link HttpClient}는 일부 헤더(Host, Content-Length 등) 직접 설정을 금지하므로 필터링</li>
     *   <li>Host는 URI에서 자동 설정되며 SigV4가 서명한 값과 동일하므로 문제 없음</li>
     * </ul>
     */
    private void applySigV4(HttpRequest.Builder builder, URI uri, byte[] body) {
        // ContentStreamProvider는 람다 폼이 모든 SDK v2 버전 호환(static factory 부재 시에도 안전).
        SdkHttpFullRequest unsigned = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(uri)
                .putHeader("Content-Type", "application/json")
                .contentStreamProvider(() -> new ByteArrayInputStream(body))
                .build();

        Aws4SignerParams params = Aws4SignerParams.builder()
                .awsCredentials(DefaultCredentialsProvider.create().resolveCredentials())
                .signingName("lambda")
                .signingRegion(Region.of(awsRegion))
                .build();

        SdkHttpFullRequest signed = Aws4Signer.create().sign(unsigned, params);
        Map<String, List<String>> headers = signed.headers();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (isRestrictedByJavaHttpClient(name)) continue;
            for (String value : entry.getValue()) {
                builder.header(name, value);
            }
        }
    }

    private static boolean isLocalHost(String host) {
        if (host == null) return false;
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }

    /** Java HttpClient가 setHeader/header()로 설정을 금지하는 헤더 목록. */
    private static boolean isRestrictedByJavaHttpClient(String name) {
        String lower = name.toLowerCase();
        return lower.equals("host")
                || lower.equals("content-length")
                || lower.equals("connection")
                || lower.equals("expect")
                || lower.equals("upgrade");
    }

    /* ─────────── SSE 파서 ─────────── */

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

    private String readErrorBody(InputStream is) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int max = 2000;
            while ((line = reader.readLine()) != null && sb.length() < max) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "(no body)";
        }
    }
}
