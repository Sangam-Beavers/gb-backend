package com.gb.wallet.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * wallet-service 도메인 에러 코드. 코드/HTTP/메시지는 API 명세 §12-4 WALLET 도메인 표를 SSOT로 한다.
 * 번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 */
@Getter
@RequiredArgsConstructor
public enum WalletErrorCode implements ErrorCode {

    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "WALLET4001", "존재하지 않는 지갑입니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "WALLET4002", "지갑 잔액이 부족합니다.");

    private final HttpStatus httpStatus; // @Getter가 getHttpStatus/getCode/getMessage 생성 → ErrorCode 충족
    private final String code;
    private final String message;
}
