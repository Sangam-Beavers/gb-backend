package com.gb.wallet.global.client;

import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발용 Mock {@link MemberClient}. member-service 미구현 상태의 임시 구현이다.
 * 운영 전환 시 RealMemberClient(@Profile("prod"))로 교체된다.
 *
 * <p>두 조회 메서드의 정책이 다르다:
 * <ul>
 *   <li>{@link #getMember}는 미존재 시 fallback {@link MemberInfo}("Unknown")를 반환 — 최근 송금
 *       목록처럼 평탄 매핑이 필요한 호출 측을 위함.</li>
 *   <li>{@link #findByEmail}은 미존재 시 {@link Optional#empty()} — 앱 사용자 검증 API가 명확히
 *       "없음"으로 응답할 수 있어야 하기 때문.</li>
 * </ul>
 *
 * <p>테스트 시 transactions/wallets 테이블에 동일한 user_public_id를 넣어두면 매칭된다.
 * 이메일은 아래 FIXTURES의 두 번째 컬럼(예: {@code "linh@example.com"})을 사용한다.
 */
@Component
@Profile("dev")
public class MockMemberClient implements MemberClient {

    /** 개발용 고정 회원 데이터. 키는 wallets.user_public_id와 1:1로 맞춰 사용한다. */
    private static final Map<String, MemberInfo> FIXTURES = Map.of(
            "11111111-1111-1111-1111-111111111111",
                    new MemberInfo("11111111-1111-1111-1111-111111111111", "linh@example.com",
                            "Linh",  "VN", true),
            "22222222-2222-2222-2222-222222222222",
                    new MemberInfo("22222222-2222-2222-2222-222222222222", "maria@example.com",
                            "Maria", "PH", true),
            "33333333-3333-3333-3333-333333333333",
                    new MemberInfo("33333333-3333-3333-3333-333333333333", "hieu@example.com",
                            "Hieu",  "VN", false),
            "44444444-4444-4444-4444-444444444444",
                    new MemberInfo("44444444-4444-4444-4444-444444444444", "john@example.com",
                            "John",  "KR", true),
            "55555555-5555-5555-5555-555555555555",
                    new MemberInfo("55555555-5555-5555-5555-555555555555", "sun@example.com",
                            "Sun",   "PH", false));

    @Override
    public MemberInfo getMember(String userPublicId) {
        MemberInfo found = FIXTURES.get(userPublicId);
        if (found != null) {
            return found;
        }
        // fallback: 없는 회원도 안전하게 표시 가능한 형태로 반환. user_public_id는 그대로 echo.
        return new MemberInfo(userPublicId, null, "Unknown", "UNK", false);
    }

    @Override
    public Optional<MemberInfo> findByEmail(String email) {
        // FIXTURES 규모가 작아 선형 탐색으로 충분. 운영용 구현은 별도 인덱스/캐시 필요 없음 — 실제 호출은 HTTP.
        return FIXTURES.values().stream()
                .filter(m -> email != null && email.equalsIgnoreCase(m.email()))
                .findFirst();
    }
}
