package com.gb.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * {@link CognitoGroupJwtAuthenticationConverter} 검증 — {@code cognito:groups} 가 {@code ROLE_<group>} 으로
 * 정확히 승격되는지, 신뢰 클레임이 한정되는지 ({@code groups} 는 기본 무시), 그리고 어떤 깨진 클레임 형태에도
 * 예외 없이 fail-closed (권한 없음) 인지 본다.
 */
class CognitoGroupJwtAuthenticationConverterTest {

    private final CognitoGroupJwtAuthenticationConverter converter =
            new CognitoGroupJwtAuthenticationConverter();

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("cognito-sub")
                .claim("public_id", "11111111-1111-1111-1111-111111111111");
    }

    private static List<String> authorities(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream().map(a -> a.getAuthority()).toList();
    }

    @Test
    @DisplayName("cognito:groups=[admin] → ROLE_admin 권한 + JwtAuthenticationToken 반환")
    void cognito그룹_admin_ROLE로_승격() {
        Jwt jwt = baseJwt().claim("cognito:groups", List.of("admin")).build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(authorities(token)).containsExactly("ROLE_admin");
    }

    @Test
    @DisplayName("그룹 클레임 없음 → 권한 비어 있음 (인증은 되지만 관리자 아님 → 보호 경로 403)")
    void 그룹없음_권한_빈() {
        AbstractAuthenticationToken token = converter.convert(baseJwt().build());

        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("여러 그룹 → 각각 ROLE_ 접두로 매핑 (중복 제거)")
    void 여러그룹_각각_ROLE로() {
        Jwt jwt = baseJwt().claim("cognito:groups", List.of("admin", "admin", "cs")).build();

        assertThat(authorities(converter.convert(jwt)))
                .containsExactlyInAnyOrder("ROLE_admin", "ROLE_cs");
    }

    @Test
    @DisplayName("기본 변환기는 Authentik groups 클레임을 신뢰하지 않는다 (stage에서 출처 한정)")
    void 기본은_authentik_groups_무시() {
        Jwt jwt = baseJwt().claim("groups", List.of("admin")).build();

        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("클레임 이름을 명시하면 그 클레임을 읽는다 (dev=Authentik용)")
    void 명시클레임_읽기() {
        var devConverter = new CognitoGroupJwtAuthenticationConverter("groups");
        Jwt jwt = baseJwt().claim("groups", List.of("admin")).build();

        assertThat(authorities(devConverter.convert(jwt))).containsExactly("ROLE_admin");
    }

    @Test
    @DisplayName("그룹 클레임이 단일 문자열이어도 (방어적) 매핑")
    void 단일문자열_그룹도_매핑() {
        Jwt jwt = baseJwt().claim("cognito:groups", "admin").build();

        assertThat(authorities(converter.convert(jwt))).containsExactly("ROLE_admin");
    }

    @Test
    @DisplayName("깨진 클레임 형태 (숫자·중첩객체·혼합) → 예외 없이 권한 없음 (fail-closed, 500 누수 없음)")
    void 깨진클레임_fail_closed() {
        Jwt number = baseJwt().claim("cognito:groups", 42).build();
        Jwt nested = baseJwt().claim("cognito:groups", List.of(Map.of("name", "admin"))).build();
        Jwt mixed = baseJwt().claim("cognito:groups", Arrays.asList("admin", 7, Map.of("k", "v"))).build();

        assertThatCode(() -> {
            assertThat(converter.convert(number).getAuthorities()).isEmpty();
            assertThat(converter.convert(nested).getAuthorities()).isEmpty();
            // 혼합 배열에선 문자열 원소만 신뢰한다 (숫자·객체 원소는 무시).
            assertThat(authorities(converter.convert(mixed))).containsExactly("ROLE_admin");
        }).doesNotThrowAnyException();
    }
}
