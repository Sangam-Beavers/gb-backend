package com.gb.admin.global.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentAdminPublicId} 파라미터에 인증 JWT의 {@code public_id} claim을 주입하는 리졸버.
 *
 * <p>OAuth2 Resource Server가 토큰을 검증하면 SecurityContext에 {@link JwtAuthenticationToken}이 담긴다.
 * 여기서 {@link Jwt}를 꺼내 custom claim {@code public_id}(관리자 대외 식별자, UUID)를 읽어 컨트롤러에
 * {@code String adminPublicId}로 바인딩한다.
 *
 * <p><b>현재(no-login 콘솔)</b>: admin-service는 SecurityConfig에서 OAuth2 검증이 비활성(permitAll)이라
 * SecurityContext에 JWT가 없다. 이때 fail-fast(AUTH4011)하면 삭제·숨김·KYC 등 운영 액션이 전부 401이 되므로,
 * 토큰이 없으면 {@link #FALLBACK_ADMIN_PUBLIC_ID}(placeholder 운영자)로 폴백한다 — 액션은 수행되고 audit_log엔
 * placeholder가 남는다. 인증(OAuth2)을 다시 켜면 실제 토큰의 {@code public_id} claim이 그대로 흐른다.
 */
@Component
public class CurrentAdminPublicIdArgumentResolver implements HandlerMethodArgumentResolver {

    /** IdP가 토큰에 실어 보내는 회원 식별 claim 이름(정확히 일치해야 함). */
    public static final String CLAIM_PUBLIC_ID = "public_id";

    /** no-login 콘솔에서 토큰이 없을 때 audit_log에 남길 placeholder 운영자 ID(UUID 형식 sentinel). */
    public static final String FALLBACK_ADMIN_PUBLIC_ID = "00000000-0000-0000-0000-000000000000";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAdminPublicId.class)
                && String.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // no-login 콘솔: JWT가 없으면 placeholder 운영자로 폴백(액션 수행 가능). 인증 재활성 시 실제 claim이 흐름.
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return FALLBACK_ADMIN_PUBLIC_ID;
        }

        Jwt jwt = jwtAuthentication.getToken();
        String adminPublicId = jwt.getClaimAsString(CLAIM_PUBLIC_ID);
        if (!StringUtils.hasText(adminPublicId)) {
            return FALLBACK_ADMIN_PUBLIC_ID;
        }
        return adminPublicId;
    }
}
