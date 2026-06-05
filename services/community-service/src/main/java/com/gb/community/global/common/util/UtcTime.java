package com.gb.community.global.common.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 응답 시각 직렬화 공통 유틸. {@link LocalDateTime}을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다
 * (CLAUDE.md §5 — 시각은 UTC Z 문자열). 초 단위로 절삭한다.
 *
 * <p><b>"저장값 = UTC" 가정의 보장(10D community-3):</b> 과거엔 JPA Auditing이 JVM 기본존으로 캡처해
 * 비-UTC JVM에서 이 변환이 오프셋을 틀었다. 현재는 JpaConfig의 {@code utcDateTimeProvider}(Auditing)와
 * 비감사 캡처부의 {@code LocalDateTime.now(ZoneOffset.UTC)} 통일로 저장값이 항상 UTC라 이 간주가 참이다
 * — 본 유틸은 시스템존을 읽지 않는다(읽으면 JVM 환경 의존이 재발).
 *
 * <p>wallet-service는 같은 로직을 응답 DTO마다 복제했지만, 여기서는 중복을 피해 한 곳에 둔다.
 */
public final class UtcTime {

    private UtcTime() {
    }

    public static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}