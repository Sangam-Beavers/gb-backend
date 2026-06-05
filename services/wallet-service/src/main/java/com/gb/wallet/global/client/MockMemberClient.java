package com.gb.wallet.global.client;

import java.util.Map;
import java.util.Optional;

/**
 * (구) 개발용 Mock {@link MemberClient} — member-service 구현 완료로 역할 종료. <b>삭제 후보.</b>
 *
 * <p>member-service 표시정보 API(auth §13) 신설에 따라 {@link RealMemberClient}(stage·prod)와
 * {@link DevMemberClient}(dev — FIXTURES 5명 이전됨)로 대체됐다. 기존 {@code @Profile({"dev","stage"})}
 * 점유 중 dev는 DevMemberClient와의 빈 충돌을 막기 위해, stage는 Real로 전환(의도된 변경)하기 위해
 * 빈 등록을 해제했다. 파일 삭제는 사람이 확인 후 수행한다(CLAUDE.md §12 — 임의 삭제 금지).
 *
 * @deprecated {@link DevMemberClient}(fixture 우선 + Real 위임) / {@link RealMemberClient}로 대체됨.
 */
@Deprecated
public class MockMemberClient implements MemberClient {

    /** 개발용 고정 회원 데이터. 키는 wallets.user_public_id와 1:1로 맞춰 사용한다. */
    private static final Map<String, MemberInfo> FIXTURES = Map.of(
            "11111111-1111-1111-1111-111111111111",
                    new MemberInfo("11111111-1111-1111-1111-111111111111", "linh@example.com",
                            "Nguyen Thi Linh", "Linh", "VN", true),
            "22222222-2222-2222-2222-222222222222",
                    new MemberInfo("22222222-2222-2222-2222-222222222222", "maria@example.com",
                            "Maria Santos", "Maria", "PH", true),
            "33333333-3333-3333-3333-333333333333",
                    new MemberInfo("33333333-3333-3333-3333-333333333333", "hieu@example.com",
                            "Tran Van Hieu", "Hieu", "VN", false),
            "44444444-4444-4444-4444-444444444444",
                    new MemberInfo("44444444-4444-4444-4444-444444444444", "john@example.com",
                            "John Doe", "John", "KR", true),
            "55555555-5555-5555-5555-555555555555",
                    new MemberInfo("55555555-5555-5555-5555-555555555555", "sun@example.com",
                            "Park Sun", "Sun", "PH", false));

    @Override
    public MemberInfo getMember(String userPublicId) {
        MemberInfo found = FIXTURES.get(userPublicId);
        if (found != null) {
            return found;
        }
        // fallback: 없는 회원도 안전하게 표시 가능한 형태로 반환. user_public_id는 그대로 echo.
        return new MemberInfo(userPublicId, null, "Unknown", "Unknown", "UNK", false);
    }

    @Override
    public Optional<MemberInfo> findByEmail(String email) {
        // FIXTURES 규모가 작아 선형 탐색으로 충분. 운영용 구현은 별도 인덱스/캐시 필요 없음 — 실제 호출은 HTTP.
        return FIXTURES.values().stream()
                .filter(m -> email != null && email.equalsIgnoreCase(m.email()))
                .findFirst();
    }
}
