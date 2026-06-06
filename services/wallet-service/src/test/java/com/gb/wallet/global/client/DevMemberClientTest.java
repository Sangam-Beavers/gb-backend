package com.gb.wallet.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link DevMemberClient}(wallet)의 fixture 우선 + 미스만 Real 위임 분기 검증.
 *
 * <p>핵심: ① fixture 5명 히트는 위임 없이 기존 dev 시나리오 그대로(시드 거래 표시·fixture 이메일 검증),
 * ② 미스(실회원)는 Real 위임 — dev에서 실회원 validate-member가 동작(수용 기준),
 * ③ findByEmail의 fail-fast(COMMON5000)는 본 클래스가 삼키지 않고 전파(검증 용도 — 조용한 가짜 데이터 금지).
 */
@ExtendWith(MockitoExtension.class)
class DevMemberClientTest {

    private static final String LINH = "11111111-1111-1111-1111-111111111111";
    private static final String REAL_USER = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Mock private MemberClient delegate;

    @Test
    @DisplayName("getMember: fixture 히트는 위임 없이 고정 데이터를 즉시 반환한다")
    void getMember_fixture_히트_위임없음() {
        DevMemberClient client = new DevMemberClient(delegate);

        MemberInfo result = client.getMember(LINH);

        assertThat(result.nickname()).isEqualTo("Linh");
        assertThat(result.name()).isEqualTo("Nguyen Thi Linh");
        assertThat(result.nationality()).isEqualTo("VN");
        assertThat(result.isVerified()).isTrue();
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("getMember: fixture 미스(실회원)는 Real에 위임한다 — dev에서 실회원 수신자 표시")
    void getMember_미스_Real위임() {
        DevMemberClient client = new DevMemberClient(delegate);
        MemberInfo real = new MemberInfo(REAL_USER, null, "Real Name", "RealNick", "KR", true);
        given(delegate.getMember(REAL_USER)).willReturn(real);

        assertThat(client.getMember(REAL_USER)).isEqualTo(real);
        verify(delegate).getMember(REAL_USER);
    }

    @Test
    @DisplayName("findMember: fixture 히트는 present, 미스는 Real에 위임(empty 그대로 — 폴백 객체 없음)")
    void findMember_fixture_히트_미스_위임() {
        DevMemberClient client = new DevMemberClient(delegate);
        given(delegate.findMember(REAL_USER)).willReturn(Optional.empty());

        assertThat(client.findMember(LINH)).isPresent();      // fixture 히트
        assertThat(client.findMember(REAL_USER)).isEmpty();   // 위임 — 미존재·장애 = empty
        verify(delegate).findMember(REAL_USER);
    }

    @Test
    @DisplayName("getMembers: fixture 히트는 분리하고 미스만 모아 Real에 1회 배치 위임 후 merge(계약: 모든 요청 id 키 포함)")
    void getMembers_fixture_분리_미스만_배치위임() {
        DevMemberClient client = new DevMemberClient(delegate);
        MemberInfo real = new MemberInfo(REAL_USER, null, "Real Name", "RealNick", "KR", true);
        given(delegate.getMembers(List.of(REAL_USER))).willReturn(Map.of(REAL_USER, real));

        Map<String, MemberInfo> result = client.getMembers(List.of(LINH, REAL_USER));

        assertThat(result).containsOnlyKeys(LINH, REAL_USER);
        assertThat(result.get(LINH).nickname()).isEqualTo("Linh");   // fixture 히트
        assertThat(result.get(REAL_USER)).isEqualTo(real);           // Real 위임분
        verify(delegate, times(1)).getMembers(List.of(REAL_USER));   // 미스만, 배치 1회
        verify(delegate, never()).getMember(anyString());
    }

    @Test
    @DisplayName("getMembers: 전부 fixture 히트면 위임 자체가 없다")
    void getMembers_전부_fixture면_위임없음() {
        DevMemberClient client = new DevMemberClient(delegate);

        Map<String, MemberInfo> result = client.getMembers(List.of(LINH));

        assertThat(result).containsOnlyKeys(LINH);
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("findByEmail: fixture 이메일 히트(대소문자 무시)는 위임 없이 반환한다 — 기존 dev 검증 시나리오 유지")
    void findByEmail_fixture_히트_대소문자무시() {
        DevMemberClient client = new DevMemberClient(delegate);

        Optional<MemberInfo> result = client.findByEmail("LINH@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().userPublicId()).isEqualTo(LINH);
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("findByEmail: fixture 미스(실회원 이메일)는 Real에 위임한다 — dev에서 실회원 validate-member 성공")
    void findByEmail_미스_Real위임_실회원_검증() {
        DevMemberClient client = new DevMemberClient(delegate);
        MemberInfo real = new MemberInfo(REAL_USER, "real@example.com", "Real Name", "RealNick", "KR", true);
        given(delegate.findByEmail("real@example.com")).willReturn(Optional.of(real));

        assertThat(client.findByEmail("real@example.com")).contains(real);
    }

    @Test
    @DisplayName("findByEmail: 위임 결과가 empty(404 MEMBER4001)면 그대로 empty — 호출 측이 MEMBER4001로 변환")
    void findByEmail_미스_위임_empty() {
        DevMemberClient client = new DevMemberClient(delegate);
        given(delegate.findByEmail("ghost@example.com")).willReturn(Optional.empty());

        assertThat(client.findByEmail("ghost@example.com")).isEmpty();
    }

    @Test
    @DisplayName("findByEmail: 위임의 fail-fast(COMMON5000)는 삼키지 않고 전파한다 — 장애를 '없는 회원'으로 오인 금지")
    void findByEmail_장애_fail_fast_전파() {
        DevMemberClient client = new DevMemberClient(delegate);
        given(delegate.findByEmail("real@example.com"))
                .willThrow(new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.findByEmail("real@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("findByEmail: null 이메일은 fixture 매칭 없이 위임된다(NPE 없음)")
    void findByEmail_null_안전() {
        DevMemberClient client = new DevMemberClient(delegate);
        given(delegate.findByEmail(null)).willReturn(Optional.empty());

        assertThat(client.findByEmail(null)).isEmpty();
    }
}
