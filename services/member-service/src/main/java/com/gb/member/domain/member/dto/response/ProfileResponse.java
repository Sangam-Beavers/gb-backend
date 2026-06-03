package com.gb.member.domain.member.dto.response;

import com.gb.member.domain.member.entity.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * 마이페이지 내 프로필 응답. 조회(GET)·수정(PATCH) 응답 공통.
 *
 * <p>{@code isVerified}/{@code temperatureGrade}/{@code profileImageUrl} 3개는 각각 신분증 인증·커뮤니티
 * 매너온도·이미지 업로드 도메인 소관이라 아직 members에 저장하지 않는다. 화면 렌더를 위해 임시 기본값을
 * 내려보내고, 해당 도메인 구현 시 실제 값으로 교체한다(아래 from()의 TODO).
 *
 * <p>JSON은 전역 SNAKE_CASE 설정으로 변환된다(public_id/is_verified/...). {@code Boolean isVerified}로 둔 건
 * getter가 {@code getIsVerified()}가 되어 snake_case가 {@code is_verified}로 떨어지게 하기 위함(primitive면 verified로 떨어짐).
 */
@Getter
public class ProfileResponse {

    @Schema(description = "회원 식별자(UUID)")
    private final String publicId;

    @Schema(description = "이메일")
    private final String email;

    @Schema(description = "닉네임", example = "global_neighbor")
    private final String nickname;

    @Schema(description = "국적 코드(ISO 3166-1 alpha-2)", example = "VN")
    private final String nationality;

    @Schema(description = "주 사용 언어(BCP 47)", example = "ko")
    private final String language;

    @Schema(description = "자기소개(한 줄 소개). 미입력 시 null", nullable = true)
    private final String bio;

    @Schema(description = "신분증 인증 배지 여부. (현재 기본 false — 인증 도메인 구현 시 실제 값)")
    private final Boolean isVerified;

    @Schema(description = "커뮤니티 매너온도 등급(RED/YELLOW/GREEN/PURPLE/BLUE). (현재 기본값 — 커뮤니티 도메인 구현 시 실제 값)")
    private final String temperatureGrade;

    @Schema(description = "프로필 사진 URL. 미설정 시 null", nullable = true)
    private final String profileImageUrl;

    @Schema(description = "가입 일시(ISO 8601)")
    private final LocalDateTime createdAt;

    @Builder
    private ProfileResponse(String publicId, String email, String nickname, String nationality,
                            String language, String bio, Boolean isVerified, String temperatureGrade,
                            String profileImageUrl, LocalDateTime createdAt) {
        this.publicId = publicId;
        this.email = email;
        this.nickname = nickname;
        this.nationality = nationality;
        this.language = language;
        this.bio = bio;
        this.isVerified = isVerified;
        this.temperatureGrade = temperatureGrade;
        this.profileImageUrl = profileImageUrl;
        this.createdAt = createdAt;
    }

    public static ProfileResponse from(Member member) {
        return ProfileResponse.builder()
                .publicId(member.getPublicId())
                .email(member.getEmail())
                .nickname(member.getNickname())
                .nationality(member.getNationality())
                .language(member.getLanguage())
                .bio(member.getBio())
                // TODO: 아래 3개는 각각 신분증 인증/커뮤니티 매너온도/이미지 업로드 도메인 구현 시 실제 값으로 교체.
                .isVerified(false)
                .temperatureGrade("GREEN")
                .profileImageUrl(null)
                .createdAt(member.getCreatedAt())
                .build();
    }
}
