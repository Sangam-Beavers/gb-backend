package com.gb.member.domain.member.dto.response;

import com.gb.member.domain.member.entity.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 회원 표시정보 응답 1건. 표시정보 배치 조회(display-info)의 항목과 이메일 단건 조회(by-email)의
 * data가 공유한다(같은 표시 필드 집합 — 명세 auth api-spec §13(§13-1/§13-2)).
 *
 * <p>다른 서비스(community/wallet)가 작성자 닉네임·송금 수신자 표시를 채우는 용도라,
 * <b>실사용처가 있는 표시 필드만</b> 내려보낸다(PII 최소화):
 * <ul>
 *   <li>{@code name} — 송금 확인증의 sender/receiver 본명(wallet)</li>
 *   <li>{@code nickname} — 게시글/댓글 작성자·최근 송금 수신자 표시(community/wallet)</li>
 *   <li>{@code nationality} — 최근 송금 수신자 국적 표시(wallet)</li>
 *   <li>{@code isVerified} — 신분증 인증 배지(community/wallet)</li>
 *   <li>{@code profileImageUrl} — 프로필 사진 URL(community 게시글/댓글 작성자 아바타).
 *       이미지 업로드 도메인 미구현이라 현재 항상 null(ProfileResponse와 동일) — 구현 시 실제 값으로 교체.</li>
 * </ul>
 * {@code email}은 어떤 호출 측도 소비하지 않아 노출하지 않는다. 내부 {@code id}(BIGINT)는 경계 밖 금지(§7).
 *
 * <p>JSON은 전역 SNAKE_CASE 설정으로 변환된다(public_id/is_verified). {@code Boolean isVerified}로 둔 건
 * getter가 {@code getIsVerified()}가 되어 snake_case가 {@code is_verified}로 떨어지게 하기 위함(primitive면 verified로 떨어짐).
 */
@Getter
public class MemberDisplayResponse {

    @Schema(description = "회원 식별자(UUID)", example = "11111111-1111-1111-1111-111111111111")
    private final String publicId;

    @Schema(description = "이름(본명). 송금 확인증 등 격식 문서 표시용", example = "Nguyen Thi Linh")
    private final String name;

    @Schema(description = "닉네임", example = "Linh")
    private final String nickname;

    @Schema(description = "국적 코드(ISO 3166-1 alpha-2)", example = "VN")
    private final String nationality;

    @Schema(description = "신분증 인증 배지 여부", example = "true")
    private final Boolean isVerified;

    @Schema(description = "프로필 사진 URL. 이미지 도메인 미구현으로 현재 항상 null", example = "null",
            nullable = true)
    private final String profileImageUrl;

    @Builder
    private MemberDisplayResponse(String publicId, String name, String nickname,
                                  String nationality, Boolean isVerified, String profileImageUrl) {
        this.publicId = publicId;
        this.name = name;
        this.nickname = nickname;
        this.nationality = nationality;
        this.isVerified = isVerified;
        this.profileImageUrl = profileImageUrl;
    }

    public static MemberDisplayResponse from(Member member) {
        return MemberDisplayResponse.builder()
                .publicId(member.getPublicId())
                .name(member.getName())
                .nickname(member.getNickname())
                .nationality(member.getNationality())
                .isVerified(member.isVerified())
                // 이미지 도메인 미구현 — 항상 null. 컬럼/업로드 구현 시 member.getProfileImageUrl()로 교체.
                .profileImageUrl(null)
                .build();
    }
}
