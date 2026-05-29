package com.gb.document.global.client.s3;

import com.gb.document.global.config.AnalysisProperties;
import java.time.Duration;
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
        return new IssueUrlResult(presigned.url().toString(), presigned.expiration());
    }
}
