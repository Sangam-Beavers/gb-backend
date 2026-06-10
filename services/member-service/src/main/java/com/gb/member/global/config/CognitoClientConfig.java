package com.gb.member.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * 운영/스테이징 IdP(AWS Cognito) 관리 API 호출용 SDK 클라이언트 빈.
 *
 * <p>prod/stage 프로필에서만 생성된다 — dev/test는 Authentik(RealIdpUserClient)을 쓰므로 이 빈도,
 * AWS 자격증명도 필요 없다(@Profile로 컨텍스트에서 제외 → 테스트가 AWS 의존 없이 뜸).
 *
 * <p><b>자격증명(IAM):</b> {@link DefaultCredentialsProvider}가 표준 순서로 자동 탐색한다
 * (환경변수 → 컨테이너/인스턴스 역할 등). 운영 배포 시 백엔드 실행 역할에 다음 권한이 필요하다:
 * {@code cognito-idp:AdminCreateUser}, {@code AdminSetUserPassword}, {@code AdminDeleteUser},
 * {@code AdminDisableUser}, {@code ListUsers}. 평문 키를 코드/yml에 넣지 않는다(CLAUDE 보안 규칙).
 *
 * <p>리전/풀ID는 환경변수로 주입한다: {@code COGNITO_REGION}, {@code COGNITO_USER_POOL_ID}
 * (→ {@code auth.cognito.region} / {@code auth.cognito.user-pool-id}).
 */
@Configuration
@Profile("prod | stage")
public class CognitoClientConfig {

    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient(
            @Value("${auth.cognito.region}") String region) {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
