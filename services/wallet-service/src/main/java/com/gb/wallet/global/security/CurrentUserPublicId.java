package com.gb.wallet.global.security;

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
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserPublicId {
}
