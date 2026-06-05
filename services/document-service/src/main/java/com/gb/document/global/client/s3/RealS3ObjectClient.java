package com.gb.document.global.client.s3;

import com.gb.document.global.config.AnalysisProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * 전 프로파일 공통 — AWS SDK v2 {@link S3Client#headObject}로 오브젝트 존재를 확인한다.
 * presign과 달리 실제 API 호출이므로 자격증명 외에 {@code s3:GetObject}(HeadObject) 권한이 필요하다.
 */
@Component
@RequiredArgsConstructor
public class RealS3ObjectClient implements S3ObjectClient {

    private final S3Client s3Client;
    private final AnalysisProperties properties;

    @Override
    public boolean objectExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.uploadBucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
