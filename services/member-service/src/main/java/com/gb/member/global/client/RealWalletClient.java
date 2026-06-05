package com.gb.member.global.client;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 실제 wallet-service의 {@code POST /api/v1/wallets}를 호출하는 {@link WalletClient} 구현체.
 *
 * <p>방식 B(외부 IdP) 기반이라 wallet-service도 OAuth2 Resource Server로 토큰을 검증한다. 따라서
 * 본 클라이언트는 <b>현재 요청의 JWT 토큰을 그대로 wallet-service에 전달</b>한다 —
 * Spring Security가 {@code SecurityContextHolder}에 저장한 {@link JwtAuthenticationToken}에서
 * {@code tokenValue}를 꺼내 {@code Authorization: Bearer} 헤더로 부착하면 된다. 별도 service token이나
 * 내부 endpoint를 따로 두지 않아 와이어링이 단순하다.
 *
 * <p>응답 4xx/5xx, 연결 실패는 모두 {@link CommonErrorCode#INTERNAL_SERVER_ERROR}로 래핑해 던진다.
 * 호출 측(VerificationServiceImpl)은 이 예외를 try/catch로 흡수해 fail-open으로 commit한다(이슈 #152).
 *
 * <p>활성 프로파일이 {@code dev}/{@code test}가 아닐 때만 빈으로 등록된다(stage·prod에서 동작).
 */
@Slf4j
@Component
@Profile("!dev & !test")
public class RealWalletClient implements WalletClient {

    private final RestClient restClient;
    private final String walletApiBaseUrl;

    public RealWalletClient(
            RestClient walletRestClient,
            @Value("${wallet.api.base-url}") String walletApiBaseUrl) {
        this.restClient = walletRestClient;
        this.walletApiBaseUrl = walletApiBaseUrl;
    }

    @Override
    public void createWalletFor(String userPublicId) {
        String bearerToken = currentJwtBearer();
        if (bearerToken == null) {
            // SecurityContext에 JWT가 없으면 인증 누락 상태에서 호출된 셈 — 가입/인증 흐름 외부에서
            // 부적절하게 호출됐다는 신호다. 호출 측이 fail-open으로 흡수하도록 BusinessException으로 던진다.
            log.error("[RealWalletClient] SecurityContext에 JWT가 없습니다. user_public_id={}", userPublicId);
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        try {
            restClient.post()
                    .uri(walletApiBaseUrl + "/api/v1/wallets")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // wallet-service의 응답 에러(4xx/5xx). 어느 쪽이든 인증 흐름은 fail-open으로 흡수되도록 던진다.
            log.error("[RealWalletClient] 지갑 생성 응답 에러: user_public_id={}, status={}, msg={}",
                    userPublicId, e.getStatusCode(), e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        } catch (RestClientException e) {
            // 연결 실패·타임아웃 등(응답 없음).
            log.error("[RealWalletClient] 지갑 생성 연결 실패: user_public_id={}, msg={}",
                    userPublicId, e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 현재 요청의 SecurityContext에서 JWT 토큰 문자열을 꺼낸다(있으면). 없으면 null.
     *
     * <p>Spring Security OAuth2 Resource Server가 토큰 검증을 통과시키면 Authentication 객체가
     * {@link JwtAuthenticationToken}으로 채워진다. {@code getToken().getTokenValue()}는 원본 JWT 문자열을
     * 그대로 돌려주므로 그대로 wallet-service에 전달해 재검증을 받는다.
     */
    private String currentJwtBearer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getTokenValue();
        }
        return null;
    }
}
