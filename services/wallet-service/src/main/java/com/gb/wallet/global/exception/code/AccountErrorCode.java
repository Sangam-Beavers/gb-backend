package com.gb.wallet.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * wallet-service 계좌(Account) 도메인 에러 코드.
 *
 * <p>코드/HTTP/메시지는 API 명세 SSOT를 따른다:
 * <ul>
 *   <li>§11 — ACCOUNT4001/4002/4004/4005/4006 (계좌 등록 흐름)</li>
 *   <li>§12 — ACCOUNT4003/4007 (충전 흐름)</li>
 *   <li>§13 — ACCOUNT4008/4009 (1원 소액이체 인증 confirm 흐름)</li>
 * </ul>
 *
 * <p>번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 */
@Getter
@RequiredArgsConstructor
public enum AccountErrorCode implements ErrorCode {

    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "ACCOUNT4001", "존재하지 않는 계좌입니다."),
    ACCOUNT_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "ACCOUNT4002", "계좌 인증에 실패했습니다."),
    INSUFFICIENT_LINKED_ACCOUNT_BALANCE(HttpStatus.BAD_REQUEST, "ACCOUNT4003", "연동 계좌의 잔액이 부족합니다."),
    ACCOUNT_ALREADY_REGISTERED(HttpStatus.CONFLICT, "ACCOUNT4004", "이미 등록된 계좌입니다."),
    VERIFICATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT4005", "계좌 인증 요청 횟수를 초과했습니다."),
    UNVERIFIED_ACCOUNT(HttpStatus.FORBIDDEN, "ACCOUNT4006", "인증되지 않은 계좌입니다."),
    CHARGE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT4007", "충전 한도를 초과했습니다."),

    /** 1원 인증: 인증번호 불일치 또는 이미 사용된 코드. BANK4005·BANK4007 → 이 에러로 통합. */
    VERIFY_CODE_INVALID(HttpStatus.BAD_REQUEST, "ACCOUNT4008", "인증번호가 올바르지 않습니다."),

    /** 1원 인증: 인증 세션 없음·만료. BANK4006 → 이 에러. registerAccount 시 Redis 세션 없을 때도 사용. */
    VERIFY_SESSION_NOT_FOUND(HttpStatus.BAD_REQUEST, "ACCOUNT4009", "인증 세션이 없거나 만료되었습니다. 인증을 다시 시작해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
