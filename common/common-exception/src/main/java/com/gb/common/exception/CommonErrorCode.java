package com.gb.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 모든 서비스가 공유하는 공통 에러 코드.
 * 코드 형식: {DOMAIN}{4자리숫자}. 서버 오류는 도메인 코드 신설 없이 COMMON5000을 사용한다.
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON4001", "요청 값이 올바르지 않습니다."),
    MISSING_REQUIRED_FIELD(HttpStatus.BAD_REQUEST, "COMMON4002", "필수 입력 항목이 누락되었습니다."),
    // 사문화: 인증 실패(JWT 누락/무효) 401은 common-security RestAuthenticationEntryPoint가 AUTH4011로 처리한다
    // (CLAUDE §6·§9). 이 COMMON4011은 현재 코드 사용처가 없는 예약 코드다 — 외부 참조 위험 때문에 제거하지 않고
    // 남겨두되, 신규 401 경로는 AUTH4011을 쓴다.
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON4011", "인증 정보가 유효하지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON4031", "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON4041", "존재하지 않는 리소스입니다."),
    // 405/406/415 — 메서드/콘텐츠 협상 오류 전용 분기(10D common-modules-1). 과거엔 전용 핸들러가 없어
    // catch-all로 500 COMMON5000이 됐다(클라이언트 잘못을 서버 오류로 오인 + 거짓 알람). conventions §9 등재.
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON4051", "허용되지 않은 HTTP 메서드입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "COMMON4061", "응답할 수 없는 Accept 형식입니다."),
    RESOURCE_ALREADY_EXISTS(HttpStatus.CONFLICT, "COMMON4091", "이미 존재하는 리소스입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON4151", "지원하지 않는 요청 본문 형식입니다."),
    UNPROCESSABLE_ENTITY(HttpStatus.UNPROCESSABLE_ENTITY, "COMMON4221", "처리할 수 없는 요청입니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "COMMON4291", "요청 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON5000", "서버 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COMMON5031", "일시적으로 처리할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
