package com.gb.community.global.client;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * (구) 개발용 Mock {@link MemberClient} — member-service 구현 완료로 역할 종료. <b>삭제 후보.</b>
 *
 * <p>member-service 표시정보 API(auth §13) 신설에 따라 {@link RealMemberClient}(stage·prod)와
 * {@link DevMemberClient}(dev — FIXTURES 이전됨)로 대체됐다. dev 프로파일에서 DevMemberClient와의
 * 빈 충돌을 막기 위해 빈 등록({@code @Component @Profile("dev")})을 해제했다. 파일 삭제는 사람이
 * 확인 후 수행한다(CLAUDE.md §12 — 임의 삭제 금지. {@code MockMemberClientTest}도 함께 삭제 대상).
 *
 * @deprecated {@link DevMemberClient}(fixture 우선 + Real 위임)로 대체됨.
 */
@Deprecated
public class MockMemberClient implements MemberClient {

    /** 작성자 미존재 시 안전 표시용 기본값. user_public_id는 응답에 노출하지 않으므로 닉네임만 채운다. */
    private static final MemberInfo FALLBACK = new MemberInfo("Unknown", false);

    /** 개발용 고정 회원 데이터. 키는 DevDataInitializer의 DEMO_USER_1/2/3 UUID와 1:1. */
    private static final Map<String, MemberInfo> FIXTURES = Map.of(
            "00000000-0000-0000-0000-000000000001",
                    new MemberInfo("Minh", true),
            "00000000-0000-0000-0000-000000000002",
                    new MemberInfo("Sokha", false),
            "00000000-0000-0000-0000-000000000003",
                    new MemberInfo("Aung", true));

    @Override
    public MemberInfo getMember(String userPublicId) {
        return FIXTURES.getOrDefault(userPublicId, FALLBACK);
    }

    @Override
    public Map<String, MemberInfo> getMembers(Collection<String> userPublicIds) {
        // 요청한 모든 id를 키로 채운다(중복 제거). 미존재는 getMember와 동일 fallback — 계약 일관.
        // Mock은 메모리 조회라 배치 이득이 없지만, 호출 측 코드는 이미 1회 호출로 정리돼 RealMemberClient 전환 시 그대로 동작한다.
        return userPublicIds.stream()
                .distinct()
                .collect(Collectors.toMap(Function.identity(), this::getMember));
    }
}