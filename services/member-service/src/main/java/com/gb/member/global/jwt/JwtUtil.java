package com.gb.member.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 액세스 토큰 발급/검증 유틸. HS256 대칭키 서명.
 *
 * <p>시크릿/만료시간은 application 설정({@code jwt.secret}, {@code jwt.access-token-expiration-ms})에서 주입받고,
 * 운영/개발기에서는 환경변수로 외부에서 공급한다(application-dev.yml 참고).
 *
 * <p>리프레시 토큰은 본 범위 밖. 액세스 토큰 발급(generateAccessToken)만 제공한다.
 *
 * <p>검증 메서드({@link #validateToken}, {@link #getMemberIdFromToken}, {@link #getEmailFromToken})는
 * 후속 이슈의 JwtAuthFilter에서 사용 예정이며, 본 이슈에서는 호출되지 않는다.
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long accessTokenExpirationMs;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs) {
        // HS256 최소 키 길이(256bit) 보장: 환경변수에 충분히 긴 시크릿을 넣어야 한다.
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    /** 로그인 성공 시 발급되는 액세스 토큰. sub=memberId, claim email을 담는다. */
    public String generateAccessToken(Long memberId, String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim("email", email)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /** 응답 expires_in(초) 계산용. 설정값을 초 단위로 노출한다. */
    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMs / 1000;
    }

    /**
     * 서명·exp 만료를 함께 검증한다. {@code parseSignedClaims}가 두 검증을 동시에 수행한다.
     * 어떤 실패든 false로 변환하며, 운영 로그를 시끄럽게 하지 않기 위해 케이스별로 {@code log.debug}로만 남긴다.
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (SignatureException e) {
            log.debug("JWT signature invalid: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.debug("JWT malformed: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.debug("JWT unsupported: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.debug("JWT empty or null: {}", e.getMessage());
        }
        return false;
    }

    /** sub claim을 Long으로 파싱해 반환. 검증 실패·파싱 실패 시 예외 없이 null. */
    public Long getMemberIdFromToken(String token) {
        Claims claims = tryParseClaims(token);
        if (claims == null) {
            return null;
        }
        String subject = claims.getSubject();
        if (subject == null) {
            return null;
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** email claim을 String으로 추출. 검증 실패·타입 불일치·부재 시 null. */
    public String getEmailFromToken(String token) {
        Claims claims = tryParseClaims(token);
        if (claims == null) {
            return null;
        }
        Object email = claims.get("email");
        return email instanceof String s ? s : null;
    }

    private Claims tryParseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
