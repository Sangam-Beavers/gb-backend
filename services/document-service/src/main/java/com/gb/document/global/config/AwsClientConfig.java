package com.gb.document.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * 운영(!dev)에서만 활성화되는 AWS 클라이언트 빈.
 *
 * <p>자격증명은 {@link DefaultCredentialsProvider}로 통일 — 운영은 IRSA(EKS) / EC2 IAM 역할,
 * 로컬은 환경변수/AWS profile을 자동 탐색한다. dev 프로파일에서는 Mock 클라이언트가 이 빈에 의존하지
 * 않으므로 빈 등록 자체를 건너뛴다(자격증명/네트워크 없이 기동 가능).
 */
@Configuration
@Profile("!dev")
@RequiredArgsConstructor
public class AwsClientConfig {

    private final AnalysisProperties properties;

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(properties.awsRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public SqsClient sqsClient() {
        return SqsClient.builder()
                .region(Region.of(properties.awsRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
