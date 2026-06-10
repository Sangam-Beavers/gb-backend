package com.gb.appadmin.global.security;

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
 * {@link CurrentAdminPublicId} 파라미터에 JWT의 {@code public_id} claim을 주입하는 리졸버.
 * claim 누락 시 AUTH4011로 fail-fast(CLAUDE.md §9).
 */
@Component
public class CurrentAdminPublicIdArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String CLAIM_PUBLIC_ID = "public_id";

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
            throw new BusinessException(AuthErrorCode.UNAUTHORIZED);
        }

        Jwt jwt = jwtAuthentication.getToken();
        String adminPublicId = jwt.getClaimAsString(CLAIM_PUBLIC_ID);
        if (!StringUtils.hasText(adminPublicId)) {
            throw new BusinessException(AuthErrorCode.UNAUTHORIZED);
        }
        return adminPublicId;
    }
}
