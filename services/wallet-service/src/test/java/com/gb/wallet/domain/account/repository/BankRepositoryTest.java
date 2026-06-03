package com.gb.wallet.domain.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.wallet.domain.account.entity.Bank;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link BankRepository}의 지원 은행 목록 쿼리 검증.
 *
 * <p>{@code findAllByIsDomesticTrueAndIsActiveTrueOrderByNameAsc}가 비활성·해외 은행을 제외하고
 * 이름 가나다(ASC)순으로 반환하는지 확인한다. 운영 시드(data-dev.sql)는 모든 은행이
 * {@code is_domestic=true·is_active=true}라 음성(제외) 데이터가 없어 필터 동작을 어떤 방식으로도 확인할 수
 * 없으므로, 이 테스트가 유일한 방어선이다(CLAUDE.md §10 제외 검증).
 *
 * <p>H2(MySQL 호환 모드) — application-test.yml이 datasource를 제공하므로
 * {@code @AutoConfigureTestDatabase(replace = NONE)}로 자동 교체를 막는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class BankRepositoryTest {

    @Autowired private TestEntityManager em;
    @Autowired private BankRepository repository;

    @Test
    @DisplayName("findSupportedBanks: 국내+활성만 가나다(ASC)순, 비활성·해외는 제외")
    void findSupportedBanks_국내활성만_가나다순() {
        // 일부러 가나다 역순으로 persist해 ORDER BY name ASC가 실제로 정렬함을 실증한다(국민 < 신한 < 우리).
        persistBank("020", "우리은행", true, true);
        persistBank("004", "국민은행", true, true);
        persistBank("088", "신한은행", true, true);
        persistBank("999", "비활성은행", true, false);   // 비활성 — 제외
        persistBank("VCB", "베트남은행", false, true);    // 해외(is_domestic=false) — 제외
        em.flush();
        em.clear();

        List<Bank> result = repository.findAllByIsDomesticTrueAndIsActiveTrueOrderByNameAsc();

        assertThat(result).extracting(Bank::getName)
                .containsExactly("국민은행", "신한은행", "우리은행");
        // 제외 검증: 비활성·해외 은행은 결과에 없다.
        assertThat(result).extracting(Bank::getName)
                .doesNotContain("비활성은행", "베트남은행");
    }

    @Test
    @DisplayName("findSupportedBanks: 조건을 만족하는 은행이 없으면 빈 리스트")
    void findSupportedBanks_없으면_빈리스트() {
        persistBank("999", "비활성은행", true, false);
        persistBank("VCB", "베트남은행", false, true);
        em.flush();
        em.clear();

        assertThat(repository.findAllByIsDomesticTrueAndIsActiveTrueOrderByNameAsc()).isEmpty();
    }

    private void persistBank(String code, String name, boolean isDomestic, boolean isActive) {
        em.persist(Bank.builder()
                .code(code)
                .name(name)
                .country(isDomestic ? "KR" : "VN")
                .isDomestic(isDomestic)
                .isActive(isActive)
                .build());
    }
}
