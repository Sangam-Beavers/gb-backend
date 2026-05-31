package com.gb.community.global.common.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 응답 시각 직렬화 공통 유틸. {@link LocalDateTime}을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다
 * (CLAUDE.md §5 — 시각은 UTC Z 문자열). 초 단위로 절삭한다.
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