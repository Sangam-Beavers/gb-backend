package com.gb.document.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * document-service 도메인 에러 코드. 코드/HTTP/메시지는 API 명세 §12 DOCUMENT 도메인 표를 SSOT로 한다.
 * 번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 *
 * <p>인증/권한 에러는 도메인 코드 신설하지 말고 COMMON4011(인증 실패) / COMMON4031(권한 없음)을 재사용한다.
 * (ai-chatbot-mcp.md §5 / conventions.md §12)
 */
@Getter
@RequiredArgsConstructor
public enum DocumentErrorCode implements ErrorCode {

    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "DOCUMENT4001", "존재하지 않는 문서입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
