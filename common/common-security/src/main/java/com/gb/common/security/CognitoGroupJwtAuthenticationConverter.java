package com.gb.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;

/**
 * 검표원이 검증한 JWT의 그룹 클레임을 Spring Security 권한 ({@code ROLE_<group>}) 으로 승격하는 변환기.
 *
 * <p>Cognito는 사용자의 그룹 소속을 {@code cognito:groups} 클레임 (문자열 배열) 으로 access·id 토큰 모두에
 * 싣는다 (modules/cognito의 {@code user_groups} = {@code { admin = {} }} 가 SSOT). 기본 Spring 변환기는
 * {@code scope}만 권한으로 바꾸고 {@code cognito:groups}는 무시하므로, 이 변환기로 그룹을 권한에 매핑해야
 * 필터 체인의 {@code hasRole("admin")} (= {@code ROLE_admin}) 인가가 동작한다.
 *
 * <p><b>신뢰 클레임은 명시적으로 한정한다.</b> 기본 생성자는 {@link #COGNITO_GROUPS_CLAIM} 만 읽는다 —
 * 이 변환기는 Cognito (stage/prod) 검표원 경로에서만 쓰이기 때문이다. dev (Authentik) 처럼 다른 클레임으로
 * RBAC를 하려면 {@link #CognitoGroupJwtAuthenticationConverter(String...)} 로 클레임 이름을 명시해 주입한다.
 * 환경별로 신뢰하는 그룹 출처를 좁혀, 미래에 다른 클레임이 채워져도 관리자 권한이 의도치 않게 새지 않게 한다.
 *
 * <p>알 수 없는 형태의 클레임 (숫자·객체·혼합) 은 무시하고 빈 권한을 돌려준다 (fail-closed). 어떤 입력에도
 * 예외를 던지지 않는 total 함수라, 보호 경로는 권한이 없으면 403이 된다 (500 누수 없음).
 *
 * <p>반환 타입은 {@link JwtAuthenticationToken} 으로 고정한다 — 각 서비스의 {@code CurrentAdminPublicId}
 * 리졸버가 SecurityContext의 인증 객체를 {@code JwtAuthenticationToken}으로 캐스팅해 {@code public_id}
 * 클레임을 꺼내기 때문이다 (계약 유지). 상태가 없어 스레드 안전하다.
 */
public final class CognitoGroupJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    /** Cognito가 그룹 소속을 싣는 클레임 이름 (access·id 토큰 공통, 문자열 배열). */
    public static final String COGNITO_GROUPS_CLAIM = "cognito:groups";

    /** Spring의 {@code hasRole("x")} 가 기대하는 권한 접두사 ({@code ROLE_x}). */
    public static final String ROLE_PREFIX = "ROLE_";

    /** 권한으로 승격할 그룹 클레임 이름들 (신뢰 출처를 명시적으로 한정). */
    private final List<String> groupClaimNames;

    /** 기본 — Cognito 검표원 전용. {@code cognito:groups} 만 신뢰한다. */
    public CognitoGroupJwtAuthenticationConverter() {
        this(COGNITO_GROUPS_CLAIM);
    }

    /** 신뢰할 그룹 클레임 이름을 명시한다 (예: dev에서 Authentik {@code groups}). 비우면 권한 없음. */
    public CognitoGroupJwtAuthenticationConverter(String... groupClaimNames) {
        this.groupClaimNames = List.of(groupClaimNames);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String group : groupNames(jwt)) {
            if (StringUtils.hasText(group)) {
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + group));
            }
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }

    /** 신뢰 클레임들을 합쳐 그룹 이름 목록을 만든다 (없으면 빈 목록). */
    private List<String> groupNames(Jwt jwt) {
        List<String> names = new ArrayList<>();
        for (String claim : groupClaimNames) {
            names.addAll(claimAsList(jwt, claim));
        }
        return names;
    }

    /** 클레임을 문자열 목록으로 정규화한다. 배열·단일 문자열만 수용하고, 그 외 (숫자·객체) ·누락은 빈 목록. */
    private static List<String> claimAsList(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value instanceof Collection<?> collection) {
            List<String> result = new ArrayList<>(collection.size());
            for (Object item : collection) {
                // 문자열 원소만 신뢰한다 (숫자·중첩 객체 원소는 무시 — 권한 위조 방지·fail-closed).
                if (item instanceof String s) {
                    result.add(s);
                }
            }
            return result;
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return List.of(s);
        }
        return List.of();
    }
}
