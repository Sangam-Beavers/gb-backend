package com.gb.community.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

/**
 * Bedrock {@link TranslationClient} — 계정 B 번역 Lambda Function URL 호출.
 *
 * <p>활성화 조건: {@code translation.client=bedrock}.
 * dev에서도 활성화 가능 — {@code application-dev.yml}에 {@code translation.client: bedrock} 명시 + URL/region 주입.
 * 환경변수 {@code TRANSLATION_LAMBDA_URL} 누락 시 생성자에서 fail-fast.
 *
 * <p>호출 흐름:
 * <ol>
 *   <li>요청 페이로드를 JSON 직렬화 (계약 = {@code docs/community/translation.md} §4).</li>
 *   <li>AWS SDK SigV4로 IAM 서명 — Lambda Function URL의 {@code AuthType: AWS_IAM} 통과.</li>
 *   <li>JDK {@link HttpClient}로 POST 전송 — 별도 RestClient 빈 불요(SigV4 헤더가 본문 해시에 묶여 RestClient
 *       사용 시 헤더 추가/본문 변환에 민감).</li>
 *   <li>응답을 {@link TranslationResult}로 매핑.</li>
 * </ol>
 *
 * <p>실패 정책 = fail-fast: 4xx/5xx/timeout 모두 {@link RuntimeException}을 던진다. Service는 잡지 않아
 * GlobalExceptionHandler가 COMMON5000(500)으로 변환한다. 캐시 INSERT는 정상 응답 시에만 수행한다
 * ({@link MockTranslationClient}와 동일 시그니처).
 *
 * <p>자격 증명은 {@link DefaultCredentialsProvider} — EKS Pod의 IRSA(서비스 어카운트 역할) 자격을 자동으로
 * 사용한다. 로컬에서 stage 프로파일을 띄울 일은 없으므로 별도 시크릿 키 주입 경로를 두지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "translation.client", havingValue = "bedrock")
public class BedrockTranslationClient implements TranslationClient {

    /** Lambda Function URL의 SigV4 서명 대상 서비스명. Function URL은 {@code lambda} 서비스로 서명한다. */
    private static final String SERVICE_NAME = "lambda";

    /** 백엔드 측 호출 타임아웃 — Bedrock 단발 호출(~2초) 대비 여유. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String lambdaUrl;
    private final Region awsRegion;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Aws4Signer signer;
    private final DefaultCredentialsProvider credentialsProvider;

    public BedrockTranslationClient(
            @Value("${translation.lambda.url:}") String lambdaUrl,
            @Value("${translation.aws.region:ap-northeast-2}") String awsRegion,
            ObjectMapper objectMapper) {
        // 본 클라이언트는 stage·prod에서만 등록되므로 URL은 반드시 채워져 있어야 한다 — 빈 값이면 기동 시
        // fail-fast (RealMemberClient 패턴과 동일). 누락된 채로 첫 호출에서 터지면 운영 알림이 늦어진다.
        if (lambdaUrl == null || lambdaUrl.isBlank()) {
            throw new IllegalStateException(
                    "translation.lambda.url이 비어 있습니다 — TRANSLATION_LAMBDA_URL 환경변수를 설정하세요.");
        }
        this.lambdaUrl = lambdaUrl;
        this.awsRegion = Region.of(awsRegion);
        this.objectMapper = objectMapper;
        this.signer = Aws4Signer.create();
        this.credentialsProvider = DefaultCredentialsProvider.create();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public TranslationResult translate(
            String kind,
            String publicId,
            String title,
            String content,
            String sourceLang,
            String targetLang) {
        try {
            byte[] body = buildPayload(kind, publicId, title, content, sourceLang, targetLang);
            SdkHttpFullRequest signedRequest = signRequest(body);

            HttpRequest httpRequest = toHttpRequest(signedRequest, body);
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[BedrockTranslationClient] Lambda 호출 실패 status={} kind={} publicId={}",
                        response.statusCode(), kind, publicId);
                throw new RuntimeException("Translation Lambda returned " + response.statusCode());
            }

            LambdaResponse parsed = objectMapper.readValue(response.body(), LambdaResponse.class);
            if (parsed.translatedContent() == null) {
                throw new RuntimeException("Translation Lambda response missing translated_content");
            }
            return new TranslationResult(
                    parsed.translatedTitle(),
                    parsed.translatedContent(),
                    parsed.targetLang() != null ? parsed.targetLang() : targetLang,
                    parsed.modelId(),
                    parsed.inputTokens(),
                    parsed.outputTokens());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[BedrockTranslationClient] Lambda 호출 IO 실패 kind={} publicId={} msg={}",
                    kind, publicId, e.getMessage());
            throw new RuntimeException("Translation Lambda call failed", e);
        }
    }

    /** Lambda 요청 페이로드를 JSON 직렬화. snake_case로 명시(MockBankClient 어댑터 규칙). */
    private byte[] buildPayload(String kind, String publicId, String title, String content,
                                String sourceLang, String targetLang) throws IOException {
        // LinkedHashMap = 직렬화 순서 결정적(로그 가독성). title은 null이어도 키를 유지(comment 응답 계약).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        payload.put("public_id", publicId);
        payload.put("title", title);
        payload.put("content", content);
        payload.put("source_lang", sourceLang);
        payload.put("target_lang", targetLang);
        return objectMapper.writeValueAsBytes(payload);
    }

    /** AWS SDK SigV4로 요청 서명 — Lambda Function URL의 AWS_IAM 인증 통과. */
    private SdkHttpFullRequest signRequest(byte[] body) {
        URI uri = URI.create(lambdaUrl);
        SdkHttpFullRequest unsigned = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(uri)
                .putHeader("Content-Type", "application/json")
                .contentStreamProvider(() -> new java.io.ByteArrayInputStream(body))
                .build();

        Aws4SignerParams params = Aws4SignerParams.builder()
                .awsCredentials(credentialsProvider.resolveCredentials())
                .signingName(SERVICE_NAME)
                .signingRegion(awsRegion)
                .build();
        return signer.sign(unsigned, params);
    }

    /** 서명된 SDK 요청을 JDK HttpRequest로 변환 — 서명 헤더를 전부 그대로 옮긴다. */
    private HttpRequest toHttpRequest(SdkHttpFullRequest signed, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(signed.getUri().toString()))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        // JDK HttpClient는 일부 헤더(Host, Content-Length 등) 직접 설정을 금지하므로 필터링.
        signed.headers().forEach((name, values) -> {
            if (isRestrictedHeader(name)) {
                return;
            }
            for (String value : values) {
                builder.header(name, value);
            }
        });
        return builder.build();
    }

    private static boolean isRestrictedHeader(String name) {
        String lower = name.toLowerCase();
        return lower.equals("host") || lower.equals("content-length") || lower.equals("connection")
                || lower.equals("upgrade") || lower.equals("expect");
    }

    /** Lambda 응답 매핑. 알 수 없는 필드는 무시(역호환). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LambdaResponse(
            @JsonProperty("translated_title") String translatedTitle,
            @JsonProperty("translated_content") String translatedContent,
            @JsonProperty("target_lang") String targetLang,
            @JsonProperty("model_id") String modelId,
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens) {
    }

    /**
     * UTF-8 바이트 길이 — Content-Length 등 디버깅용. 현재는 호출 안 함(JDK HttpClient가 자동 설정).
     */
    @SuppressWarnings("unused")
    private static int bodyLength(byte[] body) {
        return body == null ? 0 : body.length;
    }

    @SuppressWarnings("unused")
    private static String utf8(byte[] data) {
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }
}
