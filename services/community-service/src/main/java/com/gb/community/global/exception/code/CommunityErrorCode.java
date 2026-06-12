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
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMUNITY4002", "존재하지 않는 댓글입니다."),
    // #161 동적 번역 — 화이트리스트 외(ko/en/vi/fil 아님) 언어 거절. COMMON4001 대신 도메인 코드로 두는
    // 이유: "지원하지 않는 언어"는 입력 형식 오류가 아니라 비즈니스 제약(번역 비용·품질 화이트리스트). UI가
    // 사용자에게 표시할 메시지가 형식 오류와 다르다.
    UNSUPPORTED_LANGUAGE(HttpStatus.BAD_REQUEST, "COMMUNITY4003", "지원하지 않는 언어입니다."),
    // #161 동적 번역 — 본문 5000자 초과 거절(Bedrock 단발 호출 비용·지연 캡). 작성 상한(게시글 10000자/
    // 댓글 2000자)과는 별도 — 번역은 더 짧은 캡으로 비용 보호.
    CONTENT_TOO_LONG(HttpStatus.BAD_REQUEST, "COMMUNITY4004", "본문이 너무 깁니다."),
    // 커뮤니티 활동 제한된 회원이 글/댓글 작성 시도 시.
    COMMUNITY_BANNED(HttpStatus.FORBIDDEN, "COMMUNITY4005", "커뮤니티 활동이 제한된 계정입니다."),

    // ===== 신고 도메인 (COMMUNITY4006~) =====
    // 동일 (reporter, target_type, target_id) 조합으로 중복 신고 시도 시.
    DUPLICATE_REPORT(HttpStatus.CONFLICT, "COMMUNITY4006", "이미 신고한 콘텐츠입니다."),
    // Service에서 ReportReason.valueOf() 실패 시 — COMMON4001 대신 도메인 코드 사용(CLAUDE.md §6).
    UNSUPPORTED_REPORT_REASON(HttpStatus.BAD_REQUEST, "COMMUNITY4007", "지원하지 않는 신고 사유입니다.");

    private final HttpStatus httpStatus; // @Getter가 getHttpStatus/getCode/getMessage 생성 → ErrorCode 충족
    private final String code;
    private final String message;
}