package com.gb.member.domain.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.member.domain.member.entity.Member;
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
 * {@link MemberRepository}의 탈퇴자 제외 조회 검증.
 *
 * <p>{@code findByPublicIdAndDeletedAtIsNull}가 활성 회원만 반환하고, soft delete(deleted_at 세팅)된
 * 회원은 결과에서 제외하는지(필터링) 확인한다(CLAUDE §10 — 제외돼야 할 데이터가 안 나오는지 검증).
 * H2(MySQL 호환 모드) — application-test.yml이 datasource를 제공하므로
 * {@code @AutoConfigureTestDatabase(replace = NONE)}로 자동 교체를 막는다.
 *
 * <p><b>crypto 빈 import 사유:</b> 같은 EntityManagerFactory에 로드되는 {@link
 * com.gb.member.domain.verification.entity.UserVerification}이 {@code @Convert(EncryptedStringConverter.class)}
 * 를 선언하고 있어, Hibernate가 엔티티 메타데이터를 스캔할 때 컨버터 빈을 요구한다. {@code @DataJpaTest}는
 * 일반 {@code @Component}를 스캔하지 않으므로 명시적으로 가져와야 한다(conventions §15-6).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({CryptoConfig.class, AesGcmCryptoService.class, EncryptedStringConverter.class})
class MemberRepositoryTest {

    @Autowired private TestEntityManager em;
    @Autowired private MemberRepository repository;

    @Test
    @DisplayName("findByPublicIdAndDeletedAtIsNull: 활성 회원은 조회되고 탈퇴(soft delete) 회원은 제외된다")
    void findActiveByPublicId_탈퇴자_제외() {
        Member active = persistMember("active-public-id", "active@example.com", "activeNick");
        Member withdrawn = persistMember("withdrawn-public-id", "withdrawn@example.com", "withdrawnNick");
        // 탈퇴 처리: deleted_at 세팅. softDelete는 일반 필드 쓰기라 native UPDATE가 필요 없다
        // (Auditing/@PrePersist가 deleted_at을 건드리지 않음 — created_at과 달리 덮어쓰기 위험 없음).
        // flush로 dirty checking 결과를 DB에 반영한 뒤 clear로 1차 캐시를 비워 실제 쿼리를 강제한다.
        withdrawn.softDelete();
        em.flush();
        em.clear();

        assertThat(repository.findByPublicIdAndDeletedAtIsNull("active-public-id"))
                .as("활성 회원은 조회됨").isPresent();
        assertThat(repository.findByPublicIdAndDeletedAtIsNull("withdrawn-public-id"))
                .as("탈퇴(soft delete)된 회원은 제외").isEmpty();
        assertThat(repository.findByPublicIdAndDeletedAtIsNull("no-such-public-id"))
                .as("없는 publicId").isEmpty();
    }

    private Member persistMember(String publicId, String email, String nickname) {
        Member member = Member.builder()
                .publicId(publicId)
                .email(email)
                .name("홍길동")
                .nickname(nickname)
                .nationality("VN")
                .language("vi")
                .authProviderId("idp-" + publicId)
                .build();
        em.persist(member);
        return member;
    }
}
