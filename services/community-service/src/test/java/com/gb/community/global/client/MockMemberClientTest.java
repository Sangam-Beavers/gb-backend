package com.gb.community.global.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MockMemberClient#getMembers} 배치 조회 계약 검증(C-O3).
 *
 * <p>핵심 계약: 반환 맵은 <b>요청한 모든 id를 키로 포함</b>하고, 미존재 id는 {@link MockMemberClient#getMember}와
 * 동일하게 fallback("Unknown")으로 채운다 — 호출 측(게시글/관심글/댓글 목록)의 {@code map.get(id)} null 부담 제거.
 * RealMemberClient 전환 시에도 이 계약을 지켜야 한다(인터페이스 javadoc).
 */
class MockMemberClientTest {

    private final MockMemberClient client = new MockMemberClient();

    private static final String MINH = "00000000-0000-0000-0000-000000000001";
    private static final String SOKHA = "00000000-0000-0000-0000-000000000002";
    private static final String UNKNOWN = "99999999-9999-9999-9999-999999999999";

    @Test
    @DisplayName("getMembers: 요청한 모든 id를 키로 포함하고, 미존재 id는 fallback(Unknown, false)으로 채운다")
    void getMembers_모든_id_포함_미존재는_fallback() {
        Map<String, MemberInfo> result = client.getMembers(List.of(MINH, UNKNOWN));

        assertThat(result).containsOnlyKeys(MINH, UNKNOWN);
        assertThat(result.get(MINH).nickname()).isEqualTo("Minh");
        assertThat(result.get(MINH).isVerified()).isTrue();
        assertThat(result.get(UNKNOWN).nickname()).isEqualTo("Unknown");
        assertThat(result.get(UNKNOWN).isVerified()).isFalse();
    }

    @Test
    @DisplayName("getMembers: 중복 id는 distinct로 키 1개, 값은 getMember 단건과 동일")
    void getMembers_중복_distinct() {
        Map<String, MemberInfo> result = client.getMembers(List.of(SOKHA, SOKHA));

        assertThat(result).containsOnlyKeys(SOKHA);
        assertThat(result.get(SOKHA)).isEqualTo(client.getMember(SOKHA));
    }

    @Test
    @DisplayName("getMembers: 빈 입력이면 빈 맵")
    void getMembers_빈입력() {
        assertThat(client.getMembers(List.of())).isEmpty();
    }
}
