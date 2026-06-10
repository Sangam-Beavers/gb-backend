package com.gb.appadmin.domain.member.service;

import com.gb.appadmin.domain.member.dto.response.AppMemberPageResponse;
import com.gb.appadmin.domain.member.dto.response.UserActivityResponse;

public interface AppMemberService {

    AppMemberPageResponse search(String q, String kycStatus, int page, int size);

    void changeStatus(String userPublicId, String status);

    void setCommunityBan(String userPublicId, boolean banned);

    UserActivityResponse getUserActivity(String userPublicId, int postPage, int commentPage, int size);
}
