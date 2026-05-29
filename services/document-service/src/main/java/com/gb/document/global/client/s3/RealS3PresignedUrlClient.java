package com.gb.document.global.client.s3;

import com.gb.document.global.config.AnalysisProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 운영(!dev)용 — AWS SDK v2 {@link S3Presigner}로 실제 Pre-signed PUT URL을 발급한다.
 *
 * <p>{@link PutObjectRequest#metadata(Map)}로 S3 오브젝트 메타데이터를 주입한다. 발급된 URL로
 * 사용자가 파일을 PUT 업로드하면 S3가 메타데이터를 오브젝트에 박고, Lambda A가 그 메타데이터를 읽어
 * source / document_id / result_queue_arn 기준으로 분기한다. <b>키 이름이 어긋나면 분기가 깨진다.</b>
 *
 * <p><b>중요:</b> SDK v2 presigner는 메타데이터를 서명 헤더({@code X-Amz-SignedHeaders})에 굽는다.
 * 따라서 업로더는 PUT 시 동일한 {@code x-amz-meta-*}·Content-Type 헤더를 그대로 다시 보내야 서명이
 * 일치한다(안 보내면 403, 메타데이터는 오브젝트에 박히지 않음). 그래서 발급 결과에 서명 헤더를 함께
 * 담아 호출 측이 응답으로 클라이언트에 내려줄 수 있게 한다(host는 HTTP 클라이언트가 자동 설정하므로 제외).
 */
@Component
@Profile("!dev")
@RequiredArgsConstructor
public class RealS3PresignedUrlClient implements S3PresignedUrlClient {

    private final S3Presigner s3Presigner;
    private final AnalysisProperties properties;

    @Override
    public IssueUrlResult issueUploadUrl(String key, String contentType,
                                         Map<String, String> metadata, Duration ttl) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(properties.uploadBucket())
                .key(key)
                .contentType(contentType)
                .metadata(metadata)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(putRequest)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        return new IssueUrlResult(
                presigned.url().toString(), toUploadHeaders(presigned.signedHeaders()), presigned.expiration());
    }

    /**
     * 서명 헤더(Map&lt;String, List&lt;String&gt;&gt;)를 업로더가 그대로 보낼 단일값 맵으로 평탄화한다.
     * host는 HTTP 클라이언트(브라우저 등)가 자동 설정하며 JS로 덮어쓸 수 없으므로 제외한다.
     */
    private Map<String, String> toUploadHeaders(Map<String, List<String>> signedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        signedHeaders.forEach((name, values) -> {
            if (!"host".equalsIgnoreCase(name) && values != null && !values.isEmpty()) {
                headers.put(name, String.join(",", values));
            }
        });
        return headers;
    }
}
