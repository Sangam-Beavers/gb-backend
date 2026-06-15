package com.gb.member.global.client;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 테스트 환경용 {@link AppAdminClient}(이슈 #244).
 * app-admin-service에 연결하지 않고 기본 크레딧값 3을 반환한다.
 * {@link RealAppAdminClient}가 {@code !test}이므로 프로파일 충돌 없이 1:1 대응된다.
 */
@Component
@Profile("test")
public class MockAppAdminClient implements AppAdminClient {

    private static final int DEFAULT_CREDIT = 3;

    @Override
    public int getDocAnalysisCredit() {
        return DEFAULT_CREDIT;
    }
}
