package com.gb.community.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * community-service 도메인 에러 코드. 코드/HTTP/메시지는 API 명세(community/api-spec.md
 * "커뮤니티 에러 코드 메모")를 SSOT로 한다. 번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 *
 * <p>도메인 고유 에러: {@link #POST_NOT_FOUND}(COMMUNITY4001), {@link #COMMENT_NOT_FOUND}(COMMUNITY4002).
 * 잘못된 category/sort 값·필수 누락은 {@code COMMON4001}, 권한 없음은 {@code COMMON4031}을
 * 재사용한다(CommonErrorCode, 도메인 코드 신설 금지).
 *
 * <p>{@link #COMMENT_NOT_FOUND}는 (1) 댓글 publicId가 존재하지 않거나 (2) 이미 soft delete된 경우,
 * 그리고 (3) URL path의 postId와 댓글의 실제 post가 불일치하는 경우에 모두 사용한다 — 의미상 모두
 * "이 게시글에 그런 댓글 없음"으로 통일(권한 문제가 아님).
 */
@Getter
@RequiredArgsConstructor
public enum CommunityErrorCode implements ErrorCode {

    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMUNITY4001", "존재하지 않는 게시글입니다."),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMUNITY4002", "존재하지 않는 댓글입니다.");

    private final HttpStatus httpStatus; // @Getter가 getHttpStatus/getCode/getMessage 생성 → ErrorCode 충족
    private final String code;
    private final String message;
}