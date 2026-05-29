package com.gb.wallet.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.request.RegisterAccountRequest;
import com.gb.wallet.domain.account.dto.request.VerifyAccountRequest;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.repository.BankRepository;
import com.gb.wallet.domain.account.service.impl.BankAccountServiceImpl;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link BankAccountServiceImpl}의 verifyAccount/registerAccount 단위 테스트.
 * DB·Spring 컨텍스트 없이 Mockito로만. Repository/BankClient 응답을 stub해 조합·예외 로직만 검증.
 */
@ExtendWith(MockitoExtension.class)
class BankAccountServiceTest {

    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private BankRepository bankRepository;
    @Mock private BankClient bankClient;
    @InjectMocks private BankAccountServiceImpl service;

    private static final String USER_PUBLIC_ID = "user-uuid";

    // --- verifyAccount ---

    @Test
    @DisplayName("verifyAccount 정상: BankClient.verify가 반환한 토큰을 응답으로 매핑하고 DB는 만지지 않는다")
    void verifyAccount_정상() {
        VerifyAccountRequest request = verifyRequest("004", "1234567890", "홍길동");
        given(bankClient.verify("004", "1234567890", "홍길동"))
                .willReturn(new AccountToken("tok-abcdef"));

        VerifyAccountResponse response = service.verifyAccount(request);

        assertThat(response.getAccountToken()).isEqualTo("tok-abcdef");
        verifyNoInteractions(bankAccountRepository, bankRepository);
    }

    @Test
    @DisplayName("verifyAccount: BankClient가 던진 BusinessException은 그대로 전파(서비스가 try/catch 안 함)")
    void verifyAccount_bankClient_예외_전파() {
        VerifyAccountRequest request = verifyRequest("004", "1234567890", "임꺽정");
        willThrow(new BusinessException(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED))
                .given(bankClient).verify("004", "1234567890", "임꺽정");

        assertThatThrownBy(() -> service.verifyAccount(request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED);

        verifyNoInteractions(bankAccountRepository, bankRepository);
    }

    // --- registerAccount ---

    @Test
    @DisplayName("registerAccount: 사용자의 첫 활성 계좌면 자동으로 isPrimary=true로 저장")
    void registerAccount_첫_계좌면_주계좌_자동() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        Bank bank = bank("004", "KB국민은행");

        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "004", "1234567890")).willReturn(false);
        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank));
        given(bankAccountRepository.countByUserPublicIdAndIsActiveTrue(USER_PUBLIC_ID))
                .willReturn(0L);
        given(bankAccountRepository.save(any(BankAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = service.registerAccount(USER_PUBLIC_ID, request);

        ArgumentCaptor<BankAccount> captor = ArgumentCaptor.forClass(BankAccount.class);
        verify(bankAccountRepository).save(captor.capture());
        BankAccount saved = captor.getValue();
        assertThat(saved.getUserPublicId()).isEqualTo(USER_PUBLIC_ID);
        assertThat(saved.getAccountNumber()).isEqualTo("1234567890");
        assertThat(saved.getMockAccountToken()).isEqualTo("tok-abc");
        assertThat(saved.isPrimary()).as("첫 계좌면 자동 true").isTrue();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.isVirtual()).isFalse();
        assertThat(saved.getPublicId()).isNotBlank();
        assertThat(saved.getBank()).isEqualTo(bank);

        assertThat(response.getBankCode()).isEqualTo("004");
        assertThat(response.getBankName()).isEqualTo("KB국민은행");
        assertThat(response.getIsPrimary()).isTrue();
        assertThat(response.getIsVerified()).isTrue();
    }

    @Test
    @DisplayName("registerAccount: 첫 계좌가 아니면 항상 isPrimary=false (주 계좌 변경은 PATCH /primary 책임)")
    void registerAccount_첫_계좌_아니면_isPrimary_false() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");

        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "004", "1234567890")).willReturn(false);
        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank("004", "KB국민은행")));
        given(bankAccountRepository.countByUserPublicIdAndIsActiveTrue(USER_PUBLIC_ID))
                .willReturn(2L);
        given(bankAccountRepository.save(any(BankAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.registerAccount(USER_PUBLIC_ID, request);

        ArgumentCaptor<BankAccount> captor = ArgumentCaptor.forClass(BankAccount.class);
        verify(bankAccountRepository).save(captor.capture());
        assertThat(captor.getValue().isPrimary())
                .as("등록 흐름에서 다중 주 계좌가 만들어지면 안 됨")
                .isFalse();
    }

    @Test
    @DisplayName("registerAccount: 중복 등록(activeTrue 존재)이면 ACCOUNT4004, save 호출되지 않음")
    void registerAccount_중복_등록() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");

        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "004", "1234567890")).willReturn(true);

        assertThatThrownBy(() -> service.registerAccount(USER_PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);

        verify(bankAccountRepository, never()).save(any());
        verifyNoInteractions(bankClient);
    }

    @Test
    @DisplayName("registerAccount: 알 수 없는 bank_code면 COMMON4001, save 호출되지 않음")
    void registerAccount_없는_bank_code() {
        RegisterAccountRequest request = registerRequest("999", "1234567890", "tok-abc");

        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "999", "1234567890")).willReturn(false);
        given(bankRepository.findByCode("999")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerAccount(USER_PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verify(bankAccountRepository, never()).save(any());
    }

    // --- helpers ---

    private VerifyAccountRequest verifyRequest(String bankCode, String accountNumber, String holderName) {
        VerifyAccountRequest r = new VerifyAccountRequest();
        ReflectionTestUtils.setField(r, "bankCode", bankCode);
        ReflectionTestUtils.setField(r, "accountNumber", accountNumber);
        ReflectionTestUtils.setField(r, "holderName", holderName);
        return r;
    }

    private RegisterAccountRequest registerRequest(String bankCode, String accountNumber,
                                                   String accountToken) {
        RegisterAccountRequest r = new RegisterAccountRequest();
        ReflectionTestUtils.setField(r, "bankCode", bankCode);
        ReflectionTestUtils.setField(r, "accountNumber", accountNumber);
        ReflectionTestUtils.setField(r, "accountToken", accountToken);
        return r;
    }

    private Bank bank(String code, String name) {
        return Bank.builder()
                .code(code)
                .name(name)
                .country("KR")
                .isDomestic(true)
                .isActive(true)
                .build();
    }
}