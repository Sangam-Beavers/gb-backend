package com.gb.member.domain.verification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.verification.entity.IdentityDocumentType;
import com.gb.member.domain.verification.entity.UserVerification;
import com.gb.member.global.config.CryptoConfig;
import com.gb.member.global.security.crypto.AesGcmCryptoService;
import com.gb.member.global.security.crypto.EncryptedStringConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link EncryptedStringConverter}가 실제 JPA 영속/조회 사이클에서 작동하는지 검증한다.
 *
 * <p>두 가지를 동시에 확인한다:
 * <ol>
 *   <li><b>DB에는 ciphertext만</b> — native SQL로 직접 컬럼을 읽었을 때 평문 값이 보이면 안 된다.</li>
 *   <li><b>엔티티에는 평문</b> — 같은 트랜잭션에서 JPA로 다시 로드하면 평문이 자동 복호화돼 보인다.</li>
 * </ol>
 *
 * <p>{@link AesGcmCryptoService}/{@link EncryptedStringConverter}/{@link CryptoConfig}는 일반
 * {@code @Component}/{@code @Configuration}이라 {@code @DataJpaTest} 슬라이스에 자동 포함되지 않으므로
 * {@code @Import}로 명시한다. 키는 {@code application-test.yml}의 {@code gb.crypto.key}가 주입된다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({CryptoConfig.class, AesGcmCryptoService.class, EncryptedStringConverter.class})
class UserVerificationEncryptionIntegrationTest {

    private static final String PLAINTEXT_ARC = "990101-5678901";

    @Autowired private TestEntityManager em;

    @Test
    @DisplayName("document_number는 DB 컬럼에는 ciphertext로 저장되고, 엔티티 조회 시 평문으로 복원된다")
    void documentNumber_DB는ciphertext_엔티티는평문() {
        Member member = persistMember();

        UserVerification verification = UserVerification.approved(
                member, IdentityDocumentType.ALIEN_REGISTRATION, PLAINTEXT_ARC, "verifications/x/front.jpg");
        em.persist(verification);
        em.flush();
        em.clear();   // 1차 캐시 비우기 — 진짜 DB에서 다시 읽도록 강제

        // 1) native query로 컬럼 raw 값 확인 → 평문이 들어 있으면 안 됨
        Object rawColumn = em.getEntityManager()
                .createNativeQuery("SELECT document_number FROM user_verifications WHERE id = :id")
                .setParameter("id", verification.getId())
                .getSingleResult();
        assertThat(rawColumn).isInstanceOf(String.class);
        String ciphertext = (String) rawColumn;
        assertThat(ciphertext)
                .as("DB에는 평문이 남지 않아야 한다")
                .isNotEqualTo(PLAINTEXT_ARC)
                .doesNotContain(PLAINTEXT_ARC);
        assertThat(ciphertext.length())
                .as("AES-GCM(IV+ct+tag)을 Base64 인코딩한 결과는 평문보다 충분히 길다")
                .isGreaterThan(PLAINTEXT_ARC.length());

        // 2) JPA로 다시 로드 → 컨버터가 자동 복호화해서 평문 반환
        UserVerification loaded = em.find(UserVerification.class, verification.getId());
        assertThat(loaded.getDocumentNumber()).isEqualTo(PLAINTEXT_ARC);
    }

    @Test
    @DisplayName("같은 평문을 두 번 저장해도 IV 랜덤이라 DB ciphertext는 매번 다르다")
    void documentNumber_매번_다른_ciphertext() {
        Member member1 = persistMember("public-id-1", "a@example.com", "닉네임A", "idp-sub-1");
        Member member2 = persistMember("public-id-2", "b@example.com", "닉네임B", "idp-sub-2");

        UserVerification v1 = UserVerification.approved(
                member1, IdentityDocumentType.ALIEN_REGISTRATION, PLAINTEXT_ARC, "verifications/a/front.jpg");
        UserVerification v2 = UserVerification.approved(
                member2, IdentityDocumentType.ALIEN_REGISTRATION, PLAINTEXT_ARC, "verifications/b/front.jpg");
        em.persist(v1);
        em.persist(v2);
        em.flush();
        em.clear();

        String c1 = (String) em.getEntityManager()
                .createNativeQuery("SELECT document_number FROM user_verifications WHERE id = :id")
                .setParameter("id", v1.getId())
                .getSingleResult();
        String c2 = (String) em.getEntityManager()
                .createNativeQuery("SELECT document_number FROM user_verifications WHERE id = :id")
                .setParameter("id", v2.getId())
                .getSingleResult();

        assertThat(c1).isNotEqualTo(c2);
        // 그러나 평문 복원은 동일
        assertThat(em.find(UserVerification.class, v1.getId()).getDocumentNumber()).isEqualTo(PLAINTEXT_ARC);
        assertThat(em.find(UserVerification.class, v2.getId()).getDocumentNumber()).isEqualTo(PLAINTEXT_ARC);
    }

    // ───────────────────────── 헬퍼 ─────────────────────────

    private Member persistMember() {
        return persistMember("11111111-1111-1111-1111-111111111111",
                "nguyen@example.com", "하노이댁", "idp-sub-1");
    }

    private Member persistMember(String publicId, String email, String nickname, String authProviderId) {
        Member member = Member.builder()
                .publicId(publicId)
                .email(email)
                .name("Nguyen")
                .nickname(nickname)
                .nationality("VN")
                .language("vi")
                .authProviderId(authProviderId)
                .build();
        em.persist(member);
        return member;
    }
}
