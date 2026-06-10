package com.gb.appadmin.global.client;

import com.gb.appadmin.domain.member.dto.response.UserActivityResponse;

public interface CommunityAdminClient {

    UserActivityResponse getUserActivity(String userPublicId, int postPage, int commentPage, int size);
}
