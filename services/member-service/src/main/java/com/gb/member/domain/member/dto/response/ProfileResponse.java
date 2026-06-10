package com.gb.member.domain.member.dto.response;

import com.gb.member.domain.member.entity.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 마이페이지 내 프로필 응답. 조회(GET)·수정(PATCH) 응답 공통.
 *
 * <p>{@code isVerified}는 신분증 인증 도메인 구현으로 실제 값(members.is_verified)을 내려보낸다.
 * {@code trustGrade}는 마일스톤 기반 신뢰등급 저장값(members.trust_grade — 이슈 #193, 레거시
 * "생활온도(temperature_grade)" 폐기·대체)을 내려보낸다. {@code profileImageUrl}은 이미지 업로드 도메인
 * 소관이라 아직 members에 저장하지 않고 임시 기본값(null)을 내려보낸다(해당 도메인 구현 시 교체).
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

    @Schema(description = "신분증 인증 배지 여부. user_verifications APPROVED 시 true")
    private final Boolean isVerified;

    @Schema(description = "마일스톤 기반 신뢰등급. NEWCOMER=가입 기본, VERIFIED=신분증 인증 승인(이슈 #193)",
            allowableValues = {"NEWCOMER", "VERIFIED"}, example = "VERIFIED")
    private final String trustGrade;

    @Schema(description = "프로필 사진 URL. 미설정 시 null", nullable = true)
    private final String profileImageUrl;

    @Schema(description = "가입 일시(ISO 8601, UTC Z)", example = "2026-06-03T18:21:08Z")
    private final String createdAt;

    @Builder
    private ProfileResponse(String publicId, String email, String nickname, String nationality,
                            String language, String bio, Boolean isVerified, String trustGrade,
                            String profileImageUrl, String createdAt) {
        this.publicId = publicId;
        this.email = email;
        this.nickname = nickname;
        this.nationality = nationality;
        this.language = language;
        this.bio = bio;
        this.isVerified = isVerified;
        this.trustGrade = trustGrade;
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
                // is_verified·trust_grade는 members 저장값 그대로(이슈 #193 — 하드코딩 "GREEN" 제거).
                // profile_image_url은 이미지 업로드 도메인 구현 시 교체.
                .isVerified(member.isVerified())
                .trustGrade(member.getTrustGrade().name())
                .profileImageUrl(null)
                .createdAt(toUtcZ(member.getCreatedAt()))
                .build();
    }

    /** LocalDateTime → ISO-8601 UTC 'Z' 문자열(초 단위 절삭). wallet/community DTO와 동일 규칙. */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
