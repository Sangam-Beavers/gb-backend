package com.gb.member.domain.verification.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link VerificationRequest} Bean Validation 단위 테스트.
 *
 * <p>s3_key는 검증 없이 평문 그대로 컬럼(VARCHAR(500))에 저장되는 길이-경계 필드라, 한도 초과가
 * DB에서 COMMON5000(500)으로 터지지 않고 입력단 @Size에서 COMMON4001(400)로 걸리는지 고정한다
 * (10D member-verification-5). 위반 발생만 단언하면 충분 — @Valid → COMMON4001 변환은
 * GlobalExceptionHandler 공통 경로.
 */
class VerificationRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private VerificationRequest request(String s3Key) {
        VerificationRequest request = new VerificationRequest();
        ReflectionTestUtils.setField(request, "identityDocumentType", "ALIEN_REGISTRATION");
        ReflectionTestUtils.setField(request, "documentNumber", "990101-5678901");
        ReflectionTestUtils.setField(request, "s3Key", s3Key);
        return request;
    }

    @Test
    @DisplayName("정상 s3_key(500자 이하)는 검증을 통과한다")
    void s3Key_500자_이하_통과() {
        assertThat(validator.validate(request("verifications/a1b2c3d4/front.jpg"))).isEmpty();
        assertThat(validator.validate(request("a".repeat(500)))).isEmpty(); // 경계값
    }

    @Test
    @DisplayName("s3_key 501자는 @Size 위반 — 컬럼(500) 초과가 500이 아닌 COMMON4001(400) 경로로 떨어진다")
    void s3Key_501자_Size_위반() {
        Set<ConstraintViolation<VerificationRequest>> violations =
                validator.validate(request("a".repeat(501)));

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("s3Key"));
    }
}
