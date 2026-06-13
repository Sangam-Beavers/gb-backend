package com.gb.admin.global.client;

import java.util.List;

/**
 * member-service {@code /internal/admin/stats/demographics} 응답의 admin-service 측 모델.
 *
 * <p>성별/연령대/국적 분포 버킷 목록. key는 enum 이름 또는 국적 ISO alpha-2 코드.
 */
public record AdminMemberDemographics(
        List<Bucket> genderDistribution,
        List<Bucket> ageDistribution,
        List<Bucket> nationalityDistribution
) {
    public record Bucket(String key, long count) {
    }

    /** 모든 분포가 빈 안전 기본값(클라이언트 실패 시 fail-open 용). */
    public static AdminMemberDemographics empty() {
        return new AdminMemberDemographics(List.of(), List.of(), List.of());
    }
}
