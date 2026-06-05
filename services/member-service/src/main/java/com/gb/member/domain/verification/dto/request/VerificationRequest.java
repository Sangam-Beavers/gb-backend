package com.gb.member.domain.verification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    // s3_key는 형식·enum 검증 없이 평문 그대로 컬럼(VARCHAR(500))에 저장되는 유일한 길이-경계 필드라
    // @Size로 컬럼 한도를 입력단에서 막는다(초과 시 COMMON5000(500)이 아닌 COMMON4001(400) — 10D member-verification-5).
    // documentNumber는 암호화로 길이가 늘어나(§15-3) 단순 @Size가 부정확하므로 손대지 않는다.
    @Schema(description = "사전 업로드된 신분증 이미지의 S3 key(최대 500자)",
            example = "verifications/a1b2c3d4/front.jpg", maxLength = 500)
    @NotBlank(message = "s3_key는 필수입니다")
    @Size(max = 500, message = "s3_key는 500자를 넘을 수 없습니다")
    private String s3Key;
}
