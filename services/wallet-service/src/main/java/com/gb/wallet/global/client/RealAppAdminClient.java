package com.gb.wallet.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * app-admin-service의 공개 수수료 정책 API를 호출하는 {@link AppAdminClient} 구현체.
 *
 * <p>{@code GET /api/v1/app/fee-policies}를 호출해 serviceType으로 필터링한다.
 * 호출 실패(연결 오류·5xx) 시 COMMON5000으로 fail-fast — 수수료를 모르는 채로 송금을 진행시키면
 * 금융 정합성 문제가 발생하므로 조용한 폴백을 허용하지 않는다.
 */
@Slf4j
@Component
@Profile("!dev & !test")
public class RealAppAdminClient implements AppAdminClient {

    private final RestClient restClient;

    public RealAppAdminClient(
            RestClient.Builder builder,
            @Value("${app-admin.api.base-url:http://localhost:8086}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public FeePolicy getExchangeFeePolicy() {
        return fetchByServiceType("EXCHANGE");
    }

    @Override
    public FeePolicy getCashoutFeePolicy() {
        return fetchByServiceType("CASHOUT");
    }

    private FeePolicy fetchByServiceType(String serviceType) {
        try {
            FeePolicyEnvelope envelope = restClient.get()
                    .uri("/api/v1/app/fee-policies")
                    .retrieve()
                    .body(FeePolicyEnvelope.class);

            if (envelope == null || envelope.data() == null) {
                log.error("[AppAdminClient] 수수료 정책 응답 없음 — serviceType={}", serviceType);
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }

            return envelope.data().stream()
                    .filter(p -> serviceType.equalsIgnoreCase(p.serviceType()) && Boolean.TRUE.equals(p.active()))
                    .findFirst()
                    .map(p -> new FeePolicy(
                            p.feeType(),
                            new BigDecimal(p.feeValue()),
                            p.minFee() != null ? new BigDecimal(p.minFee()) : null,
                            p.maxFee() != null ? new BigDecimal(p.maxFee()) : null))
                    .orElseThrow(() -> {
                        log.error("[AppAdminClient] 활성 수수료 정책 없음 — serviceType={}", serviceType);
                        return new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
                    });

        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("[AppAdminClient] 수수료 정책 조회 실패 — serviceType={}, error={}", serviceType, e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FeePolicyEnvelope(List<FeePolicyWire> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FeePolicyWire(
            @JsonProperty("service_type") String serviceType,
            @JsonProperty("fee_type") String feeType,
            @JsonProperty("fee_value") String feeValue,
            @JsonProperty("min_fee") String minFee,
            @JsonProperty("max_fee") String maxFee,
            @JsonProperty("active") Boolean active
    ) {}
}
