package com.gb.member.global.security.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM 기반 문자열 암복호 유틸.
 *
 * <p><b>왜 GCM인가</b> — 인증암호화(AEAD). CBC는 변조 탐지 불가라 PII 저장에 부적합. GCM은 ciphertext에
 * 무결성 태그(128bit)가 붙어 변조 시 {@link javax.crypto.AEADBadTagException}으로 즉시 실패한다.
 *
 * <p><b>출력 포맷</b> — {@code Base64( IV(12B) || ciphertext || tag(16B) )} 단일 문자열.
 * 같은 평문도 IV가 매번 랜덤이라 ciphertext가 매번 다르다(= 결정성 없음 → 컬럼 equals 검색 불가).
 * 검색/중복확인이 필요해지면 별도 HMAC hash 컬럼을 추가하는 방식으로 풀어야 한다.
 *
 * <p><b>키 검증</b> — 생성 시점에 {@link CryptoProperties#key()}를 Base64 디코딩해 정확히 32바이트(256bit)인지
 * 확인하고, 아니면 즉시 빈 생성을 실패시켜 운영 환경의 키 누락/오타를 부팅 시 잡는다.
 *
 * <p>스레드 안전성: {@link SecureRandom}과 {@link SecretKeySpec}은 불변/스레드 안전. {@link Cipher}는
 * 비스레드 안전이라 호출마다 새로 생성한다(JCE 표준 사용 패턴).
 */
@Component
public class AesGcmCryptoService {

    /** AES-GCM 권장 IV 길이(12B = 96bit). 더 큰 IV는 추가 변환 비용만 들고 이득 없음. */
    private static final int IV_LENGTH_BYTES = 12;
    /** GCM 인증 태그 길이(128bit = 16B). NIST SP 800-38D 최댓값. */
    private static final int TAG_LENGTH_BITS = 128;
    /** AES-256 키 길이(32B). */
    private static final int KEY_LENGTH_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmCryptoService(CryptoProperties properties) {
        if (properties.key() == null || properties.key().isBlank()) {
            throw new IllegalStateException(
                    "gb.crypto.key가 비어 있다. 환경변수 GB_CRYPTO_KEY로 Base64(32B AES-256 키)를 주입해야 한다.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(properties.key());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "gb.crypto.key가 Base64 형식이 아니다. Base64(32B AES-256 키)로 주입해야 한다.", e);
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "gb.crypto.key는 정확히 32바이트(AES-256)여야 한다. 현재: " + keyBytes.length + "B");
        }
        this.secretKey = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }

    /**
     * 평문을 AES-256-GCM으로 암호화한 뒤 Base64({@code IV || ciphertext || tag})로 직렬화한다.
     * null은 null 반환(엔티티 nullable 컬럼 호환).
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] payload = ByteBuffer.allocate(iv.length + ciphertextWithTag.length)
                    .put(iv)
                    .put(ciphertextWithTag)
                    .array();
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            // JCE 알고리즘 미존재/키 오류 등 — 운영에 노출되면 안 되는 시스템 오류
            throw new IllegalStateException("AES-GCM 암호화 실패", e);
        }
    }

    /**
     * {@link #encrypt(String)} 결과를 복호화해 평문을 돌려준다. 변조되었거나 다른 키로 만든 값이면
     * {@link javax.crypto.AEADBadTagException}이 발생해 {@link IllegalStateException}으로 감싸 던진다.
     * null은 null 반환.
     */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length < IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
                throw new IllegalStateException("AES-GCM 페이로드 길이가 부족하다(손상 가능).");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertextWithTag = new byte[payload.length - IV_LENGTH_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(payload, IV_LENGTH_BYTES, ciphertextWithTag, 0, ciphertextWithTag.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertextWithTag);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM 복호화 실패(변조/잘못된 키)", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AES-GCM 페이로드 Base64 디코딩 실패", e);
        }
    }
}
