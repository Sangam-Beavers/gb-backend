package com.gb.common.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 성공 응답에서 사용하는 상태/메시지 정의.
 */
@Getter
@RequiredArgsConstructor
public enum SuccessStatus {

    OK(HttpStatus.OK, "요청이 성공적으로 처리되었습니다."),
    CREATED(HttpStatus.CREATED, "성공적으로 생성되었습니다.");

    private final HttpStatus httpStatus;
    private final String message;
}
