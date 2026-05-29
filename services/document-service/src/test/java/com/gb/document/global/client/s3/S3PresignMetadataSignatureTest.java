package com.gb.document.global.client.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.document.global.config.AnalysisProperties;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 제출 API가 넣는 S3 오브젝트 메타데이터(source / document_id / result_queue_arn)가
 * Pre-signed PUT URL의 <b>서명에 실제로 포함</b>되는지 확인하는 검증 테스트.
 *
 * <p>배경: presigned URL을 만들 때 메타데이터를 넣는 것과, 그 URL로 PUT했을 때 오브젝트에 메타데이터가
 * 박히는 것은 별개다. 메타데이터 헤더가 서명에 포함되지 않으면 업로드 시 무시될 수 있다.
 * S3Presigner의 서명은 네트워크 없이 전적으로 로컬에서 수행되므로, 더미 자격증명으로 서명을 떠서
 * {@code X-Amz-SignedHeaders}에 {@code x-amz-meta-*}가 들어가는지 직접 눈으로 확인한다.
 *
 * <p>결론적으로 SDK v2는 메타데이터를 <b>서명 헤더로 강제</b>하므로 무시될 수 없다. 다만 그 반대급부로
 * 업로더(프론트)는 PUT 시 동일한 {@code x-amz-meta-*} 헤더(이름+값)를 그대로 다시 보내야 한다.
 */
class S3PresignMetadataSignatureTest {

    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("AKIDEXAMPLE", "secretkeyexample")))
            .build();

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    @DisplayName("메타데이터 3종이 X-Amz-SignedHeaders(서명 헤더)에 포함된다 — PUT 시 무시되지 않음")
    void 메타데이터가_서명헤더에_포함된다() {
        // given — 제출 API가 만드는 것과 동일한 메타데이터
        Map<String, String> metadata = Map.of(
                "source", "production",
                "document_id", "11111111-2222-3333-4444-555555555555",
                "result_queue_arn", "arn:aws:sqs:ap-northeast-2:123456789012:gb-analysis-results-prod");

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket("gb-document-uploads-prod")
                .key("uploads/2026-05-29/doc/contract.pdf")
                .contentType("application/octet-stream")
                .metadata(metadata)
                .build();

        // when — 로컬 서명만 수행(네트워크 호출 없음)
        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(600))
                        .putObjectRequest(putRequest)
                        .build());

        // then — 메타데이터가 서명 헤더에 들어갔는지 (대소문자 무시)
        Map<String, java.util.List<String>> signedHeaders = presigned.signedHeaders();
        java.util.Set<String> signedHeaderNames = signedHeaders.keySet().stream()
                .map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());

        URL url = presigned.url();
        String signedHeadersParam = extractQueryParam(url.getQuery(), "X-Amz-SignedHeaders");

        // 눈으로 확인 가능하도록 출력
        System.out.println("=== presigned URL ===\n" + url);
        System.out.println("=== signedHeaders() ===\n" + signedHeaders);
        System.out.println("=== X-Amz-SignedHeaders ===\n" + signedHeadersParam);
        System.out.println("=== httpRequest headers ===\n" + presigned.httpRequest().headers());

        assertThat(signedHeaderNames)
                .as("메타데이터 헤더가 서명 헤더에 포함되어야 PUT 시 무시되지 않는다")
                .contains("x-amz-meta-source", "x-amz-meta-document_id", "x-amz-meta-result_queue_arn");

        assertThat(signedHeadersParam.toLowerCase())
                .as("URL의 X-Amz-SignedHeaders 쿼리에도 메타데이터 헤더 이름이 들어가야 한다")
                .contains("x-amz-meta-source")
                .contains("x-amz-meta-document_id")
                .contains("x-amz-meta-result_queue_arn");

        // 서명 헤더의 값까지 확인 — 업로더는 이 값들을 그대로 다시 보내야 함
        assertThat(presigned.httpRequest().firstMatchingHeader("x-amz-meta-source")).hasValue("production");
    }

    @Test
    @DisplayName("RealS3PresignedUrlClient는 서명 헤더를 응답에 담되 host는 제외한다")
    void 클라이언트가_서명헤더를_host제외하고_반환한다() {
        // given
        AnalysisProperties props = new AnalysisProperties(
                "production",
                "arn:aws:sqs:ap-northeast-2:123456789012:gb-analysis-results-prod",
                "",
                "gb-document-uploads-prod",
                600,
                "ap-northeast-2");
        RealS3PresignedUrlClient client = new RealS3PresignedUrlClient(presigner, props);
        Map<String, String> metadata = Map.of(
                "source", "production",
                "document_id", "11111111-2222-3333-4444-555555555555",
                "result_queue_arn", "arn:aws:sqs:ap-northeast-2:123456789012:gb-analysis-results-prod");

        // when
        S3PresignedUrlClient.IssueUrlResult issued = client.issueUploadUrl(
                "uploads/2026-05-29/doc/contract.pdf", "application/octet-stream",
                metadata, Duration.ofSeconds(600));

        // then — 업로더가 PUT 시 보낼 헤더에 메타데이터 + Content-Type은 있고 host는 없어야 한다.
        assertThat(issued.signedHeaders().keySet().stream().map(String::toLowerCase))
                .contains("content-type", "x-amz-meta-source",
                        "x-amz-meta-document_id", "x-amz-meta-result_queue_arn")
                .doesNotContain("host");
        assertThat(issued.signedHeaders())
                .as("값까지 그대로 실려야 업로더가 동일 헤더로 PUT할 수 있다")
                .containsEntry("x-amz-meta-source", "production");
        assertThat(issued.url()).startsWith("https://gb-document-uploads-prod.s3");
    }

    private static String extractQueryParam(String rawQuery, String key) {
        if (rawQuery == null) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equalsIgnoreCase(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
