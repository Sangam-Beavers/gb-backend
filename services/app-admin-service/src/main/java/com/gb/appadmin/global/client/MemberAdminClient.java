package com.gb.appadmin.global.client;

import com.gb.appadmin.domain.member.dto.response.AppMemberPageResponse;
import com.gb.appadmin.domain.member.dto.response.AppMemberResponse;
import java.util.Optional;

public interface MemberAdminClient {

    AppMemberPageResponse search(String q, String kycStatus, int page, int size);

    void changeStatus(String userPublicId, String status);

    void setCommunityBan(String userPublicId, boolean banned);

    /**
     * member-service /internal/admin/members/{id} 단건 조회.
     * 404이면 empty, 그 외 오류는 COMMON5000.
     */
    Optional<AppMemberResponse> getMemberByPublicId(String userPublicId);
}
