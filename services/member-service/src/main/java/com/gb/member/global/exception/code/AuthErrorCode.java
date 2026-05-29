package com.gb.member.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증(AUTH) 도메인 에러 코드. 코드/HTTP/메시지는 conventions.md §9 인증(AUTH) 표를 SSOT로 한다.
 * 번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH4001", "이메일 또는 비밀번호가 올바르지 않습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
