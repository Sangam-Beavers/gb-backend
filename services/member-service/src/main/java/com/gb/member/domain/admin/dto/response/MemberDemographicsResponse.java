package com.gb.member.domain.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 회원 인구통계 응답 — 성별/연령대/국적 분포(business-analytics용).
 *
 * <p>각 분포는 {@code {key, count}} 버킷 목록이다. key는 enum 이름(SCREAMING_SNAKE_CASE)
 * 또는 국적 ISO 3166-1 alpha-2 코드. 라벨 변환은 화면(admin frontend)이 담당한다.
 */
@Schema(description = "회원 인구통계(성별/연령대/국적 분포)")
public record MemberDemographicsResponse(
        @Schema(description = "성별 분포(MALE/FEMALE)")
        List<Bucket> genderDistribution,
        @Schema(description = "연령대 분포(TEENS~SIXTIES_PLUS)")
        List<Bucket> ageDistribution,
        @Schema(description = "국적 분포(ISO alpha-2, 많은 순)")
        List<Bucket> nationalityDistribution
) {
    @Schema(description = "분포 버킷")
    public record Bucket(
            @Schema(description = "구분 키", example = "MALE")
            String key,
            @Schema(description = "회원 수", example = "1240")
            long count
    ) {
    }
}
