package com.gb.wallet.domain.reward.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.wallet.domain.reward.dto.response.CouponListResponse;
import com.gb.wallet.domain.reward.dto.response.StampCardResponse;
import com.gb.wallet.domain.reward.entity.Coupon;
import com.gb.wallet.domain.reward.entity.CouponStatus;
import com.gb.wallet.domain.reward.entity.CouponType;
import com.gb.wallet.domain.reward.entity.RewardStamp;
import com.gb.wallet.domain.reward.repository.CouponRepository;
import com.gb.wallet.domain.reward.repository.RewardStampRepository;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.config.RewardProperties;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RewardServiceImpl} 단위 테스트 — 적립 멱등성, 목표 충족 시 쿠폰 발급, 조회 매핑을 검증한다.
 * DB·스프링 컨텍스트 없이 Mockito로 조합/분기만 빠르게 확인한다(CLAUDE.md §10).
 */
@ExtendWith(MockitoExtension.class)
class RewardServiceImplTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String TX_1 = "tx-aaaa-0001";
    private static final int TARGET = 5;
    private static final int VALID_DAYS = 90;

    @Mock private RewardStampRepository stampRepository;
    @Mock private CouponRepository couponRepository;

    private RewardServiceImpl rewardService;

    @BeforeEach
    void setUp() {
        // RewardProperties는 record라 mock 대신 실제 값(목표 5, 만료 90일)을 주입한다.
        rewardService = new RewardServiceImpl(stampRepository, couponRepository,
                new RewardProperties(TARGET, VALID_DAYS));
    }

    @Test
    @DisplayName("첫 적립 — 스탬프 1건 저장, 목표 미달이라 쿠폰 미발급")
    void accrueStamp_firstStamp_savesStampNoCoupon() {
        given(stampRepository.existsBySourceTransactionPublicId(TX_1)).willReturn(false);
        given(stampRepository.countByUserPublicId(USER)).willReturn(1L);

        rewardService.accrueStamp(USER, TX_1, TransactionType.REMITTANCE);

        verify(stampRepository).save(any(RewardStamp.class));
        verify(couponRepository, never()).save(any(Coupon.class));
    }

    @Test
    @DisplayName("멱등 — 같은 거래로 이미 적립됐으면 아무것도 하지 않는다")
    void accrueStamp_duplicateTransaction_skips() {
        given(stampRepository.existsBySourceTransactionPublicId(TX_1)).willReturn(true);

        rewardService.accrueStamp(USER, TX_1, TransactionType.REMITTANCE);

        verify(stampRepository, never()).save(any(RewardStamp.class));
        verifyNoInteractions(couponRepository);
    }

    @Test
    @DisplayName("앱 내 송금(INTERNAL_TRANSFER)은 수수료가 없어 적립/쿠폰 대상이 아니다 — 아무것도 하지 않는다")
    void accrueStamp_internalTransfer_doesNotAccrue() {
        rewardService.accrueStamp(USER, TX_1, TransactionType.INTERNAL_TRANSFER);

        verifyNoInteractions(stampRepository, couponRepository);
    }

    @Test
    @DisplayName("5번째 적립 — 카드 완성으로 cycle 1 쿠폰 발급(TRANSFER_FEE_FREE / ISSUED)")
    void accrueStamp_fifthStamp_issuesCouponCycle1() {
        given(stampRepository.existsBySourceTransactionPublicId(TX_1)).willReturn(false);
        given(stampRepository.countByUserPublicId(USER)).willReturn(5L);
        given(couponRepository.existsByUserPublicIdAndCycleNo(USER, 1)).willReturn(false);

        rewardService.accrueStamp(USER, TX_1, TransactionType.REMITTANCE);

        ArgumentCaptor<Coupon> captor = ArgumentCaptor.forClass(Coupon.class);
        verify(couponRepository).save(captor.capture());
        Coupon issued = captor.getValue();
        assertThat(issued.getCycleNo()).isEqualTo(1);
        assertThat(issued.getType()).isEqualTo(CouponType.TRANSFER_FEE_FREE);
        assertThat(issued.getStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(issued.getUserPublicId()).isEqualTo(USER);
        assertThat(issued.getPublicId()).isNotBlank();
        assertThat(issued.getExpiresAt()).isAfter(issued.getIssuedAt());
    }

    @Test
    @DisplayName("6번째 적립 — cycle 1 쿠폰이 이미 있으면 재발급하지 않는다")
    void accrueStamp_sixthStamp_doesNotReissue() {
        given(stampRepository.existsBySourceTransactionPublicId(TX_1)).willReturn(false);
        given(stampRepository.countByUserPublicId(USER)).willReturn(6L);
        given(couponRepository.existsByUserPublicIdAndCycleNo(USER, 1)).willReturn(true);

        rewardService.accrueStamp(USER, TX_1, TransactionType.REMITTANCE);

        verify(couponRepository, never()).save(any(Coupon.class));
    }

    @Test
    @DisplayName("10번째 적립 — 두 번째 카드 완성으로 cycle 2 쿠폰 발급")
    void accrueStamp_tenthStamp_issuesCouponCycle2() {
        given(stampRepository.existsBySourceTransactionPublicId(TX_1)).willReturn(false);
        given(stampRepository.countByUserPublicId(USER)).willReturn(10L);
        given(couponRepository.existsByUserPublicIdAndCycleNo(USER, 2)).willReturn(false);

        rewardService.accrueStamp(USER, TX_1, TransactionType.REMITTANCE);

        ArgumentCaptor<Coupon> captor = ArgumentCaptor.forClass(Coupon.class);
        verify(couponRepository).save(captor.capture());
        assertThat(captor.getValue().getCycleNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("스탬프 카드 조회 — current=누적%목표, target, 누적, 사용가능 쿠폰 수")
    void getStampCard_computesProgress() {
        given(stampRepository.countByUserPublicId(USER)).willReturn(8L);
        given(couponRepository.countByUserPublicIdAndStatus(USER, CouponStatus.ISSUED)).willReturn(1L);

        StampCardResponse res = rewardService.getStampCard(USER);

        assertThat(res.getCurrentCount()).isEqualTo(3); // 8 % 5
        assertThat(res.getTarget()).isEqualTo(5);
        assertThat(res.getTotalStamps()).isEqualTo(8);
        assertThat(res.getAvailableCouponCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("보유 쿠폰 조회 — 엔티티를 응답 항목으로 매핑")
    void getCoupons_mapsEntities() {
        LocalDateTime issuedAt = LocalDateTime.of(2026, 6, 11, 4, 15, 30);
        Coupon coupon = Coupon.builder()
                .publicId("coupon-public-1")
                .userPublicId(USER)
                .type(CouponType.TRANSFER_FEE_FREE)
                .status(CouponStatus.ISSUED)
                .cycleNo(1)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusDays(90))
                .build();
        given(couponRepository.findByUserPublicIdOrderByIssuedAtDesc(USER)).willReturn(List.of(coupon));

        CouponListResponse res = rewardService.getCoupons(USER);

        assertThat(res.getCoupons()).hasSize(1);
        CouponListResponse.CouponItem item = res.getCoupons().get(0);
        assertThat(item.getPublicId()).isEqualTo("coupon-public-1");
        assertThat(item.getType()).isEqualTo("TRANSFER_FEE_FREE");
        assertThat(item.getStatus()).isEqualTo("ISSUED");
        assertThat(item.getIssuedAt()).isEqualTo("2026-06-11T04:15:30Z");
    }
}
