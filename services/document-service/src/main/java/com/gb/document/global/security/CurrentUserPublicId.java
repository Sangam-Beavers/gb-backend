package com.gb.document.global.security;

import io.swagger.v3.oas.annotations.Parameter;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 인증된 사용자의 {@code public_id}(UUID)를 주입한다.
 *
 * <p>OAuth2 Resource Server가 검증한 JWT의 custom claim {@code public_id}를
 * {@link CurrentUserPublicIdArgumentResolver}가 꺼내 {@code String}으로 바인딩한다.
 * 인증 전환 전의 {@code @RequestHeader("X-User-Public-Id")} 임시 처리를 대체한다(CLAUDE.md §9).
 *
 * <p>claim이 없거나 비어 있으면(=토큰 누락/IdP 설정 오류) resolver가 {@code AUTH4011}로 fail-fast 한다.
 *
 * <p>{@code @Parameter(hidden = true)}: 이 값은 JWT에서 주입되는 서버 내부 식별자이므로 SpringDoc이
 * API 입력 파라미터로 노출하지 않도록 숨긴다(메타 애너테이션이라 사용처 전부에 일괄 적용). 인증은
 * Swagger의 Bearer(Authorize) 토큰으로 처리한다.
 */
@Parameter(hidden = true)
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserPublicId {
}
