package com.gb.admin.global.security;

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
 * <p>인증이 없거나(=보호 경로인데 토큰 없음) claim이 비어 있으면(=IdP Property Mapping 누락) {@code AUTH4011}로
 * fail-fast 한다. 조용히 null을 흘려보내 도메인 로직에서 엉뚱한 에러로 번지지 않게 한다.
 */
@Component
public class CurrentAdminPublicIdArgumentResolver implements HandlerMethodArgumentResolver {

    /** IdP가 토큰에 실어 보내는 회원 식별 claim 이름(정확히 일치해야 함). */
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
