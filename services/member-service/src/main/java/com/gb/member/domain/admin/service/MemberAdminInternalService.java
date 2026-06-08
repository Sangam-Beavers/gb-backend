package com.gb.member.domain.admin.service;

import com.gb.member.domain.admin.dto.response.AdminMemberLookupResponse;
import com.gb.member.domain.admin.dto.response.AdminMemberPageResponse;
import com.gb.member.domain.admin.dto.response.AdminMemberView;
import com.gb.member.domain.admin.dto.response.MemberStatsResponse;
import java.util.Collection;

public interface MemberAdminInternalService {

    AdminMemberPageResponse search(String q, String kycStatus, int page, int size);

    AdminMemberView get(String publicId);

    void approveKyc(String publicId);

    void rejectKyc(String publicId, String reason);

    AdminMemberLookupResponse lookup(Collection<String> userPublicIds);

    MemberStatsResponse getStats();
}
