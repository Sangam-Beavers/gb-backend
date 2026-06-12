package com.gb.wallet.domain.reward.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gb.wallet.domain.reward.entity.Coupon;
import com.gb.wallet.domain.reward.entity.CouponStatus;
import com.gb.wallet.domain.reward.entity.CouponType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link CouponRepository} 통합 검증(H2 MySQL 모드). 보유 목록 정렬·상태별 카운트·발급 멱등 체크와
 * (user_public_id, cycle_no) UNIQUE(같은 사이클 중복 발급 차단)를 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CouponRepositoryTest {

    private static final String USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Autowired private CouponRepository repository;

    private static Coupon coupon(String user, int cycleNo, CouponStatus status, LocalDateTime issuedAt) {
        return Coupon.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(user)
                .type(CouponType.TRANSFER_FEE_FREE)
                .status(status)
                .cycleNo(cycleNo)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusDays(90))
                .build();
    }

    @Test
    @DisplayName("findByUserPublicIdOrderByIssuedAtDesc — 발급 최신순, 본인 것만")
    void findOrderedByIssuedDesc() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 0, 0);
        repository.save(coupon(USER_A, 1, CouponStatus.ISSUED, base));
        repository.save(coupon(USER_A, 2, CouponStatus.ISSUED, base.plusDays(10)));
        repository.save(coupon(USER_B, 1, CouponStatus.ISSUED, base.plusDays(5)));

        List<Coupon> result = repository.findByUserPublicIdOrderByIssuedAtDesc(USER_A);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getCycleNo()).isEqualTo(2); // 최신 먼저
        assertThat(result.get(1).getCycleNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("countByUserPublicIdAndStatus — 상태별 보유 수")
    void countByStatus() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 0, 0);
        repository.save(coupon(USER_A, 1, CouponStatus.ISSUED, base));
        repository.save(coupon(USER_A, 2, CouponStatus.USED, base.plusDays(1)));

        assertThat(repository.countByUserPublicIdAndStatus(USER_A, CouponStatus.ISSUED)).isEqualTo(1);
        assertThat(repository.countByUserPublicIdAndStatus(USER_A, CouponStatus.USED)).isEqualTo(1);
    }

    @Test
    @DisplayName("existsByUserPublicIdAndCycleNo — 사이클 발급 멱등 체크")
    void existsByUserAndCycle() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 0, 0);
        repository.save(coupon(USER_A, 1, CouponStatus.ISSUED, base));

        assertThat(repository.existsByUserPublicIdAndCycleNo(USER_A, 1)).isTrue();
        assertThat(repository.existsByUserPublicIdAndCycleNo(USER_A, 2)).isFalse();
        assertThat(repository.existsByUserPublicIdAndCycleNo(USER_B, 1)).isFalse();
    }

    @Test
    @DisplayName("(user, cycle_no) UNIQUE — 같은 사이클로 두 번 발급 불가")
    void userCycleUnique() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 1, 0, 0);
        repository.saveAndFlush(coupon(USER_A, 1, CouponStatus.ISSUED, base));

        assertThatThrownBy(() -> repository.saveAndFlush(coupon(USER_A, 1, CouponStatus.ISSUED, base)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
