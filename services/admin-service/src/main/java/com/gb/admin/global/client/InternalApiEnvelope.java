package com.gb.admin.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ApiResponse envelope({@code {success, data, message}}) 의 admin-service 측 wire 모델.
 *
 * <p>전역 SNAKE_CASE 가 들어왔지만 외부(타 서비스) 응답 매핑은 명시 {@link JsonProperty} 로 고정한다
 * — wallet/member 의 ApiResponse 가 어떤 필드명을 쓰는지에 의존하지 않게.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalApiEnvelope<T>(
        @JsonProperty("success") boolean success,
        @JsonProperty("data") T data,
        @JsonProperty("message") String message,
        @JsonProperty("code") String code
) {
}
