package com.gb.community.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link DevMemberClient}의 fixture 우선 + 미스만 Real 위임 데코레이터 분기 검증.
 *
 * <p>핵심: ① fixture 히트는 위임 없이 즉시 반환(시드 글 작성자 표시 유지 — 역전 현상 방지),
 * ② 미스만 모아 위임 <b>1회</b>(N+1 금지), ③ 배치 계약(요청한 모든 id 키 포함)은 fixture+위임 merge로 유지,
 * ④ 장애 폴백은 위임받는 RealMemberClient의 fail-open이 담당(여기선 위임 결과를 그대로 신뢰).
 */
@ExtendWith(MockitoExtension.class)
class DevMemberClientTest {

    private static final String MINH = "00000000-0000-0000-0000-000000000001";
    private static final String SOKHA = "00000000-0000-0000-0000-000000000002";
    private static final String REAL_USER = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String GHOST = "99999999-9999-9999-9999-999999999999";

    @Mock private MemberClient delegate;

    @Test
    @DisplayName("getMember: fixture 히트는 위임 없이 고정 닉네임을 즉시 반환한다")
    void getMember_fixture_히트_위임없음() {
        DevMemberClient client = new DevMemberClient(delegate);

        MemberInfo result = client.getMember(MINH);

        assertThat(result.nickname()).isEqualTo("Minh");
        assertThat(result.isVerified()).isTrue();
        assertThat(result.trustGrade()).isEqualTo("VERIFIED");   // 이슈 #194 — fixture에 등급 포함
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("getMember: fixture 미스(실회원)는 Real에 위임해 실제 닉네임을 반환한다")
    void getMember_미스_Real위임() {
        DevMemberClient client = new DevMemberClient(delegate);
        given(delegate.getMember(REAL_USER)).willReturn(new MemberInfo("실제닉네임", true));

        MemberInfo result = client.getMember(REAL_USER);

        assertThat(result.nickname()).isEqualTo("실제닉네임");
        verify(delegate).getMember(REAL_USER);
    }

    @Test
    @DisplayName("getMembers: fixture 히트를 분리하고 미스만 모아 1회 배치 위임 후 merge한다(N+1 금지)")
    void getMembers_히트분리_미스만_1회위임_merge() {
        DevMemberClient client = new DevMemberClient(delegate);
        given(delegate.getMembers(List.of(REAL_USER, GHOST))).willReturn(Map.of(
                REAL_USER, new MemberInfo("실제닉네임", false),
                GHOST, RealMemberClient.FALLBACK));

        Map<String, MemberInfo> result =
                client.getMembers(List.of(MINH, SOKHA, REAL_USER, GHOST));

        // 배치 계약: 요청한 모든 id가 키로 포함 — fixture 2 + 위임 2.
        assertThat(result).containsOnlyKeys(MINH, SOKHA, REAL_USER, GHOST);
        assertThat(result.get(MINH).nickname()).isEqualTo("Minh");
        assertThat(result.get(SOKHA).nickname()).isEqualTo("Sokha");
        assertThat(result.get(REAL_USER).nickname()).isEqualTo("실제닉네임");
        assertThat(result.get(GHOST).nickname()).isEqualTo("Unknown");
        // 미스만 모은 1회 위임 — fixture id는 위임 인자에 섞이지 않는다.
        verify(delegate).getMembers(List.of(REAL_USER, GHOST));
    }

    @Test
    @DisplayName("getMembers: 전부 fixture 히트면 위임하지 않는다")
    void getMembers_전부_fixture_위임없음() {
        DevMemberClient client = new DevMemberClient(delegate);

        Map<String, MemberInfo> result = client.getMembers(List.of(MINH, SOKHA));

        assertThat(result).containsOnlyKeys(MINH, SOKHA);
        verify(delegate, never()).getMembers(anyCollection());
    }

    @Test
    @DisplayName("getMembers: 중복 id는 distinct로 키 1개")
    void getMembers_중복_distinct() {
        DevMemberClient client = new DevMemberClient(delegate);

        Map<String, MemberInfo> result = client.getMembers(List.of(MINH, MINH));

        assertThat(result).containsOnlyKeys(MINH);
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("getMembers: 빈 입력이면 빈 맵, 위임 없음")
    void getMembers_빈입력() {
        DevMemberClient client = new DevMemberClient(delegate);

        assertThat(client.getMembers(List.of())).isEmpty();
        verifyNoInteractions(delegate);
    }
}
