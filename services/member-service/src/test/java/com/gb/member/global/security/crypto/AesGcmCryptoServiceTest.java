package com.gb.member.global.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AES-256-GCM 암복호 유틸 단위 테스트.
 *
 * <p>핵심 보장:
 * <ol>
 *   <li>round-trip: encrypt → decrypt = 원본</li>
 *   <li>비결정성: 같은 평문도 IV가 매번 랜덤이라 ciphertext가 매번 다르다</li>
 *   <li>변조 탐지: 1바이트만 바꿔도 GCM 태그 검증이 실패해 복호화가 실패한다</li>
 *   <li>키 검증: 누락/형식오류/길이오류 키는 빈 생성 시점에 fail-fast</li>
 *   <li>null 통과: 엔티티 nullable 컬럼 호환 위해 null → null</li>
 * </ol>
 */
class AesGcmCryptoServiceTest {

    /** Base64(32B all-zero). 테스트 전용 비밀 아님. */
    private static final String TEST_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    private final AesGcmCryptoService service = new AesGcmCryptoService(new CryptoProperties(TEST_KEY_B64));

    @Test
    @DisplayName("암호화 후 복호화하면 원본 평문이 그대로 복원된다")
    void roundTrip_원본복원() {
        String plaintext = "990101-5678901";

        String encrypted = service.encrypt(plaintext);
        String decrypted = service.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("같은 평문도 IV가 매번 랜덤이라 ciphertext가 매번 달라진다 (결정성 없음)")
    void encrypt_같은평문_다른ciphertext() {
        String plaintext = "990101-5678901";

        String first = service.encrypt(plaintext);
        String second = service.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        // 그러나 복호화 결과는 동일
        assertThat(service.decrypt(first)).isEqualTo(plaintext);
        assertThat(service.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("ciphertext가 1바이트라도 변조되면 GCM 태그 검증 실패로 복호화가 거절된다")
    void decrypt_변조_실패() {
        String encrypted = service.encrypt("990101-5678901");
        byte[] payload = Base64.getDecoder().decode(encrypted);
        // 마지막 바이트(태그 일부)를 한 비트 뒤집어 변조 시뮬레이션
        payload[payload.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(payload);

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("복호화 실패");
    }

    @Test
    @DisplayName("다른 키로 만든 ciphertext는 복호화되지 않는다")
    void decrypt_다른키_실패() {
        AesGcmCryptoService other = new AesGcmCryptoService(
                new CryptoProperties(Base64.getEncoder().encodeToString(filled((byte) 0xFF))));
        String encryptedByOther = other.encrypt("990101-5678901");

        assertThatThrownBy(() -> service.decrypt(encryptedByOther))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("null은 암호화/복호화 모두 null 반환 (nullable 컬럼 호환)")
    void null_통과() {
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("빈 문자열도 round-trip 가능하다")
    void roundTrip_빈문자열() {
        String encrypted = service.encrypt("");
        assertThat(encrypted).isNotNull();
        assertThat(service.decrypt(encrypted)).isEmpty();
    }

    @Test
    @DisplayName("유니코드 문자열(여권번호·한글 등)도 round-trip 가능하다")
    void roundTrip_유니코드() {
        String plaintext = "여권번호-M12345678-한글혼합";

        String encrypted = service.encrypt(plaintext);

        assertThat(service.decrypt(encrypted)).isEqualTo(plaintext);
    }

    // ───────────────────────── 키 검증 ─────────────────────────

    @Test
    @DisplayName("키가 null이면 빈 생성 시점에 IllegalStateException으로 fail-fast")
    void 키_null_실패() {
        assertThatThrownBy(() -> new AesGcmCryptoService(new CryptoProperties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GB_CRYPTO_KEY");
    }

    @Test
    @DisplayName("키가 빈 문자열이면 빈 생성 시점에 fail-fast")
    void 키_빈문자열_실패() {
        assertThatThrownBy(() -> new AesGcmCryptoService(new CryptoProperties("   ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GB_CRYPTO_KEY");
    }

    @Test
    @DisplayName("키가 Base64 형식이 아니면 빈 생성 시점에 fail-fast")
    void 키_잘못된Base64_실패() {
        assertThatThrownBy(() -> new AesGcmCryptoService(new CryptoProperties("!!!not-base64!!!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    @DisplayName("키 길이가 32바이트가 아니면(예: 16B AES-128) 빈 생성 시점에 fail-fast")
    void 키_길이오류_실패() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);   // AES-128 키

        assertThatThrownBy(() -> new AesGcmCryptoService(new CryptoProperties(shortKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }

    private static byte[] filled(byte value) {
        byte[] arr = new byte[32];
        java.util.Arrays.fill(arr, value);
        return arr;
    }
}
