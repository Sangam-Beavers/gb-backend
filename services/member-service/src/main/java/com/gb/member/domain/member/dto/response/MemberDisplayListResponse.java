package com.gb.member.domain.member.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Getter;

/**
 * 회원 표시정보 배치 조회(display-info) 응답 data.
 *
 * <p>요청한 public_id 중 <b>존재하는 활성(미탈퇴) 회원만</b> 배열에 담는다 — 미존재·탈퇴 회원은
 * 항목에서 제외되며, 호출 측(community/wallet의 MemberClient)이 누락 id를 "Unknown" 폴백으로
 * 채운다(인터페이스 계약). 순서는 보장하지 않는다(호출 측이 public_id 키 맵으로 소비).
 */
@Getter
public class MemberDisplayListResponse {

    @Schema(description = "요청 public_id 중 존재하는 활성 회원의 표시정보 목록(미존재·탈퇴는 제외)")
    private final List<MemberDisplayResponse> members;

    private MemberDisplayListResponse(List<MemberDisplayResponse> members) {
        this.members = members;
    }

    public static MemberDisplayListResponse of(List<MemberDisplayResponse> members) {
        return new MemberDisplayListResponse(members);
    }
}
