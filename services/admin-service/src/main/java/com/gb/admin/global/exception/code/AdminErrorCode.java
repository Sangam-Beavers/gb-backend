package com.gb.admin.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * admin-service 도메인 에러 코드.
 *
 * <p>Phase 1에서는 자체 도메인 코드를 최소화한다 — 미구현 cross-service 경로에서 발생하는 일반 오류는
 * {@code COMMON5000}을 사용하고, 권한/인증은 {@code AUTH4011}/{@code COMMON4031}을 재사용한다(CLAUDE.md §6).
 * 진짜 도메인 대상이 있는 케이스만 신규 번호를 부여한다.
 *
 * <p>번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).
 */
@Getter
@RequiredArgsConstructor
public enum AdminErrorCode implements ErrorCode {

    ADMIN_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4001", "존재하지 않는 관리자입니다."),

    // TODO: 아래 4002~4006은 app-admin-service(AppAdminErrorCode)로 이동 완료.
    // admin-service/domain/ 하위의 notice·faq·feePolicy·exchangeRatePolicy·serviceSetting 패키지를
    // 삭제하면 아래 코드들도 함께 제거할 것.
    @Deprecated NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4002", "존재하지 않는 공지사항입니다."),
    @Deprecated FAQ_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4003", "존재하지 않는 FAQ입니다."),
    @Deprecated FEE_POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4004", "존재하지 않는 수수료 정책입니다."),
    @Deprecated EXCHANGE_RATE_POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4005", "존재하지 않는 환율 정책입니다."),
    @Deprecated SERVICE_SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "ADMIN4006", "존재하지 않는 서비스 설정입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
