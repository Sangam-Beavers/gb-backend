package com.gb.member.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * member-service 도메인 에러 코드. 코드/HTTP/메시지는 conventions.md §회원(MEMBER) 표를 SSOT로 한다.
 * 번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER4001", "존재하지 않는 회원입니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "MEMBER4002", "이미 사용 중인 이메일입니다."),
    NICKNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "MEMBER4003", "이미 사용 중인 닉네임입니다."),
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "MEMBER4004", "유효하지 않거나 만료된 재설정 토큰입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
