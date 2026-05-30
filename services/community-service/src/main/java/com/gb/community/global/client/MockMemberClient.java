package com.gb.community.global.client;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발용 Mock {@link MemberClient}. member-service 미구현 상태의 임시 구현이다.
 * 운영 전환 시 RealMemberClient(@Profile("!dev"))로 교체된다.
 *
 * <p>FIXTURES 키는 {@code DevDataInitializer}가 시드하는 게시글 작성자 UUID 3개와 1:1로 맞춘다.
 * 시드 글의 작성자를 조회하면 고정 닉네임/인증여부/온도가 반환돼 챗봇 데모/화면이 자연스럽게 채워진다.
 *
 * <p>미존재 시 fallback {@link MemberInfo}("Unknown", false, "GREEN")를 반환한다 — 게시글 목록처럼
 * 평탄 매핑이 필요한 표시용 호출에서 null 검사 부담을 없애기 위함(검증 용도가 아니라 fail-fast 불필요).
 */
@Component
@Profile("dev")
public class MockMemberClient implements MemberClient {

    /** 작성자 미존재 시 안전 표시용 기본값. user_public_id는 응답에 노출하지 않으므로 닉네임만 채운다. */
    private static final MemberInfo FALLBACK = new MemberInfo("Unknown", false, "GREEN");

    /** 개발용 고정 회원 데이터. 키는 DevDataInitializer의 DEMO_USER_1/2/3 UUID와 1:1. */
    private static final Map<String, MemberInfo> FIXTURES = Map.of(
            "00000000-0000-0000-0000-000000000001",
                    new MemberInfo("Minh", true, "GREEN"),
            "00000000-0000-0000-0000-000000000002",
                    new MemberInfo("Sokha", false, "YELLOW"),
            "00000000-0000-0000-0000-000000000003",
                    new MemberInfo("Aung", true, "BLUE"));

    @Override
    public MemberInfo getMember(String userPublicId) {
        return FIXTURES.getOrDefault(userPublicId, FALLBACK);
    }
}