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
    // WALLET4002 = 422(UNPROCESSABLE_ENTITY): 요청 형식은 정상이나 잔액이라는 비즈니스 상태 때문에 처리 불가.
    // 전 도메인(송금·환전 등) 공용 코드이며 422가 SSOT — 명세 §8/§9·conventions와 정렬.
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "WALLET4002", "지갑 잔액이 부족합니다."),
    // WALLET4003 = 422: 요청은 정상이나 지갑 상태(SUSPENDED/CLOSED)가 ACTIVE가 아니라 처리 불가(WTX-05).
    // 동결/폐쇄 지갑의 송금·수신·충전을 차단한다. WALLET4002와 동일한 "비즈니스 상태 차단" 결의 422.
    WALLET_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "WALLET4003", "비활성 지갑입니다.");

    private final HttpStatus httpStatus; // @Getter가 getHttpStatus/getCode/getMessage 생성 → ErrorCode 충족
    private final String code;
    private final String message;
}
