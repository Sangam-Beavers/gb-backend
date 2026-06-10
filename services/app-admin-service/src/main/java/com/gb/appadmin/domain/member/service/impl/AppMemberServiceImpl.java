package com.gb.appadmin.domain.member.service.impl;

import com.gb.appadmin.domain.member.dto.response.AppMemberPageResponse;
import com.gb.appadmin.domain.member.dto.response.UserActivityResponse;
import com.gb.appadmin.domain.member.service.AppMemberService;
import com.gb.appadmin.global.client.CommunityAdminClient;
import com.gb.appadmin.global.client.MemberAdminClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppMemberServiceImpl implements AppMemberService {

    private final MemberAdminClient memberAdminClient;
    private final CommunityAdminClient communityAdminClient;

    @Override
    public AppMemberPageResponse search(String q, String kycStatus, int page, int size) {
        return memberAdminClient.search(q, kycStatus, page, size);
    }

    @Override
    public void changeStatus(String userPublicId, String status) {
        memberAdminClient.changeStatus(userPublicId, status);
    }

    @Override
    public void setCommunityBan(String userPublicId, boolean banned) {
        memberAdminClient.setCommunityBan(userPublicId, banned);
    }

    @Override
    public UserActivityResponse getUserActivity(String userPublicId, int postPage, int commentPage, int size) {
        return communityAdminClient.getUserActivity(userPublicId, postPage, commentPage, size);
    }
}
