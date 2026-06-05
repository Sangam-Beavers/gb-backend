package com.gb.document.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * 전 프로파일 공통 AWS 클라이언트 빈.
 *
 * <p>자격증명은 {@link DefaultCredentialsProvider}로 통일 — 운영은 IRSA(EKS) / EC2 IAM 역할,
 * dev/로컬은 환경변수/AWS profile(예: {@code AWS_PROFILE=gb-account-b})을 자동 탐색한다.
 * 자격증명 해석은 lazy라 자격증명 없이도 기동은 되지만, presign/SQS 호출 시점에 실패한다.
 * dev도 진짜 S3 Pre-signed URL을 발급해 E2E(업로드→Lambda→온프렘 MySQL)를 검증하기 위해
 * dev 프로파일 제외(@Profile("!dev"))를 제거했다.
 *
 * <p>v1.1 — 결과 수신용 {@code spring-cloud-aws-starter-sqs}가 {@code SqsAsyncClient}를 auto-config로
 * 만든다. 그 starter도 컨테이너의 {@link AwsCredentialsProvider}/{@link AwsRegionProvider} 빈을
 * {@code @ConditionalOnMissingBean}으로 우선 사용하므로, 여기서 두 빈을 노출해 <b>설정 소스를 한 곳</b>으로
 * 모은다(우리가 만든 동기 {@link SqsClient}/{@link S3Presigner} ↔ starter가 만든 비동기 SqsAsyncClient가
 * 같은 자격증명/리전을 공유). 상세: {@code docs/document-analysis/result-queue-routing.md} §3.
 */
@Configuration
@RequiredArgsConstructor
public class AwsClientConfig {

    private final AnalysisProperties properties;

    /**
     * 전역 자격증명 빈. spring-cloud-aws starter도 이 빈을 우선 주입받는다.
     * 운영은 IRSA/EC2 IAM, 로컬은 환경변수/AWS profile을 chain으로 탐색한다.
     */
    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.create();
    }

    /**
     * 전역 리전 빈. {@link AnalysisProperties#awsRegion()}을 정적 소스로 사용해 환경변수보다
     * yaml 설정을 우선한다. spring-cloud-aws starter도 이 빈을 우선 주입받는다.
     */
    @Bean
    public AwsRegionProvider awsRegionProvider() {
        Region region = Region.of(properties.awsRegion());
        return () -> region;
    }

    @Bean
    public S3Presigner s3Presigner(AwsCredentialsProvider credentialsProvider,
                                   AwsRegionProvider regionProvider) {
        return S3Presigner.builder()
                .region(regionProvider.getRegion())
                .credentialsProvider(credentialsProvider)
                .build();
    }

    @Bean
    public SqsClient sqsClient(AwsCredentialsProvider credentialsProvider,
                               AwsRegionProvider regionProvider) {
        return SqsClient.builder()
                .region(regionProvider.getRegion())
                .credentialsProvider(credentialsProvider)
                .build();
    }

    /** retry의 원본 존재 확인(HeadObject)용 — {@code RealS3ObjectClient}가 사용한다. */
    @Bean
    public S3Client s3Client(AwsCredentialsProvider credentialsProvider,
                             AwsRegionProvider regionProvider) {
        return S3Client.builder()
                .region(regionProvider.getRegion())
                .credentialsProvider(credentialsProvider)
                .build();
    }
}
