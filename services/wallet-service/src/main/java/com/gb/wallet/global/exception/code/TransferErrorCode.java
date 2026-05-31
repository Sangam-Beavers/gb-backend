package com.gb.wallet.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 송금(Transfer) 도메인 에러 코드. 코드/HTTP/메시지는 conventions.md §9의 TRANSFER 도메인 표를
 * SSOT로 한다. 번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 *
 * <p>TRANSFER4001은 명세에 정의되지 않았으므로 추가 금지.
 */
@Getter
@RequiredArgsConstructor
public enum TransferErrorCode implements ErrorCode {

    UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST, "TRANSFER4002", "지원하지 않는 통화입니다."),
    UNSUPPORTED_TRANSFER_TYPE(HttpStatus.BAD_REQUEST, "TRANSFER4003", "지원하지 않는 송금 유형입니다.");

    private final HttpStatus httpStatus; // @Getter가 getHttpStatus/getCode/getMessage 생성 → ErrorCode 충족
    private final String code;
    private final String message;
}
