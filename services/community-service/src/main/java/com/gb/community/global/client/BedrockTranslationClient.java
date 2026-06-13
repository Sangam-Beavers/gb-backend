package com.gb.community.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Bedrock {@link TranslationClient} — 계정 B 번역 Lambda(gb-community-translator)를
 * <b>표준 Lambda Invoke API</b>로 동기 호출한다.
 *
 * <p>활성화 조건: {@code translation.client=bedrock}.
 * dev에서도 활성화 가능 — {@code application-dev.yml}에 {@code translation.client: bedrock} 명시 + 함수명/region 주입.
 * 환경변수 {@code TRANSLATION_LAMBDA_FUNCTION_NAME}(기본 {@code gb-community-translator}) 사용.
 *
 * <p><b>왜 Function URL이 아니라 Invoke API인가:</b> EKS 파드에서 Lambda Function URL(`*.lambda-url...on.aws`)을
 * IAM(SigV4)으로 호출하면 인가가 거부(403)되는 환경 이슈가 확인됐다(2026-06-13 인시던트 — 권한·서명·프록시 모두 정상,
 * EKS 내부에서 Function URL 공개 엔드포인트 경로만 거부). 같은 역할·같은 파드로 표준 Lambda Invoke API는 정상
 * 동작하므로, 백엔드↔Lambda 호출을 Invoke API로 전환한다. <b>Lambda 코드는 무수정</b> — 핸들러가 기대하는
 * Function URL 이벤트 형태로 페이로드를 감싸 invoke한다(아래 {@link #buildInvokePayload}).
 *
 * <p>호출 흐름:
 * <ol>
 *   <li>요청 페이로드를 JSON 직렬화 (계약 = {@code docs/community/translation.md} §4).</li>
 *   <li>Function URL v2 이벤트({@code {version, requestContext, body}})로 래핑 — 핸들러가 {@code event["body"]}를 파싱.</li>
 *   <li>{@link LambdaClient#invoke}로 동기 호출 (IAM 인증 = 표준 SigV4, {@code lambda:InvokeFunction}).</li>
 *   <li>응답 봉투 {@code {statusCode, body}}에서 body를 꺼내 {@link TranslationResult}로 매핑.</li>
 * </ol>
 *
 * <p>실패 정책 = fail-fast: functionError·비200·파싱 실패 모두 {@link RuntimeException}을 던진다. Service는 잡지 않아
 * GlobalExceptionHandler가 COMMON5000(500)으로 변환한다. 캐시 INSERT는 정상 응답 시에만 수행한다
 * ({@link MockTranslationClient}와 동일 시그니처).
 *
 * <p>자격 증명은 기본적으로 {@link DefaultCredentialsProvider} — EKS Pod의 Pod Identity/IRSA 자격을 자동으로
 * 사용한다(stage/prod). 단 dev 로컬에서 실 Bedrock을 켤 때, JVM의 {@code user.home}이 표준 위치의
 * {@code ~/.aws/credentials}를 못 찾는 환경을 위해 {@code translation.aws.credentials-file}(+선택
 * {@code translation.aws.profile})이 설정되면 해당 파일을 명시적으로 읽는 {@link ProfileCredentialsProvider}를 쓴다.
 * 운영에선 이 값을 비워 두어 기본 자격 체인을 그대로 유지한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "translation.client", havingValue = "bedrock")
public class BedrockTranslationClient implements TranslationClient {

    private final String functionName;
    private final ObjectMapper objectMapper;
    private final LambdaClient lambdaClient;

    public BedrockTranslationClient(
            @Value("${translation.lambda.function-name:gb-community-translator}") String functionName,
            @Value("${translation.aws.region:ap-northeast-2}") String awsRegion,
            @Value("${translation.aws.credentials-file:}") String credentialsFile,
            @Value("${translation.aws.profile:default}") String profileName,
            ObjectMapper objectMapper) {
        // 본 클라이언트는 bedrock 프로필에서만 등록된다 — 함수명이 비면 기동 시 fail-fast.
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalStateException(
                    "translation.lambda.function-name이 비어 있습니다 — TRANSLATION_LAMBDA_FUNCTION_NAME 환경변수를 설정하세요.");
        }
        this.functionName = functionName;
        this.objectMapper = objectMapper;
        this.lambdaClient = LambdaClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(resolveCredentialsProvider(credentialsFile, profileName))
                .build();
    }

    /**
     * 자격 증명 공급자 선택. {@code credentials-file}이 설정되면(dev 로컬) 그 경로의 파일을 명시적으로 읽고,
     * 비어 있으면(stage/prod) 표준 {@link DefaultCredentialsProvider} 체인(Pod Identity/IRSA 등)을 쓴다.
     */
    private static AwsCredentialsProvider resolveCredentialsProvider(String credentialsFile, String profileName) {
        if (credentialsFile == null || credentialsFile.isBlank()) {
            return DefaultCredentialsProvider.create();
        }
        log.info("[BedrockTranslationClient] 명시 자격 증명 파일 사용 file={} profile={}", credentialsFile, profileName);
        ProfileFile profileFile = ProfileFile.builder()
                .content(Paths.get(credentialsFile))
                .type(ProfileFile.Type.CREDENTIALS)
                .build();
        return ProfileCredentialsProvider.builder()
                .profileFile(profileFile)
                .profileName(profileName)
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
            String eventJson = buildInvokePayload(kind, publicId, title, content, sourceLang, targetLang);
            InvokeResponse response = lambdaClient.invoke(InvokeRequest.builder()
                    .functionName(functionName)
                    .payload(SdkBytes.fromUtf8String(eventJson))
                    .build());

            // Lambda 런타임 자체 오류(미처리 예외 등) — functionError가 채워진다.
            if (response.functionError() != null) {
                log.warn("[BedrockTranslationClient] Lambda functionError={} kind={} publicId={}",
                        response.functionError(), kind, publicId);
                throw new RuntimeException("Translation Lambda functionError: " + response.functionError());
            }

            // 직접 invoke 응답 = 핸들러가 반환한 Function URL 봉투 {statusCode, headers, body(JSON 문자열)}.
            String envelope = response.payload().asUtf8String();
            JsonNode root = objectMapper.readTree(envelope);
            int statusCode = root.path("statusCode").asInt(0);
            String bodyJson = root.path("body").isMissingNode() ? null : root.path("body").asText(null);
            if (statusCode != 200 || bodyJson == null) {
                log.warn("[BedrockTranslationClient] Lambda 비정상 응답 status={} kind={} publicId={} body={}",
                        statusCode, kind, publicId, truncate(bodyJson));
                throw new RuntimeException("Translation Lambda returned status " + statusCode);
            }

            LambdaResponse parsed = objectMapper.readValue(bodyJson, LambdaResponse.class);
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
        } catch (IOException e) {
            log.warn("[BedrockTranslationClient] Lambda 응답 파싱 실패 kind={} publicId={} msg={}",
                    kind, publicId, e.getMessage());
            throw new RuntimeException("Translation Lambda response parse failed", e);
        }
    }

    /**
     * 번역 요청(snake_case)을 Function URL v2 이벤트로 래핑한 invoke 페이로드를 만든다.
     * Lambda 핸들러(translator_handler)는 {@code event["body"]}(JSON 문자열)를 그대로 파싱하므로
     * <b>Lambda 코드 무수정으로 직접 invoke와 호환</b>된다.
     */
    private String buildInvokePayload(String kind, String publicId, String title, String content,
                                      String sourceLang, String targetLang) throws IOException {
        // LinkedHashMap = 직렬화 순서 결정적(로그 가독성). title은 null이어도 키를 유지(comment 응답 계약).
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("kind", kind);
        inner.put("public_id", publicId);
        inner.put("title", title);
        inner.put("content", content);
        inner.put("source_lang", sourceLang);
        inner.put("target_lang", targetLang);
        String innerJson = objectMapper.writeValueAsString(inner);

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("method", "POST");
        http.put("path", "/");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", "2.0");
        event.put("rawPath", "/");
        event.put("requestContext", Map.of("http", http));
        event.put("headers", Map.of("content-type", "application/json"));
        event.put("body", innerJson);
        event.put("isBase64Encoded", false);
        return objectMapper.writeValueAsString(event);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "(null)";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
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
}
