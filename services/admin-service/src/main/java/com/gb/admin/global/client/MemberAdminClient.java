package com.gb.admin.global.client;

import org.springframework.data.domain.Page;

/**
 * member-service의 admin 전용 엔드포인트를 호출하는 클라이언트 계약.
 *
 * <p>Phase 1은 {@link MockMemberAdminClient}({@code @Profile("dev")}) fixture만 동작한다.
 * 다음 스프린트에서 member-service에 {@code /internal/admin/members/*} 엔드포인트를 도입한 뒤
 * {@code RealMemberAdminClient}로 활성화한다(현재 빈 미등록).
 */
public interface MemberAdminClient {

    Page<AdminMemberSummary> search(String q, KycStatus kycStatus, int page, int size);

    void approveKyc(String userPublicId, String adminPublicId);

    void rejectKyc(String userPublicId, String adminPublicId, String reason);

    /**
     * user_public_id 묶음을 한 번에 조회한다(N+1 방지). 미존재 id 는 결과 맵에서 제외된다 —
     * 호출 측이 "Unknown" 폴백을 직접 적용한다.
     */
    java.util.Map<String, AdminMemberMini> lookup(java.util.Collection<String> userPublicIds);

    AdminMemberStats stats();
}
