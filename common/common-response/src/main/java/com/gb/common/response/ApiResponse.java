package com.gb.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

/**
 * 성공 응답 포맷. 모든 서비스가 공유한다.
 *
 * <p>성공 응답에는 {@code code} 필드가 존재하지 않으며, {@code data}는 값이 {@code null}이어도
 * 항상 직렬화된다(명세 §11: "data가 없으면 data: null"). {@code @JsonInclude(ALWAYS)}로
 * 프로젝트 전역 Jackson 설정이 NON_NULL이더라도 {@code data: null} 노출을 보장한다.
 *
 * <p>실패 응답은 {@code data} 키 자체가 없어야 하므로 별도 타입 {@link ErrorResponse}로 분리한다.
 * 편의를 위해 {@link #fail(String, String)} 진입점은 여기에 두되 반환 타입은 {@link ErrorResponse}다.
 */
@Getter
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final String message;

    private ApiResponse(T data, String message) {
        this.success = true;
        this.data = data;
        this.message = message;
    }

    /** 성공 + 기본 메시지. */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, SuccessStatus.OK.getMessage());
    }

    /** 성공 + 커스텀 메시지. */
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(data, message);
    }

    /** 성공 + {@link SuccessStatus} 기반 메시지. */
    public static <T> ApiResponse<T> success(SuccessStatus status, T data) {
        return new ApiResponse<>(data, status.getMessage());
    }

    /**
     * 실패 응답 생성. data 키가 없는 {@link ErrorResponse}를 반환한다.
     * code/message는 호출 측(주로 GlobalExceptionHandler)에서 ErrorCode 정보를 풀어 전달한다.
     */
    public static ErrorResponse fail(String code, String message) {
        return ErrorResponse.of(code, message);
    }
}
