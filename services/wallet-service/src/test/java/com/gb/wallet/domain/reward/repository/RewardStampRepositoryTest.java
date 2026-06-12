package com.gb.wallet.domain.reward.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gb.wallet.domain.reward.entity.RewardStamp;
import com.gb.wallet.global.common.enums.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link RewardStampRepository} 통합 검증(H2 MySQL 모드). 누적 카운트와 source_transaction_public_id
 * UNIQUE(멱등 키)가 의도대로 동작하는지 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class RewardStampRepositoryTest {

    private static final String USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Autowired private RewardStampRepository repository;

    private static RewardStamp stamp(String user, String sourceTx) {
        return RewardStamp.builder()
                .userPublicId(user)
                .sourceTransactionPublicId(sourceTx)
                .transferType(TransactionType.INTERNAL_TRANSFER)
                .build();
    }

    @Test
    @DisplayName("countByUserPublicId — 사용자별 누적 스탬프만 센다")
    void countByUser() {
        repository.save(stamp(USER_A, "tx-a-1"));
        repository.save(stamp(USER_A, "tx-a-2"));
        repository.save(stamp(USER_B, "tx-b-1"));

        assertThat(repository.countByUserPublicId(USER_A)).isEqualTo(2);
        assertThat(repository.countByUserPublicId(USER_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("existsBySourceTransactionPublicId — 적립 여부 멱등 체크")
    void existsBySourceTx() {
        repository.save(stamp(USER_A, "tx-a-1"));

        assertThat(repository.existsBySourceTransactionPublicId("tx-a-1")).isTrue();
        assertThat(repository.existsBySourceTransactionPublicId("tx-a-x")).isFalse();
    }

    @Test
    @DisplayName("source_transaction_public_id UNIQUE — 같은 거래로 두 번 적립 불가")
    void sourceTxUnique() {
        repository.saveAndFlush(stamp(USER_A, "tx-dup"));

        assertThatThrownBy(() -> repository.saveAndFlush(stamp(USER_B, "tx-dup")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
