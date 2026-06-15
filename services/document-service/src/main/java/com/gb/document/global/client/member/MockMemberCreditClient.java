package com.gb.document.global.client.member;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 테스트용 크레딧 차감 클라이언트. 항상 성공(no-op).
 *
 * <p>테스트 컨텍스트에서 member-service 없이 document-service를 띄울 수 있도록
 * {@code @Profile("test")}로 등록한다. {@link RealMemberCreditClient}는 {@code !test} 조건.
 */
@Component
@Profile("test")
public class MockMemberCreditClient implements MemberCreditClient {

    @Override
    public void useCredit(String userPublicId) {
        // no-op: 테스트에서 항상 크레딧 차감 성공으로 처리
    }
}
