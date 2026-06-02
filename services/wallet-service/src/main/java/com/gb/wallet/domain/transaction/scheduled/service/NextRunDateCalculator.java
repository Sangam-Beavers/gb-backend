package com.gb.wallet.domain.transaction.scheduled.service;

import com.gb.wallet.global.common.enums.TransferFrequency;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import org.springframework.stereotype.Component;

/**
 * 정기 송금 {@code next_run_date} 계산기. 모든 날짜는 <b>KST({@code Asia/Seoul})</b> 기준.
 *
 * <p>정책:
 * <ul>
 *   <li><b>WEEKLY</b> — {@code scheduleDay}는 ISO 8601 요일(1=월요일, 7=일요일). 기준일(보통 오늘) 이후 가장
 *       가까운 그 요일을 다음 실행일로 한다. 기준일이 그 요일이면 7일 뒤로(=다음 주). "오늘 이미 지났음" 정책.</li>
 *   <li><b>MONTHLY</b> — {@code scheduleDay}는 매월 N일(1~31). 기준일 이후 가장 가까운 그 일자를 다음
 *       실행일로 한다. 기준일이 그 일자이면 다음 달로. <b>해당 달의 실제 일수보다 큰 값은 그 달
 *       마지막 날로 fallback</b> (예: scheduleDay=31일인데 4월이면 4/30, 2월이면 2/28 또는 2/29).</li>
 * </ul>
 *
 * <p>"오늘 이미 지났으면 다음 주기" 정책 — 사용자가 오늘 정기 송금을 설정해도 오늘 즉시 실행되지 않고
 * 다음 정상 주기에 실행된다. 이중 실행(설정 즉시 + 스케줄러 트리거) 회피.
 *
 * <p>본 헬퍼는 stateless·side-effect-free라 단위 테스트에서 {@link LocalDate} 입력만으로 검증 가능.
 */
@Component
public class NextRunDateCalculator {

    /** 정기 송금 시간대 — 모든 날짜 계산의 기준. */
    public static final ZoneId ZONE_KST = ZoneId.of("Asia/Seoul");

    /** 오늘(KST) 기준 다음 실행일 계산. 일반적인 호출 경로. */
    public LocalDate calculate(TransferFrequency frequency, int scheduleDay) {
        return calculateFrom(frequency, scheduleDay, LocalDate.now(ZONE_KST));
    }

    /**
     * {@code baseDate} 기준 다음 실행일 계산. 스케줄러 회차 후 다음 실행일을 잡을 때 호출(다음 사이클에서 사용).
     * 단위 테스트에서도 시간을 고정하기 위해 활용.
     */
    public LocalDate calculateFrom(TransferFrequency frequency, int scheduleDay, LocalDate baseDate) {
        if (!frequency.isValidDay(scheduleDay)) {
            throw new IllegalArgumentException(
                    "scheduleDay out of range for " + frequency + ": " + scheduleDay);
        }
        return switch (frequency) {
            case WEEKLY -> nextWeekly(baseDate, scheduleDay);
            case MONTHLY -> nextMonthly(baseDate, scheduleDay);
        };
    }

    /**
     * baseDate 이후 가장 가까운 ISO 요일({@code dayOfWeek}). 같은 요일이면 7일 뒤(=다음 주).
     */
    private LocalDate nextWeekly(LocalDate baseDate, int dayOfWeek) {
        DayOfWeek target = DayOfWeek.of(dayOfWeek);
        // TemporalAdjusters.next(target)은 baseDate가 target과 같아도 7일 뒤를 반환 — "오늘 이미 지났음" 정책 일치.
        return baseDate.with(TemporalAdjusters.next(target));
    }

    /**
     * baseDate 이후 가장 가까운 매월 {@code dayOfMonth}. 같은 일자이면 다음 달.
     * 해당 달의 실제 일수보다 큰 값은 그 달 마지막 날로 fallback (예: 31 → 4월은 30, 2월은 28/29).
     */
    private LocalDate nextMonthly(LocalDate baseDate, int dayOfMonth) {
        // 이번 달 시도: baseDate.year/month로 dayOfMonth (또는 월말로 clamp).
        LocalDate thisMonth = clampToMonthEnd(baseDate.getYear(), baseDate.getMonthValue(), dayOfMonth);
        if (thisMonth.isAfter(baseDate)) {
            return thisMonth;  // 이번 달 target이 baseDate 이후 → 채택
        }
        // 이번 달 target이 baseDate 이전이거나 같음 → 다음 달로.
        LocalDate firstOfNextMonth = baseDate.withDayOfMonth(1).plusMonths(1);
        return clampToMonthEnd(firstOfNextMonth.getYear(), firstOfNextMonth.getMonthValue(), dayOfMonth);
    }

    /**
     * (year, month, day) 조합이 유효하면 그대로, day가 그 달 일수를 넘으면 그 달 마지막 날로.
     */
    private LocalDate clampToMonthEnd(int year, int month, int day) {
        int lastDayOfMonth = LocalDate.of(year, month, 1).lengthOfMonth();
        int effectiveDay = Math.min(day, lastDayOfMonth);
        return LocalDate.of(year, month, effectiveDay);
    }
}
