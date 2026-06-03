package com.gb.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증(AUTH) 도메인 에러 코드. 코드/HTTP/메시지는 conventions.md §9 인증(AUTH) 표를 SSOT로 한다.
 * 번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 *
 * <p>AUTH 코드는 JWT 검증 필터(common-security 검표원) 등 인증 흐름
 * 전반에 걸쳐 쓰이는 cross-cutting 개념이라 common-exception에 둔다. 한 enum을 모든 모듈이 참조함으로써
 * 응답 코드 분기가 일관되도록 보장한다.
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    /**
     * 액세스 토큰 누락·위조·만료 등 **요청 인증 실패의 통합 코드** — JWT 검증 필터 전용.
     *
     * <p>Authorization 헤더 없음, 서명 무효, exp 만료, 형식 깨짐 등 모든 토큰 검증 실패에 동일 코드로
     * 응답한다. 분리하면 공격자가 토큰 상태(존재 여부·만료 여부 등)를 추론할 단서를 얻을 수 있어
     * 의도적으로 한 코드에 통일한다. 로그인 시점의 자격증명 검증은 외부 IdP(방식 B) 소관이라 본 서비스 코드엔 없다.
     */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH4011", "인증이 필요합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
