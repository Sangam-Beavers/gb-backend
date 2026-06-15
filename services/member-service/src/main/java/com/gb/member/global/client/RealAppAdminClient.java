package com.gb.member.global.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 실제 app-admin-service를 호출하는 {@link AppAdminClient} 구현체(이슈 #244).
 * dev/stage/prod 환경에서 {@code GET /api/v1/app-admin/app/settings/doc-analysis-credit}을 호출해
 * DOC_ANALYSIS_CREDIT 설정값을 읽는다.
 *
 * <p><b>Fail-open</b>: 네트워크 오류·파싱 실패·app-admin 장애 등 모든 예외를 흡수하고
 * 기본값(3)을 반환한다 — app-admin 장애가 회원 가입을 막지 않도록 한다.
 *
 * <p>base URL은 {@code app-admin.api.base-url}(환경변수 {@code APP_ADMIN_API_BASE_URL})로 주입한다.
 * K8s 내부에서는 {@code http://app-admin.{namespace}.svc.cluster.local:8086} 형태로 설정한다.
 */
@Slf4j
@Component
@Profile("!test")
public class RealAppAdminClient implements AppAdminClient {

    private final RestClient appAdminRestClient;
    private final String baseUrl;

    private static final int DEFAULT_CREDIT = 3;

    public RealAppAdminClient(
            @Qualifier("appAdminRestClient") RestClient appAdminRestClient,
            @Value("${app-admin.api.base-url:http://localhost:8086}") String baseUrl) {
        this.appAdminRestClient = appAdminRestClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public int getDocAnalysisCredit() {
        try {
            JsonNode response = appAdminRestClient.get()
                    .uri(baseUrl + "/api/v1/app-admin/app/settings/doc-analysis-credit")
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                log.warn("app-admin 크레딧 조회 응답이 null, 기본값({}) 사용", DEFAULT_CREDIT);
                return DEFAULT_CREDIT;
            }

            // ApiResponse 포맷: { "success": true, "data": { "setting_value": "3", ... } }
            String settingValue = response.path("data").path("setting_value").asText(null);
            if (settingValue == null || settingValue.isBlank()) {
                log.warn("app-admin setting_value 없음, 기본값({}) 사용", DEFAULT_CREDIT);
                return DEFAULT_CREDIT;
            }

            return Integer.parseInt(settingValue);
        } catch (NumberFormatException e) {
            log.warn("app-admin 크레딧값 파싱 실패, 기본값({}) 사용: {}", DEFAULT_CREDIT, e.getMessage());
            return DEFAULT_CREDIT;
        } catch (Exception e) {
            log.warn("app-admin 크레딧 조회 실패, 기본값({}) 사용: {}", DEFAULT_CREDIT, e.getMessage());
            return DEFAULT_CREDIT;
        }
    }
}
