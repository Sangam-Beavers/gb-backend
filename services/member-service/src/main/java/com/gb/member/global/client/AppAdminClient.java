package com.gb.member.global.client;

/**
 * app-admin-service 연동 클라이언트(이슈 #244).
 * 가입 시 신규 회원에게 부여할 서류 분석 기본 크레딧을 조회한다.
 *
 * <p>구현체:
 * <ul>
 *   <li>{@link MockAppAdminClient} — test 프로파일: 외부 호출 없이 기본값 3 반환</li>
 *   <li>{@link RealAppAdminClient} — !test 프로파일: app-admin GET /doc-analysis-credit 호출,
 *       장애 시 기본값 3 fall-open</li>
 * </ul>
 */
public interface AppAdminClient {

    /**
     * 신규 가입자에게 부여할 서류 분석 기본 크레딧 수를 반환한다.
     * 구현체는 조회 실패 시 기본값(3)을 반환한다(fail-open — 서비스 장애가 가입을 막지 않도록).
     */
    int getDocAnalysisCredit();
}
