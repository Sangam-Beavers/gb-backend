package com.gb.admin.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 관리자 그룹 인가 (RBAC) 활성 여부를 한 곳에서 결정한다 — SecurityConfig와 ArgumentResolver가 같은 값을 본다.
 *
 * <p><b>fail-closed:</b> stage/prod (Cognito가 토큰을 발급하는 실환경) 프로파일에선 플래그와 무관하게 항상 켠다.
 * 따라서 stage/prod에서 {@code app.security.admin-group-enforced} 누락·오설정 (ConfigMap/env 덮어쓰기 등) 으로
 * 무인증 콘솔로 새는 일이 구조적으로 불가능하다 — issuer-uri가 없으면 검표원이 기동에 실패해 (fail-closed) 열린
 * 채로 뜨지 않는다. 그 외 (dev/test) 는 플래그로만 켜, 무인증 콘솔·로컬 개발 흐름을 유지한다.
 */
@Component
public class AdminSecurityPolicy {

    private final boolean adminGroupEnforced;

    public AdminSecurityPolicy(
            Environment environment,
            @Value("${app.security.admin-group-enforced:false}") boolean adminGroupEnforcedFlag) {
        this.adminGroupEnforced =
                adminGroupEnforcedFlag || environment.acceptsProfiles(Profiles.of("stage", "prod"));
    }

    /** 관리자 그룹 인가 (검표원 + {@code hasRole("admin")}) 를 켤지 여부. */
    public boolean isAdminGroupEnforced() {
        return adminGroupEnforced;
    }
}
