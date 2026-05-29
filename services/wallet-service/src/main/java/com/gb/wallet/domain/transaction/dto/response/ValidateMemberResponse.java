package com.gb.wallet.domain.transaction.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gb.wallet.global.client.MemberInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * GET /api/v1/transfers/validate-member 응답 data.
 *
 * <p>JSON 필드명은 전역 Jackson 설정으로 camelCase → snake_case 변환된다
 * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE}).
 * 따라서 DTO 필드는 camelCase로 두고 {@code @JsonProperty}는 붙이지 않는다.
 */
@Getter
public class ValidateMemberResponse {

    @Schema(description = "수신자 회원 식별자(UUID)", example = "11111111-1111-1111-1111-111111111111")
    private final String receiverPublicId;

    @Schema(description = "수신자 닉네임", example = "Linh")
    private final String nickname;

    // primitive boolean + 필드명 isXxx 조합은 Lombok이 isXxx() 게터를 만들고 Jackson이 'is'를 떼
    // property를 'verified'로 추출하므로, 전역 SNAKE_CASE 변환을 거쳐도 명세("is_verified")와
    // 어긋난다. 이 케이스는 @JsonProperty로 명시 고정한다(전역 변환의 사각지대 — 예외적 사용).
    @Schema(description = "회원 인증 배지 여부", example = "true")
    @JsonProperty("is_verified")
    private final boolean isVerified;

    @Builder
    private ValidateMemberResponse(String receiverPublicId, String nickname, boolean isVerified) {
        this.receiverPublicId = receiverPublicId;
        this.nickname = nickname;
        this.isVerified = isVerified;
    }

    public static ValidateMemberResponse from(MemberInfo member) {
        return ValidateMemberResponse.builder()
                .receiverPublicId(member.userPublicId())
                .nickname(member.nickname())
                .isVerified(member.isVerified())
                .build();
    }
}
