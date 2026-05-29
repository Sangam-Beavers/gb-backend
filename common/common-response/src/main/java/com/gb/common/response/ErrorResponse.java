package com.gb.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 실패 응답 포맷. {@code data} 필드 자체가 없으므로 실패 응답에는 data 키가 직렬화되지 않는다.
 * 성공 응답({@link ApiResponse})에 {@code code}가 없는 것과 대칭을 이룬다.
 *
 * <p>아래 {@link Schema} 어노테이션은 Swagger UI 표시 전용 — 직렬화 동작에는 영향이 없다.
 * 필드/생성자/팩토리 시그니처는 그대로 유지된다.
 */
@Getter
@Schema(description = "공통 실패 응답. 성공 응답과 달리 data 키가 없고 비즈니스 code가 포함된다.")
public class ErrorResponse {

    @Schema(description = "성공 여부. 실패 응답에선 항상 false.", example = "false")
    private final boolean success;

    @Schema(description = "비즈니스 에러 코드({DOMAIN}{4자리}).", example = "WALLET4001")
    private final String code;

    @Schema(description = "사용자에게 보여줄 에러 메시지.", example = "존재하지 않는 지갑입니다.")
    private final String message;

    private ErrorResponse(String code, String message) {
        this.success = false;
        this.code = code;
        this.message = message;
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message);
    }
}
