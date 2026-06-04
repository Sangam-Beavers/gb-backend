package com.gb.wallet.domain.account.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link AccountResponse#from(BankAccount)}의 마스킹·파생필드 변환 단위 테스트.
 *
 * <p>마스킹은 공용 {@code AccountNumberMasker}에 위임하므로 공개 진입점 {@code from()}을 통해 검증한다.
 * JPA·Spring 없이 순수 객체로 돌린다. BaseEntity의 {@code createdAt}은 비영속 객체라 auditing이 덮어쓰지
 * 않으므로 {@link ReflectionTestUtils}로 직접 주입한다 — "persist 후 native UPDATE" 규칙은 auditing이
 * {@code @PrePersist}에서 값을 덮어쓰는 영속 테스트에만 해당하며, 여기선 @PrePersist가 발화하지 않는다.
 *
 * <p><b>WACC-08:</b> 계좌번호 마스킹은 {@code AccountNumberMasker}(앞3 + 별표 + 끝2) 단일 SSOT로 통일됐다.
 * {@code AccountResponse}는 그 유틸에 위임하며, 과거 도메인별로 달랐던 규칙(끝4 vs 끝2)은 더 보수적인 끝2로 합쳐졌다.
 */
class AccountResponseTest {

    private static Bank bank() {
        return Bank.builder()
                .code("004").name("KB국민은행").country("KR").isDomestic(true).isActive(true)
                .build();
    }

    private static BankAccount account(String accountNumber, String mockAccountToken,
                                       boolean isActive, LocalDateTime createdAt) {
        BankAccount account = BankAccount.builder()
                .publicId("acct-public-id")
                .userPublicId("user-public-id")
                .bank(bank())
                .accountNumber(accountNumber)
                .holderName("홍길동")
                .mockAccountToken(mockAccountToken)
                .isVirtual(false)
                .isPrimary(true)
                .isActive(isActive)
                .build();
        ReflectionTestUtils.setField(account, "createdAt", createdAt);
        return account;
    }

    @Test
    @DisplayName("mask: 일반(길이≥6)은 앞3 + 별표(len-5) + 끝2")
    void mask_일반_길이14() {
        AccountResponse response = AccountResponse.from(
                account("12345678901234", "tok", true, null));
        // 길이 14 → "123" + "*"×9 + "34"
        assertThat(response.getAccountNumberMasked()).isEqualTo("123*********34");
    }

    @Test
    @DisplayName("mask: 경계 길이 6은 별표 1개(AB1*2D)")
    void mask_경계_길이6() {
        AccountResponse response = AccountResponse.from(
                account("AB1C2D", "tok", true, null));
        assertThat(response.getAccountNumberMasked()).isEqualTo("AB1*2D");
    }

    @Test
    @DisplayName("mask: 길이 5 이하는 전체 마스킹(방어)")
    void mask_길이5이하_전체마스킹() {
        // 길이 4 → 전체 마스킹
        assertThat(AccountResponse.from(account("1234", "tok", true, null)).getAccountNumberMasked())
                .isEqualTo("****");
        // 경계 길이 5(임계값) — 여전히 전체 마스킹(앞3+끝2 규칙은 6자부터 적용)
        assertThat(AccountResponse.from(account("12345", "tok", true, null)).getAccountNumberMasked())
                .isEqualTo("*****");
    }

    @Test
    @DisplayName("mask: accountNumber가 null이면 null(NPE 없음)")
    void mask_null_입력() {
        assertThat(AccountResponse.from(account(null, "tok", true, null)).getAccountNumberMasked())
                .isNull();
    }

    @Test
    @DisplayName("is_verified: mock_account_token이 있고 활성이면 true")
    void isVerified_토큰있고_활성() {
        assertThat(AccountResponse.from(account("12345678901234", "tok", true, null)).getIsVerified())
                .isTrue();
    }

    @Test
    @DisplayName("is_verified: 토큰이 null이면 false")
    void isVerified_토큰_null() {
        assertThat(AccountResponse.from(account("12345678901234", null, true, null)).getIsVerified())
                .isFalse();
    }

    @Test
    @DisplayName("is_verified: 토큰이 있어도 비활성이면 false")
    void isVerified_비활성() {
        assertThat(AccountResponse.from(account("12345678901234", "tok", false, null)).getIsVerified())
                .isFalse();
    }

    @Test
    @DisplayName("created_at: UTC Z + 초 절삭(밀리/나노초 버림)")
    void createdAt_UTC_Z_초절삭() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 26, 4, 15, 30, 123_456_789);
        AccountResponse response = AccountResponse.from(account("12345678901234", "tok", true, createdAt));
        assertThat(response.getCreatedAt()).isEqualTo("2026-05-26T04:15:30Z");
    }

    @Test
    @DisplayName("created_at: null이면 null(NPE 없음)")
    void createdAt_null() {
        assertThat(AccountResponse.from(account("12345678901234", "tok", true, null)).getCreatedAt())
                .isNull();
    }
}
