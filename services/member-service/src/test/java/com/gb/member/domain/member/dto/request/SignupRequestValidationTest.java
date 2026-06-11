package com.gb.member.domain.member.dto.request;

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
 * {@link SignupRequest} Bean Validation 단위 테스트.
 *
 * <p>@Size 상한이 members 컬럼 길이(database.md §members SSOT)와 같은 값으로 입력단에서 걸려,
 * 과길이 입력이 INSERT 단계 500(DataIntegrityViolation)이 아니라 COMMON4001(400) 경로로
 * 떨어지는지 고정한다. 위반 발생만 단언하면 충분 — @Valid → COMMON4001
 * 변환은 GlobalExceptionHandler 공통 경로.
 */
class SignupRequestValidationTest {

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

    private SignupRequest request(String name, String nickname, String nationality, String language) {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", "user@example.com");
        ReflectionTestUtils.setField(request, "password", "P@ssw0rd!");
        ReflectionTestUtils.setField(request, "name", name);
        ReflectionTestUtils.setField(request, "nickname", nickname);
        ReflectionTestUtils.setField(request, "nationality", nationality);
        ReflectionTestUtils.setField(request, "language", language);
        // 성별·연령대(이슈 #203)는 @NotBlank 필수 — 길이 경계 검증 케이스가 이들 누락으로 오염되지 않게 유효값을 채운다.
        ReflectionTestUtils.setField(request, "gender", "MALE");
        ReflectionTestUtils.setField(request, "ageRange", "TWENTIES");
        return request;
    }

    @Test
    @DisplayName("컬럼 길이 경계값(name 100·nickname 50·nationality 10·language 10)은 검증을 통과한다")
    void 경계값_통과() {
        SignupRequest boundary = request(
                "가".repeat(100), "n".repeat(50), "N".repeat(10), "l".repeat(10));
        assertThat(validator.validate(boundary)).isEmpty();
    }

    @Test
    @DisplayName("컬럼 길이+1 입력은 @Size 위반 — 과길이가 500이 아닌 COMMON4001(400) 경로로 떨어진다")
    void 초과값_Size_위반() {
        // 필드별로 하나씩 한도+1을 줘 4개 위반이 모두 잡히는지 확인한다.
        SignupRequest over = request(
                "가".repeat(101), "n".repeat(51), "N".repeat(11), "l".repeat(11));
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(over);
        assertThat(violations).hasSize(4);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("name", "nickname", "nationality", "language");
    }
}
