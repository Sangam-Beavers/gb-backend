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
 *
 * <p><b>s3_key는 현재 선택(optional).</b> OCR/이미지 업로드 도입 전 데모 단계에서는 사용자가
 * 신분증 번호만 직접 입력해 인증한다(이슈 #152). 추후 이미지 업로드가 도입되면 다시 필수로 복구한다.
 */
@Getter
@NoArgsConstructor
public class VerificationRequest {

    @Schema(description = "신분증 유형 — 외국인 등록증 또는 4개국 본국 신분증(이슈 #108)",
            example = "ALIEN_REGISTRATION",
            allowableValues = {
                    "ALIEN_REGISTRATION",
                    "NATIONAL_ID_KR",
                    "NATIONAL_ID_US",
                    "NATIONAL_ID_VN",
                    "NATIONAL_ID_PH"
            })
    @NotBlank(message = "신분증 유형은 필수입니다")
    private String identityDocumentType;

    @Schema(description = "문서 번호(외국인등록번호 등). 유형별 형식 검증 후 저장",
            example = "990101-5678901")
    @NotBlank(message = "문서 번호는 필수입니다")
    private String documentNumber;

    /**
     * 사전 업로드된 신분증 이미지의 S3 key. <b>현재(OCR 미도입) 선택값.</b> 길이 제한은 DB 컬럼과 동일한 500자
     * — 형식 검증 없이 평문 그대로 저장되는 길이-경계 필드라 @Size로 컬럼 한도를 입력단에서 막는다
     * (초과 시 COMMON5000(500)이 아닌 COMMON4001(400), 10D member-verification-5).
     * 미전송(null) 또는 빈 문자열 허용 — 형식 검증 통과만으로 인증된다(이슈 #152). OCR 도입 시 필수 복구.
     */
    @Schema(description = "사전 업로드된 신분증 이미지의 S3 key (선택 — OCR 도입 전 임시 정책, 최대 500자)",
            example = "verifications/a1b2c3d4/front.jpg", nullable = true, maxLength = 500)
    @Size(max = 500, message = "s3_key는 500자 이내여야 합니다")
    private String s3Key;
}
