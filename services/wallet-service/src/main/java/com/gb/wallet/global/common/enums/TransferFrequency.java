package com.gb.wallet.global.common.enums;

import java.util.Optional;

/**
 * 정기 송금 반복 주기. {@code WEEKLY}는 ISO 8601 요일(1=월요일, 7=일요일), {@code MONTHLY}는 매월 1~31일.
 *
 * <p>{@code schedule_day}의 유효 범위는 frequency별로 다르다:
 * <ul>
 *   <li>WEEKLY → 1~7</li>
 *   <li>MONTHLY → 1~31 (실제 달의 일수보다 큰 값은 그 달 마지막 날로 fallback)</li>
 * </ul>
 *
 * <p>범위를 벗어난 {@code schedule_day}는 Service에서 {@code COMMON4221}(422)로 차단한다.
 */
public enum TransferFrequency {
    WEEKLY(1, 7),
    MONTHLY(1, 31);

    private final int minDay;
    private final int maxDay;

    TransferFrequency(int minDay, int maxDay) {
        this.minDay = minDay;
        this.maxDay = maxDay;
    }

    public boolean isValidDay(int day) {
        return day >= minDay && day <= maxDay;
    }

    public int minDay() { return minDay; }
    public int maxDay() { return maxDay; }

    /** 코드(이름) 문자열로 enum 매칭. 미지원 값이면 {@link Optional#empty()}. */
    public static Optional<TransferFrequency> fromCode(String code) {
        if (code == null) return Optional.empty();
        try {
            return Optional.of(TransferFrequency.valueOf(code));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
