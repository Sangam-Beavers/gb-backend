package com.gb.wallet.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * MEMBER 도메인 에러 코드. 코드/HTTP/메시지는 API 명세 §12의 MEMBER 도메인 표를 SSOT로 한다.
 *
 * <p><b>소유권 주의</b>: MEMBER 도메인 코드는 본래 member-service 소관이나, 회원 검증 API가
 * 현재는 wallet-service 안에 위치하므로 임시로 여기에 정의한다. member-service 구현 시
 * 이 enum은 그쪽으로 이전 대상이며, wallet-service에는 코드 참조만 남게 된다.
 *
 * <p>번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER4001", "존재하지 않는 회원입니다.");

    private final HttpStatus httpStatus; // @Getter가 getHttpStatus/getCode/getMessage 생성 → ErrorCode 충족
    private final String code;
    private final String message;
}
