package com.gb.member.domain.member.controller;

import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import com.gb.member.domain.member.dto.response.CheckAvailabilityResponse;
import com.gb.member.domain.member.dto.response.MemberDisplayListResponse;
import com.gb.member.domain.member.dto.response.MemberDisplayResponse;
import com.gb.member.domain.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 조회 계열 API. 두 부류를 담당한다:
 * <ul>
 *   <li><b>가입 전 공개 조회</b>(check-email/check-nickname) — 인증 불필요(SecurityConfig permitAll).
 *       가입 폼의 "중복확인" 버튼이 호출하는 사전 확인용.</li>
 *   <li><b>서비스 간 표시정보 조회</b>(display-info/by-email) — 인증 필요. community/wallet의
 *       MemberClient가 현재 요청의 JWT를 그대로 릴레이해 호출한다(작성자 닉네임·송금 수신자 표시/검증).
 *       MSA 경계라 식별자는 public_id(UUID)만 받는다(§7 — 타 서비스의 member DB 직접 SELECT 금지).</li>
 * </ul>
 *
 * <p>가입 자체(/api/v1/auth/register)는 {@link MemberController}가 담당한다.
 *
 * <p>{@code @Validated}가 있어야 {@code @RequestParam}의 제약(@Email/@NotBlank/@Size)이 활성화된다.
 * 위반 시 ConstraintViolationException → GlobalExceptionHandler가 COMMON4001(400)로 변환한다.
 */
@Tag(name = "Member", description = "회원 조회 API")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/v1/members")
public class MemberQueryController {

    private final MemberService memberService;

    @GetMapping("/check-email")
    @Operation(
            summary = "이메일 중복 확인",
            description = "이메일이 사용 가능한지(중복이 아닌지) 확인한다. available=true면 사용 가능.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "확인 성공. data.available 로 사용 가능 여부 반환."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 이메일이 비었거나 형식이 올바르지 않습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<CheckAvailabilityResponse> checkEmail(
            @RequestParam
            @NotBlank(message = "이메일은 필수입니다")
            @Email(message = "이메일 형식이 올바르지 않습니다") String email) {
        return ApiResponse.success(memberService.checkEmail(email));
    }

    @GetMapping("/check-nickname")
    @Operation(
            summary = "닉네임 중복 확인",
            description = "닉네임이 사용 가능한지(중복이 아닌지) 확인한다. available=true면 사용 가능.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "확인 성공. data.available 로 사용 가능 여부 반환."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 닉네임이 비어 있습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<CheckAvailabilityResponse> checkNickname(
            @RequestParam
            @NotBlank(message = "닉네임은 필수입니다") String nickname) {
        return ApiResponse.success(memberService.checkNickname(nickname));
    }

    /** display-info 배치 조회의 public_id 개수 상한 — 무제한 IN 절·URL 길이 폭주 가드. */
    private static final int DISPLAY_INFO_MAX_IDS = 100;

    @GetMapping("/display-info")
    @Operation(
            summary = "회원 표시정보 배치 조회 (서비스 간)",
            description = "public_id 목록(콤마 구분, 최대 100개)으로 표시정보(이름/닉네임/국적/인증배지/신뢰등급)를 "
                    + "한 번에 조회한다. community/wallet의 MemberClient가 작성자·수신자 표시를 채울 때 "
                    + "호출한다(N+1 회피 배치). 존재하는 활성(미탈퇴) 회원만 배열에 담기며, 미존재·탈퇴 id는 "
                    + "항목에서 제외된다(호출 측이 \"Unknown\" 폴백 처리 — 에러 아님).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data.members 로 존재하는 활성 회원의 표시정보 목록 반환."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - public_ids가 비었거나 100개를 초과했습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.(토큰 누락/무효)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<MemberDisplayListResponse> getDisplayInfos(
            @RequestParam(name = "public_ids")
            @NotEmpty(message = "public_ids는 필수입니다")
            @Size(max = DISPLAY_INFO_MAX_IDS, message = "public_ids는 최대 " + DISPLAY_INFO_MAX_IDS + "개까지 가능합니다")
            List<@NotBlank(message = "public_id는 비어 있을 수 없습니다") String> publicIds) {
        return ApiResponse.success(memberService.getDisplayInfos(publicIds));
    }

    @GetMapping("/by-email")
    @Operation(
            summary = "이메일로 회원 표시정보 조회 (서비스 간)",
            description = "이메일로 활성(미탈퇴) 회원의 표시정보를 단건 조회한다. wallet의 "
                    + "validate-member(송금 수신자 검증)가 호출한다. 검증 용도라 미존재·탈퇴 시 폴백 없이 "
                    + "404 MEMBER4001로 응답한다(fail-fast).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공. data 로 표시정보 반환."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "COMMON4001 - 이메일이 비었거나 형식이 올바르지 않습니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "AUTH4011 - 인증이 필요합니다.(토큰 누락/무효)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "MEMBER4001 - 존재하지 않는 회원입니다.(탈퇴 포함)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ApiResponse<MemberDisplayResponse> getByEmail(
            @RequestParam
            @NotBlank(message = "이메일은 필수입니다")
            @Email(message = "이메일 형식이 올바르지 않습니다") String email) {
        return ApiResponse.success(memberService.getDisplayInfoByEmail(email));
    }
}
