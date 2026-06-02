package com.gb.wallet.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 송금(Transfer) 도메인 에러 코드. 코드/HTTP/메시지는 conventions.md §9의 TRANSFER 도메인 표를
 * SSOT로 한다. 번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 */
@Getter
@RequiredArgsConstructor
public enum TransferErrorCode implements ErrorCode {

    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "TRANSFER4001", "존재하지 않는 송금 내역입니다."),
    UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST, "TRANSFER4002", "지원하지 않는 통화입니다."),
    UNSUPPORTED_TRANSFER_TYPE(HttpStatus.BAD_REQUEST, "TRANSFER4003", "지원하지 않는 송금 유형입니다."),
    SELF_TRANSFER_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "TRANSFER4004", "자기 자신에게 송금할 수 없습니다."),
    UNSUPPORTED_CURRENCY_PAIR(HttpStatus.BAD_REQUEST, "TRANSFER4005", "지원하지 않는 통화 조합입니다."),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "TRANSFER4006", "송금 요청 횟수를 초과했습니다.");

    private final HttpStatus httpStatus; // @Getter가 getHttpStatus/getCode/getMessage 생성 → ErrorCode 충족
    private final String code;
    private final String message;
}
