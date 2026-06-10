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
 * <p>{@code findAllByIsActiveTrueOrderByCountryAscNameAsc}가 비활성 은행을 제외하고
 * 국가코드 → 이름 ASC 순으로 반환하는지 확인한다.
 *
 * <p>GlobalBridge 계좌 추가는 KR 충전 계좌(한국 거주 외국인 근로자)와 해외(VN/PH/US) 수취 계좌를
 * 모두 등록할 수 있으므로 isDomestic 필터 없이 전체 활성 파트너 은행 16개를 반환한다.
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
    @DisplayName("findSupportedBanks: 활성 은행 전체를 country→name ASC 순, 비활성은 제외")
    void findSupportedBanks_전체활성_국가이름순() {
        persistBank("020", "우리은행", "KR", true, true);
        persistBank("004", "국민은행", "KR", true, true);
        persistBank("VCB", "Vietcombank", "VN", false, true);
        persistBank("BDO", "BDO Unibank", "PH", false, true);
        persistBank("999", "비활성은행", "KR", true, false);  // 비활성 — 제외
        em.flush();
        em.clear();

        List<Bank> result = repository.findAllByIsActiveTrueOrderByCountryAscNameAsc();

        // KR(국민은행, 우리은행) → PH(BDO) → VN(Vietcombank) — country ASC, name ASC
        assertThat(result).extracting(Bank::getName)
                .containsExactly("국민은행", "우리은행", "BDO Unibank", "Vietcombank");
        assertThat(result).extracting(Bank::getName)
                .doesNotContain("비활성은행");
    }

    @Test
    @DisplayName("findSupportedBanks: 조건을 만족하는 은행이 없으면 빈 리스트")
    void findSupportedBanks_없으면_빈리스트() {
        persistBank("999", "비활성은행", "KR", true, false);
        em.flush();
        em.clear();

        assertThat(repository.findAllByIsActiveTrueOrderByCountryAscNameAsc()).isEmpty();
    }

    private void persistBank(String code, String name, String country, boolean isDomestic, boolean isActive) {
        em.persist(Bank.builder()
                .code(code)
                .name(name)
                .country(country)
                .isDomestic(isDomestic)
                .isActive(isActive)
                .build());
    }
}
