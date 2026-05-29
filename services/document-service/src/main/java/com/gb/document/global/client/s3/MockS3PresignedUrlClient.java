package com.gb.document.global.client.s3;

import com.gb.document.global.config.AnalysisProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * dev 프로파일용 Mock — 실제 AWS S3 호출 없이 더미 URL을 즉시 반환한다.
 * 메타데이터는 로그에만 찍어 키 이름이 정확한지 사람이 눈으로 확인할 수 있게 한다.
 *
 * <p>CLAUDE.md §7 — Mock ↔ Real 전환 시 Service 코드 무변경이 목표. dev 환경에서 컨트롤러 동작을
 * 검증할 때 S3 자격증명/네트워크 없이도 흐름이 끝까지 도는지 확인할 수 있다.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class MockS3PresignedUrlClient implements S3PresignedUrlClient {

    private final AnalysisProperties properties;

    @Override
    public IssueUrlResult issueUploadUrl(String key, String contentType,
                                         Map<String, String> metadata, Duration ttl) {
        String url = "https://mock-s3.local/%s/%s?X-Amz-MockSignature=dev"
                .formatted(properties.uploadBucket(), key);
        Instant expiresAt = Instant.now().plus(ttl);
        log.info("[mock-s3] presigned PUT 발급 — bucket={} key={} contentType={} metadata={} expires={}",
                properties.uploadBucket(), key, contentType, metadata, expiresAt);
        return new IssueUrlResult(url, expiresAt);
    }
}
