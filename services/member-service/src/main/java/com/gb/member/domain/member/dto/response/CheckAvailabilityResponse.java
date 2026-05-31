package com.gb.member.domain.member.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 이메일/닉네임 중복 확인 응답.
 *
 * <p>{@code available = true}면 사용 가능(중복 아님), {@code false}면 이미 사용 중이다.
 * 가입 폼의 "중복확인" 버튼이 호출하는 사전 확인용 응답이다(서버 최종 검증은 회원가입 시 별도 수행).
 */
@Getter
public class CheckAvailabilityResponse {

    @Schema(description = "사용 가능 여부 (true=사용 가능, false=이미 사용 중)", example = "true")
    private final boolean available;

    @Builder
    private CheckAvailabilityResponse(boolean available) {
        this.available = available;
    }

    public static CheckAvailabilityResponse of(boolean available) {
        return CheckAvailabilityResponse.builder()
                .available(available)
                .build();
    }
}
