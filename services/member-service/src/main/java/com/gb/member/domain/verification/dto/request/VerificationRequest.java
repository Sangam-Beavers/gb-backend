package com.gb.member.domain.verification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 신분증 인증 요청 본문. (POST /api/v1/members/me/verification)
 *
 * <p>필드는 camelCase로 두고 전역 SNAKE_CASE 설정이 JSON을 {@code identity_document_type}/
 * {@code document_number}/{@code s3_key}로 변환한다.
 *
 * <p>여기서는 "빈 값 여부"만 Bean Validation으로 본다(@NotBlank → COMMON4001).
 * 신분증 <b>유형 enum 변환</b>과 <b>번호 형식(정규식)</b> 검증은 Service에서 수행한다(CLAUDE §6 —
 * enum/형식 검증은 @Pattern로 박지 않고 Service에서 처리).
 */
@Getter
@NoArgsConstructor
public class VerificationRequest {

    @Schema(description = "신분증 유형", example = "ALIEN_REGISTRATION",
            allowableValues = {"ALIEN_REGISTRATION", "PASSPORT", "NATIONAL_ID"})
    @NotBlank(message = "신분증 유형은 필수입니다")
    private String identityDocumentType;

    @Schema(description = "문서 번호(외국인등록번호 등). 유형별 형식 검증 후 저장",
            example = "990101-5678901")
    @NotBlank(message = "문서 번호는 필수입니다")
    private String documentNumber;

    @Schema(description = "사전 업로드된 신분증 이미지의 S3 key",
            example = "verifications/a1b2c3d4/front.jpg")
    @NotBlank(message = "s3_key는 필수입니다")
    private String s3Key;
}
