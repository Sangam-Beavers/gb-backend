package com.gb.common.response;

import lombok.Getter;

/**
 * 실패 응답 포맷. {@code data} 필드 자체가 없으므로 실패 응답에는 data 키가 직렬화되지 않는다.
 * 성공 응답({@link ApiResponse})에 {@code code}가 없는 것과 대칭을 이룬다.
 */
@Getter
public class ErrorResponse {

    private final boolean success;
    private final String code;
    private final String message;

    private ErrorResponse(String code, String message) {
        this.success = false;
        this.code = code;
        this.message = message;
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message);
    }
}
