package com.gb.member.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JwtUtil 검증 메서드 단위 테스트.
 *
 * <p>Spring 컨텍스트 없이 생성자에 직접 시크릿/만료시간을 주입해 빠르게 검증한다
 * ({@code @Value}는 컨테이너 주입용 메타데이터일 뿐, 직접 생성에는 영향 없음).
 *
 * <p>HS256 최소 키 길이(256bit = 32byte) 이상이어야 {@code Keys.hmacShaKeyFor}가 통과하므로
 * 테스트용 시크릿도 32바이트 이상으로 잡는다.
 */
class JwtUtilTest {

    private static final String SECRET =
            "test-secret-for-jwt-util-test-must-be-at-least-32-bytes-long-1234567890";
    private static final String OTHER_SECRET =
            "different-secret-for-forgery-test-also-at-least-32-bytes-long-0987654321";
    private static final long EXPIRATION_MS = 3_600_000L;

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, EXPIRATION_MS);

    @Test
    @DisplayName("정상 발급 토큰은 validateToken이 true를 반환한다")
    void validateToken_정상_토큰이면_true() {
        String token = jwtUtil.generateAccessToken(42L, "user@example.com");

        assertThat(jwtUtil.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("다른 시크릿으로 서명된 위조 토큰은 validateToken이 false를 반환한다")
    void validateToken_위조_서명이면_false() {
        String forged = buildToken(OTHER_SECRET, "42", "user@example.com",
                new Date(System.currentTimeMillis() + EXPIRATION_MS));

        assertThat(jwtUtil.validateToken(forged)).isFalse();
    }

    @Test
    @DisplayName("exp가 과거인 만료 토큰은 validateToken이 false를 반환한다")
    void validateToken_만료_토큰이면_false() {
        Date past = new Date(System.currentTimeMillis() - 10_000);
        String expired = buildToken(SECRET, "42", "user@example.com", past);

        assertThat(jwtUtil.validateToken(expired)).isFalse();
    }

    @Test
    @DisplayName("형식이 깨진 토큰은 validateToken이 false를 반환한다")
    void validateToken_형식깨진_토큰이면_false() {
        assertThat(jwtUtil.validateToken("not.a.jwt")).isFalse();
    }

    @Test
    @DisplayName("null/빈 문자열 토큰은 validateToken이 false를 반환한다")
    void validateToken_null_또는_빈문자열이면_false() {
        assertThat(jwtUtil.validateToken(null)).isFalse();
        assertThat(jwtUtil.validateToken("")).isFalse();
    }

    @Test
    @DisplayName("정상 토큰에서 getMemberIdFromToken은 발급 시 넣은 memberId를 그대로 반환한다")
    void getMemberIdFromToken_정상_토큰이면_원본_memberId_반환() {
        Long memberId = 42L;
        String token = jwtUtil.generateAccessToken(memberId, "user@example.com");

        assertThat(jwtUtil.getMemberIdFromToken(token)).isEqualTo(memberId);
    }

    @Test
    @DisplayName("위조 토큰에서 getMemberIdFromToken은 null을 반환한다")
    void getMemberIdFromToken_위조_토큰이면_null() {
        String forged = buildToken(OTHER_SECRET, "42", "user@example.com",
                new Date(System.currentTimeMillis() + EXPIRATION_MS));

        assertThat(jwtUtil.getMemberIdFromToken(forged)).isNull();
    }

    private static String buildToken(String secret, String subject, String email, Date expiration) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .claim("email", email)
                .issuedAt(new Date(System.currentTimeMillis() - 1_000))
                .expiration(expiration)
                .signWith(key)
                .compact();
    }
}
