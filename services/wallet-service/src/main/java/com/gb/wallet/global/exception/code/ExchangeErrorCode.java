package com.gb.wallet.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 환전 도메인 에러 코드. 코드/HTTP/메시지는 API 명세(remittance/api-spec.md §9) 환전 표를 SSOT로 한다.
 * 번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 *
 * <p>미지원 통화(TRANSFER4002)·잔액 부족(WALLET4002)·본인 아님(COMMON4031)은 기존 코드를 재사용하고,
 * 환전 고유 상황만 여기서 정의한다.
 */
@Getter
@RequiredArgsConstructor
public enum ExchangeErrorCode implements ErrorCode {

    EXCHANGE_NOT_FOUND(HttpStatus.NOT_FOUND, "EXCHANGE4001", "존재하지 않는 환전 내역입니다."),
    QUOTE_EXPIRED(HttpStatus.BAD_REQUEST, "EXCHANGE4002", "환율 견적이 만료되었습니다."),
    // wallet-exchange-3 — 신청액이 너무 작아 수령액이 0으로 반올림되는 경우. 요청 형식은 정상이나 결과가
    // 처리 불가한 비즈니스 상태라 422(WALLET4002/4003과 동일 사상). 견적 생성 시 fail-fast로 차단.
    AMOUNT_TOO_SMALL(HttpStatus.UNPROCESSABLE_ENTITY, "EXCHANGE4003", "환전 금액이 너무 작습니다. (수령액이 0)");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
