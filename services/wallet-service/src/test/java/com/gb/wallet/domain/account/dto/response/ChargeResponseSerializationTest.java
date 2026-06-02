package com.gb.wallet.domain.account.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChargeResponse}의 Jackson round-trip 검증 — 충전 멱등성 Layer 1(Redis 캐시)이 응답을 JSON으로
 * 저장했다가 동일 키 재요청 시 다시 객체로 복원하므로, 같은 ObjectMapper로 직렬화↔역직렬화가 깨지면 안 된다.
 *
 * <p><b>일부러 {@code ParameterNamesModule} 없이 plain ObjectMapper + SNAKE_CASE만 쓴다.</b> 이렇게 해도
 * round-trip이 되면, ChargeResponse가 {@code -parameters}/파라미터명 추론에 기대지 않고 생성자의
 * {@code @JsonCreator + @JsonProperty(snake_case)}만으로 역직렬화됨을 증명한다(결정성 보장). 운영
 * ObjectMapper(모듈 더 많음)는 이 명시 애너테이션을 그대로 우선하므로 동일하게 동작한다.
 */
class ChargeResponseSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    @DisplayName("직렬화 → snake_case 키, 역직렬화 → 동일 값으로 round-trip된다")
    void round_trip_snake_case() throws Exception {
        ChargeResponse original = ChargeResponse.builder()
                .publicId("7c9e6679-7425-40de-944b-e07fc1f90ae7")
                .accountPublicId("9b2e4c1a-7f3d-4b8e-9a1c-2d5e6f7a8b9c")
                .amount("1530000.0000")
                .currencyCode("KRW")
                .walletBalance("5430000.0000")
                .status("COMPLETED")
                .createdAt("2026-05-30T04:15:30Z")
                .build();

        String json = objectMapper.writeValueAsString(original);

        // 직렬화 출력은 명세 §5 snake_case 키여야 한다(전역 SNAKE_CASE 전략과 동일 결과).
        assertThat(json)
                .contains("\"public_id\"")
                .contains("\"account_public_id\"")
                .contains("\"currency_code\"")
                .contains("\"wallet_balance\"")
                .contains("\"created_at\"");

        ChargeResponse restored = objectMapper.readValue(json, ChargeResponse.class);

        assertThat(restored.getPublicId()).isEqualTo(original.getPublicId());
        assertThat(restored.getAccountPublicId()).isEqualTo(original.getAccountPublicId());
        assertThat(restored.getAmount()).isEqualTo(original.getAmount());
        assertThat(restored.getCurrencyCode()).isEqualTo(original.getCurrencyCode());
        assertThat(restored.getWalletBalance()).isEqualTo(original.getWalletBalance());
        assertThat(restored.getStatus()).isEqualTo(original.getStatus());
        assertThat(restored.getCreatedAt()).isEqualTo(original.getCreatedAt());
    }

    @Test
    @DisplayName("null 필드(예: 일부 미설정)도 round-trip에서 보존된다")
    void round_trip_with_null_field() throws Exception {
        ChargeResponse original = ChargeResponse.builder()
                .publicId("pid")
                .accountPublicId("acct")
                .amount("100.0000")
                .currencyCode("KRW")
                .walletBalance("100.0000")
                .status("COMPLETED")
                .createdAt(null)
                .build();

        ChargeResponse restored = objectMapper.readValue(
                objectMapper.writeValueAsString(original), ChargeResponse.class);

        assertThat(restored.getCreatedAt()).isNull();
        assertThat(restored.getPublicId()).isEqualTo("pid");
    }
}
