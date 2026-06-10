package com.gb.appadmin.global.client;

import com.gb.appadmin.domain.member.dto.response.AppMemberPageResponse;

public interface MemberAdminClient {

    AppMemberPageResponse search(String q, String kycStatus, int page, int size);

    void changeStatus(String userPublicId, String status);

    void setCommunityBan(String userPublicId, boolean banned);
}
