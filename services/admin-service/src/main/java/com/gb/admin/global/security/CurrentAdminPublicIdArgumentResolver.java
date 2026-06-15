package com.gb.admin.global.security;

import com.gb.admin.global.config.AdminSecurityPolicy;
import com.gb.common.exception.AuthErrorCode;
import com.gb.common.exception.BusinessException;
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
 * <p><b>인가 활성 (stage/prod, {@link AdminSecurityPolicy#isAdminGroupEnforced()}):</b> 토큰이 없거나
 * {@code public_id} 가 비어 있으면 {@code AUTH4011} 로 fail-fast 한다 (member-service 리졸버와 동형). 보호
 * 경로 ({@code /api/v1/admin/**}) 는 필터에서 이미 인증·그룹을 막으므로 토큰 부재 분기는 사실상 도달 불가이며,
 * {@code public_id} 누락 (pre-token Lambda 미동작 등) 시 placeholder 운영자로 감사 로그가 오염되는 비대칭을
 * 막는다 (non-repudiation).
 *
 * <p><b>무인증 콘솔 (dev/local, 인가 비활성):</b> SecurityContext에 JWT가 없을 수 있으므로
 * {@link #FALLBACK_ADMIN_PUBLIC_ID} (placeholder 운영자) 로 폴백한다 — 액션은 수행되고 audit_log엔
 * placeholder가 남는다. 인가를 켜면 위의 fail-fast 경로로 전환된다.
 */
@Component
public class CurrentAdminPublicIdArgumentResolver implements HandlerMethodArgumentResolver {

    /** IdP가 토큰에 실어 보내는 회원 식별 claim 이름(정확히 일치해야 함). */
    public static final String CLAIM_PUBLIC_ID = "public_id";

    /** no-login 콘솔에서 토큰이 없을 때 audit_log에 남길 placeholder 운영자 ID(UUID 형식 sentinel). */
    public static final String FALLBACK_ADMIN_PUBLIC_ID = "00000000-0000-0000-0000-000000000000";

    private final AdminSecurityPolicy securityPolicy;

    public CurrentAdminPublicIdArgumentResolver(AdminSecurityPolicy securityPolicy) {
        this.securityPolicy = securityPolicy;
    }

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
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            // 인가 활성: 토큰 없으면 fail-fast(AUTH4011). 무인증 콘솔(dev): placeholder 운영자로 폴백.
            if (securityPolicy.isAdminGroupEnforced()) {
                throw new BusinessException(AuthErrorCode.UNAUTHORIZED);
            }
            return FALLBACK_ADMIN_PUBLIC_ID;
        }

        Jwt jwt = jwtAuthentication.getToken();
        String adminPublicId = jwt.getClaimAsString(CLAIM_PUBLIC_ID);
        if (!StringUtils.hasText(adminPublicId)) {
            // 토큰은 있으나 public_id 누락: 인가 활성 시 placeholder 귀속(감사 오염)을 막기 위해 fail-fast.
            if (securityPolicy.isAdminGroupEnforced()) {
                throw new BusinessException(AuthErrorCode.UNAUTHORIZED);
            }
            return FALLBACK_ADMIN_PUBLIC_ID;
        }
        return adminPublicId;
    }
}
