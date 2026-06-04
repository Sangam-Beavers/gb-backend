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
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "TRANSFER4006", "송금 요청 횟수를 초과했습니다."),
    // 송금 PIN(별도 PIN — 방식 B라 계정 비밀번호는 미보유). 번호는 명세 §12 등록 후 확정.
    PIN_MISMATCH(HttpStatus.BAD_REQUEST, "TRANSFER4007", "송금 PIN이 일치하지 않습니다."),
    // 단기(10분)·24h 장기 잠금이 같은 코드를 공유한다. "잠시 후"는 24h 잠금엔 오해 소지라 잠금 시간에
    // 무관하게 정확한 메시지로 일반화한다(WSCH-06 후속 — 별도 코드 분기는 명세 §12 등록 후로 보류).
    PIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "TRANSFER4008", "송금 PIN 입력 횟수를 초과해 일시적으로 잠겨 있습니다."),
    PIN_NOT_SET(HttpStatus.BAD_REQUEST, "TRANSFER4009", "송금 PIN이 설정되지 않았습니다."),
    // TX-PIN — pin-verify 성공 증표(단명 마커) 없이 송금 실행/정기설정을 호출한 경우. 명세 §5/§6: 송금 전 PIN 검증 필수.
    // 428 Precondition Required = "본 요청 전에 충족해야 할 선결 조건(pin-verify)이 미충족"의 표준 의미.
    PIN_VERIFICATION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "TRANSFER4010", "송금 전 PIN 검증이 필요합니다.");

    private final HttpStatus httpStatus; // @Getter가 getHttpStatus/getCode/getMessage 생성 → ErrorCode 충족
    private final String code;
    private final String message;
}
