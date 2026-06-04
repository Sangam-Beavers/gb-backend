package com.gb.wallet.domain.account.dto.response;

import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.global.common.util.AccountNumberMasker;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import lombok.Getter;

/**
 * 등록된 내 계좌 목록의 단일 항목.
 * snake_case 변환 규칙은 컨테이너 DTO({@link AccountListResponse}) javadoc 참고.
 */
@Getter
public class AccountResponse {

    @Schema(description = "계좌 식별자(UUID)", example = "9b2e4c1a-7f3d-4b8e-9a1c-2d5e6f7a8b9c")
    private final String accountPublicId;

    @Schema(description = "은행 코드", example = "004")
    private final String bankCode;

    @Schema(description = "은행명", example = "KB국민은행")
    private final String bankName;

    @Schema(description = "마스킹된 계좌번호 (앞 3자리 + 끝 2자리 노출)", example = "123*********34")
    private final String accountNumberMasked;

    // Boolean(wrapper) 사용 이유: primitive boolean + 필드명 isXxx 조합이면 Lombok @Getter가
    // isXxx() getter를 만들고, Jackson은 표준 Bean 규약에 따라 is prefix를 떼고 property를
    // "primary"로 추출해 응답이 {"primary": ...}로 나간다(명세는 "is_primary"). wrapper면
    // getIsXxx()가 생성되어 property가 "isPrimary"가 되고, 전역 SNAKE_CASE 전략으로 "is_primary"로 직렬화된다.
    @Schema(description = "주 계좌 여부", example = "true")
    private final Boolean isPrimary;

    @Schema(description = "가상계좌(앱 내부 발급) 여부", example = "false")
    private final Boolean isVirtual;

    @Schema(description = "외부 은행 인증 완료 여부 (mock_account_token 존재 && is_active)", example = "true")
    private final Boolean isVerified;

    @Schema(description = "계좌 등록 시각(ISO 8601, UTC Z)", example = "2026-05-26T04:15:30Z")
    private final String createdAt;

    @Builder
    private AccountResponse(String accountPublicId, String bankCode, String bankName, String accountNumberMasked,
                            Boolean isPrimary, Boolean isVirtual, Boolean isVerified, String createdAt) {
        this.accountPublicId = accountPublicId;
        this.bankCode = bankCode;
        this.bankName = bankName;
        this.accountNumberMasked = accountNumberMasked;
        this.isPrimary = isPrimary;
        this.isVirtual = isVirtual;
        this.isVerified = isVerified;
        this.createdAt = createdAt;
    }

    public static AccountResponse from(BankAccount bankAccount) {
        // is_verified: 외부(Mock) 은행이 발급한 계좌 토큰이 있고 계좌가 활성 상태일 때 인증된 것으로 간주.
        boolean verified = bankAccount.getMockAccountToken() != null && bankAccount.isActive();
        return AccountResponse.builder()
                .accountPublicId(bankAccount.getPublicId())
                .bankCode(bankAccount.getBank().getCode())
                .bankName(bankAccount.getBank().getName())
                .accountNumberMasked(AccountNumberMasker.mask(bankAccount.getAccountNumber()))
                .isPrimary(bankAccount.isPrimary())
                .isVirtual(bankAccount.isVirtual())
                .isVerified(verified)
                .createdAt(toUtcZ(bankAccount.getCreatedAt()))
                .build();
    }

    /**
     * LocalDateTime을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다.
     * 초 단위로 절삭해 명세 mock 포맷("2026-05-26T04:15:30Z")과 일치시킨다.
     */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
