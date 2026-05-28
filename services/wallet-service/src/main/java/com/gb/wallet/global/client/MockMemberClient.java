package com.gb.wallet.global.client;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발용 Mock {@link MemberClient}. member-service 미구현 상태의 임시 구현이다.
 * 운영 전환 시 RealMemberClient(@Profile("prod"))로 교체된다.
 *
 * <p>없는 {@code userPublicId}로 조회 시 null이 아니라 fallback {@link MemberInfo}를 반환한다
 * — 호출 Service가 null 분기 없이 평탄하게 매핑할 수 있도록.
 *
 * <p>테스트 시 transactions/wallets 테이블에 동일한 user_public_id를 넣어두면 매칭된다.
 */
@Component
@Profile("dev")
public class MockMemberClient implements MemberClient {

    /** 개발용 고정 회원 데이터. 키는 wallets.user_public_id와 1:1로 맞춰 사용한다. */
    private static final Map<String, MemberInfo> FIXTURES = Map.of(
            "11111111-1111-1111-1111-111111111111",
                    new MemberInfo("11111111-1111-1111-1111-111111111111", "Linh",  "VN", true,  "GREEN"),
            "22222222-2222-2222-2222-222222222222",
                    new MemberInfo("22222222-2222-2222-2222-222222222222", "Maria", "PH", true,  "BLUE"),
            "33333333-3333-3333-3333-333333333333",
                    new MemberInfo("33333333-3333-3333-3333-333333333333", "Hieu",  "VN", false, "YELLOW"),
            "44444444-4444-4444-4444-444444444444",
                    new MemberInfo("44444444-4444-4444-4444-444444444444", "John",  "KR", true,  "GREEN"),
            "55555555-5555-5555-5555-555555555555",
                    new MemberInfo("55555555-5555-5555-5555-555555555555", "Sun",   "PH", false, "PURPLE"));

    @Override
    public MemberInfo getMember(String userPublicId) {
        MemberInfo found = FIXTURES.get(userPublicId);
        if (found != null) {
            return found;
        }
        // fallback: 없는 회원도 안전하게 표시 가능한 형태로 반환. user_public_id는 그대로 echo.
        return new MemberInfo(userPublicId, "Unknown", "UNK", false, "GREEN");
    }
}
