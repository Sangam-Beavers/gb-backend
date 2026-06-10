package com.gb.appadmin.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * app-admin-service 도메인 에러 코드.
 *
 * <p>번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 */
@Getter
@RequiredArgsConstructor
public enum AppAdminErrorCode implements ErrorCode {

    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4002", "존재하지 않는 공지사항입니다."),
    FAQ_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4003", "존재하지 않는 FAQ입니다."),
    FEE_POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4004", "존재하지 않는 수수료 정책입니다."),
    EXCHANGE_RATE_POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4005", "존재하지 않는 환율 정책입니다."),
    SERVICE_SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4006", "존재하지 않는 서비스 설정입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
