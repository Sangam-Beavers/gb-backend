package com.gb.appadmin.global.security;

import io.swagger.v3.oas.annotations.Parameter;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 인증된 관리자의 {@code public_id}(UUID)를 주입한다.
 * JWT custom claim {@code public_id}에서 추출(CLAUDE.md §9).
 */
@Parameter(hidden = true)
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentAdminPublicId {
}
