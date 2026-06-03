package com.gb.wallet.domain.transaction.scheduled.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gb.wallet.global.common.enums.TransferFrequency;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NextRunDateCalculator} 단독 단위 테스트 — KST·월말 fallback·ISO 요일·"오늘 지났음" 정책.
 * {@code calculateFrom(frequency, day, baseDate)}를 사용해 시간을 고정.
 */
class NextRunDateCalculatorTest {

    private final NextRunDateCalculator calc = new NextRunDateCalculator();

    // ===== WEEKLY =====

    @Test
    @DisplayName("WEEKLY: 오늘=수(3), 목표=금(5) → 같은 주 금요일")
    void weekly_같은주_다음_요일() {
        // 2026-06-03은 수요일(3)
        LocalDate today = LocalDate.of(2026, 6, 3);
        LocalDate result = calc.calculateFrom(TransferFrequency.WEEKLY, 5, today);
        assertThat(result).isEqualTo(LocalDate.of(2026, 6, 5));  // 금요일
    }

    @Test
    @DisplayName("WEEKLY: 오늘=금(5), 목표=수(3) → 다음 주 수요일")
    void weekly_지난요일은_다음주() {
        LocalDate friday = LocalDate.of(2026, 6, 5);  // 금요일
        LocalDate result = calc.calculateFrom(TransferFrequency.WEEKLY, 3, friday);
        assertThat(result).isEqualTo(LocalDate.of(2026, 6, 10));  // 다음 주 수요일
    }

    @Test
    @DisplayName("WEEKLY: 오늘=목표 요일이면 다음 주 같은 요일 (오늘 이미 지났음 정책)")
    void weekly_오늘이_목표요일이면_다음주() {
        LocalDate monday = LocalDate.of(2026, 6, 1);  // 월요일(1)
        LocalDate result = calc.calculateFrom(TransferFrequency.WEEKLY, 1, monday);
        assertThat(result).isEqualTo(LocalDate.of(2026, 6, 8));  // 다음 주 월요일
    }

    // ===== MONTHLY =====

    @Test
    @DisplayName("MONTHLY: 오늘=6/10, 목표=25일 → 같은 달 6/25")
    void monthly_이번달_미도래() {
        LocalDate today = LocalDate.of(2026, 6, 10);
        LocalDate result = calc.calculateFrom(TransferFrequency.MONTHLY, 25, today);
        assertThat(result).isEqualTo(LocalDate.of(2026, 6, 25));
    }

    @Test
    @DisplayName("MONTHLY: 오늘=6/25 (target과 같음) → 다음 달 7/25")
    void monthly_오늘이_목표일이면_다음달() {
        LocalDate today = LocalDate.of(2026, 6, 25);
        LocalDate result = calc.calculateFrom(TransferFrequency.MONTHLY, 25, today);
        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 25));
    }

    @Test
    @DisplayName("MONTHLY: 오늘=6/26 (target 지남) → 다음 달 7/25")
    void monthly_이번달_target_지남() {
        LocalDate today = LocalDate.of(2026, 6, 26);
        LocalDate result = calc.calculateFrom(TransferFrequency.MONTHLY, 25, today);
        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 25));
    }

    @Test
    @DisplayName("MONTHLY: 오늘=4/1, 목표=31일 → 4월은 30일까지라 4/30으로 fallback")
    void monthly_월말_fallback_4월() {
        LocalDate today = LocalDate.of(2026, 4, 1);
        LocalDate result = calc.calculateFrom(TransferFrequency.MONTHLY, 31, today);
        assertThat(result).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("MONTHLY: 오늘=2/1, 목표=31일 → 2026년 2월은 28일까지라 2/28로 fallback")
    void monthly_월말_fallback_2월_비윤년() {
        LocalDate today = LocalDate.of(2026, 2, 1);
        LocalDate result = calc.calculateFrom(TransferFrequency.MONTHLY, 31, today);
        assertThat(result).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    @DisplayName("MONTHLY: 오늘=2/1, 목표=31일, 윤년(2028) → 2/29로 fallback")
    void monthly_월말_fallback_2월_윤년() {
        LocalDate today = LocalDate.of(2028, 2, 1);
        LocalDate result = calc.calculateFrom(TransferFrequency.MONTHLY, 31, today);
        assertThat(result).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    @DisplayName("MONTHLY: 오늘=1/31, 목표=31 (target과 같음) → 2월은 28일이라 다음달은 2/28")
    void monthly_오늘이_목표일이고_다음달은_월말_fallback() {
        LocalDate today = LocalDate.of(2026, 1, 31);
        LocalDate result = calc.calculateFrom(TransferFrequency.MONTHLY, 31, today);
        assertThat(result).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    // ===== 입력 검증 =====

    @Test
    @DisplayName("WEEKLY: scheduleDay 0 → IllegalArgumentException")
    void weekly_invalid_day_lower() {
        assertThatThrownBy(() ->
                calc.calculateFrom(TransferFrequency.WEEKLY, 0, LocalDate.of(2026, 6, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("WEEKLY: scheduleDay 8 → IllegalArgumentException")
    void weekly_invalid_day_upper() {
        assertThatThrownBy(() ->
                calc.calculateFrom(TransferFrequency.WEEKLY, 8, LocalDate.of(2026, 6, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MONTHLY: scheduleDay 32 → IllegalArgumentException")
    void monthly_invalid_day_upper() {
        assertThatThrownBy(() ->
                calc.calculateFrom(TransferFrequency.MONTHLY, 32, LocalDate.of(2026, 6, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
