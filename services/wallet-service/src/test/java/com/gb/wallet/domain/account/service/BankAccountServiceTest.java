package com.gb.wallet.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.gb.wallet.global.config.VerifyRateLimitProperties;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.redis.DistributedLockHelper;
import com.gb.wallet.global.redis.RateLimitHelper;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link BankAccountServiceImpl}의 verify/register 단위 테스트. DB·Spring 컨텍스트 없이 Mockito로만.
 *
 * <p>{@code registerAccountLocked}(실제 등록 본문)는 self-proxy 없이 직접 호출해 비즈니스 로직만 본다.
 * {@code registerAccount}(분산락 래퍼)는 {@code @Mock BankAccountService self}를 통해 락 획득/실패 분기와
 * 위임만 검증한다(ChargeServiceTest의 charge/doCharge 분리와 동일 구조).
 */
@ExtendWith(MockitoExtension.class)
class BankAccountServiceTest {

    @Mock private BankAccountRepository bankAccountRepository;
    @Mock private BankRepository bankRepository;
    @Mock private BankClient bankClient;
    @Mock private RateLimitHelper rateLimitHelper;
    @Mock private DistributedLockHelper distributedLockHelper;
    @Mock private BankAccountService self;
    @InjectMocks private BankAccountServiceImpl service;

    private static final String USER_PUBLIC_ID = "user-uuid";
    private static final String IP = "127.0.0.1";
    private static final String VERIFY_KEY = "ratelimit:account-verify:" + IP;
    private static final String REGISTER_LOCK_KEY = "lock:account-register:" + USER_PUBLIC_ID;

    @BeforeEach
    void injectFields() {
        // @RequiredArgsConstructor 생성자 주입을 쓰면 @InjectMocks는 비-final 필드(self) 추가 주입을 하지
        // 않아 self가 null로 남는다 → 래퍼(registerAccount)의 self-proxy 위임 검증을 위해 직접 박는다.
        ReflectionTestUtils.setField(service, "self", self);
        // verifyRateLimitProperties는 대응 @Mock이 없어 생성자 주입 시 null → 한도/윈도 조회에서 NPE.
        // 실제 객체로 기본값(60s/10회)을 박아 기존 동작을 유지한다.
        ReflectionTestUtils.setField(service, "verifyRateLimitProperties",
                new VerifyRateLimitProperties(60, 10));
    }

    // --- verifyAccount (rate-limit) ---

    @Test
    @DisplayName("verifyAccount 정상: rate-limit 통과 시 BankClient 토큰을 응답으로 매핑하고 DB는 만지지 않는다")
    void verifyAccount_정상() {
        VerifyAccountRequest request = verifyRequest("004", "1234567890", "홍길동");
        given(rateLimitHelper.tryAcquire(eq(VERIFY_KEY), anyLong(), any(Duration.class)))
                .willReturn(true);
        given(bankClient.verify("004", "1234567890", "홍길동"))
                .willReturn(new AccountToken("tok-abcdef"));

        VerifyAccountResponse response = service.verifyAccount(request, IP);

        assertThat(response.getAccountToken()).isEqualTo("tok-abcdef");
        verifyNoInteractions(bankAccountRepository, bankRepository);
    }

    @Test
    @DisplayName("verifyAccount: IP 기반 키(ratelimit:account-verify:{ip})로 카운터를 센다")
    void verifyAccount_IP기반_키() {
        VerifyAccountRequest request = verifyRequest("004", "1234567890", "홍길동");
        given(rateLimitHelper.tryAcquire(any(), anyLong(), any(Duration.class))).willReturn(true);
        given(bankClient.verify(any(), any(), any())).willReturn(new AccountToken("tok"));

        service.verifyAccount(request, IP);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> limitCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Duration> windowCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(rateLimitHelper).tryAcquire(keyCaptor.capture(), limitCaptor.capture(), windowCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo(VERIFY_KEY);
        assertThat(limitCaptor.getValue()).isEqualTo(10L);            // 설정 기본 임계값
        assertThat(windowCaptor.getValue()).isEqualTo(Duration.ofSeconds(60)); // 설정 기본 윈도
    }

    @Test
    @DisplayName("verifyAccount: rate-limit 초과면 ACCOUNT4005, 외부 은행 호출하지 않는다")
    void verifyAccount_rate_limit_초과_ACCOUNT4005() {
        VerifyAccountRequest request = verifyRequest("004", "1234567890", "홍길동");
        given(rateLimitHelper.tryAcquire(eq(VERIFY_KEY), anyLong(), any(Duration.class)))
                .willReturn(false);

        assertThatThrownBy(() -> service.verifyAccount(request, IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.VERIFICATION_RATE_LIMITED);

        verifyNoInteractions(bankClient, bankAccountRepository, bankRepository);
    }

    @Test
    @DisplayName("verifyAccount: BankClient가 던진 BusinessException은 그대로 전파(서비스가 try/catch 안 함)")
    void verifyAccount_bankClient_예외_전파() {
        VerifyAccountRequest request = verifyRequest("004", "1234567890", "임꺽정");
        given(rateLimitHelper.tryAcquire(eq(VERIFY_KEY), anyLong(), any(Duration.class)))
                .willReturn(true);
        willThrow(new BusinessException(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED))
                .given(bankClient).verify("004", "1234567890", "임꺽정");

        assertThatThrownBy(() -> service.verifyAccount(request, IP))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED);

        verifyNoInteractions(bankAccountRepository, bankRepository);
    }

    // --- registerAccount (분산락 래퍼) ---

    @Test
    @DisplayName("registerAccount: 락 획득 성공 시 self.registerAccountLocked에 위임하고 락을 해제한다")
    void registerAccount_락획득_위임_후_해제() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        AccountResponse expected = stubAccountResponse();
        RLock lock = lock();
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(lock);
        given(self.registerAccountLocked(USER_PUBLIC_ID, request)).willReturn(expected);

        AccountResponse result = service.registerAccount(USER_PUBLIC_ID, request);

        assertThat(result).isSameAs(expected);
        verify(self).registerAccountLocked(USER_PUBLIC_ID, request);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("registerAccount: 락 획득 실패(tryLock=null)면 COMMON5031(503), 등록 본문 미진입")
    void registerAccount_락실패_COMMON5031() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(null);

        assertThatThrownBy(() -> service.registerAccount(USER_PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        verify(self, never()).registerAccountLocked(any(), any());
        verifyNoInteractions(bankAccountRepository, bankRepository);
    }

    // --- registerAccountLocked (등록 본문) ---

    @Test
    @DisplayName("registerAccountLocked: 사용자의 첫 활성 계좌면 자동으로 isPrimary=true로 저장")
    void registerAccountLocked_첫_계좌면_주계좌_자동() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        Bank bank = bank("004", "KB국민은행");

        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "004", "1234567890")).willReturn(false);
        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank));
        given(bankAccountRepository.countByUserPublicIdAndIsActiveTrue(USER_PUBLIC_ID))
                .willReturn(0L);
        given(bankAccountRepository.save(any(BankAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = service.registerAccountLocked(USER_PUBLIC_ID, request);

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
    @DisplayName("registerAccountLocked: 첫 계좌가 아니면 항상 isPrimary=false (주 계좌 변경은 PATCH /primary 책임)")
    void registerAccountLocked_첫_계좌_아니면_isPrimary_false() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");

        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "004", "1234567890")).willReturn(false);
        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank("004", "KB국민은행")));
        given(bankAccountRepository.countByUserPublicIdAndIsActiveTrue(USER_PUBLIC_ID))
                .willReturn(2L);
        given(bankAccountRepository.save(any(BankAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.registerAccountLocked(USER_PUBLIC_ID, request);

        ArgumentCaptor<BankAccount> captor = ArgumentCaptor.forClass(BankAccount.class);
        verify(bankAccountRepository).save(captor.capture());
        assertThat(captor.getValue().isPrimary())
                .as("등록 흐름에서 다중 주 계좌가 만들어지면 안 됨")
                .isFalse();
    }

    @Test
    @DisplayName("registerAccountLocked: 중복 등록(activeTrue 존재)이면 ACCOUNT4004, save 호출되지 않음")
    void registerAccountLocked_중복_등록() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");

        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "004", "1234567890")).willReturn(true);

        assertThatThrownBy(() -> service.registerAccountLocked(USER_PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);

        verify(bankAccountRepository, never()).save(any());
        verifyNoInteractions(bankClient);
    }

    @Test
    @DisplayName("registerAccountLocked: 알 수 없는 bank_code면 COMMON4001, save 호출되지 않음")
    void registerAccountLocked_없는_bank_code() {
        RegisterAccountRequest request = registerRequest("999", "1234567890", "tok-abc");

        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "999", "1234567890")).willReturn(false);
        given(bankRepository.findByCode("999")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerAccountLocked(USER_PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        verify(bankAccountRepository, never()).save(any());
    }

    // --- changePrimary (분산락 래퍼) ---

    @Test
    @DisplayName("changePrimary: 락 획득 성공 시 self.changePrimaryLocked에 위임하고 락을 해제한다")
    void changePrimary_락획득_위임_후_해제() {
        AccountResponse expected = stubAccountResponse();
        RLock lock = lock();
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(lock);
        given(self.changePrimaryLocked(USER_PUBLIC_ID, "acct-uuid")).willReturn(expected);

        AccountResponse result = service.changePrimary(USER_PUBLIC_ID, "acct-uuid");

        assertThat(result).isSameAs(expected);
        verify(self).changePrimaryLocked(USER_PUBLIC_ID, "acct-uuid");
        verify(lock).unlock();
    }

    @Test
    @DisplayName("changePrimary: 락 획득 실패(tryLock=null)면 COMMON5031, 변경 본문 미진입")
    void changePrimary_락실패_COMMON5031() {
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(null);

        assertThatThrownBy(() -> service.changePrimary(USER_PUBLIC_ID, "acct-uuid"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        verify(self, never()).changePrimaryLocked(any(), any());
        verifyNoInteractions(bankAccountRepository);
    }

    // --- changePrimaryLocked (변경 본문) ---

    @Test
    @DisplayName("changePrimaryLocked: 대상을 주계좌로 승격하고 기존 주계좌를 해제한다")
    void changePrimaryLocked_승격_기존해제() {
        BankAccount target = bankAccount("acct-target", false, true);
        BankAccount existingPrimary = bankAccount("acct-old-primary", true, true);
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue("acct-target", USER_PUBLIC_ID))
                .willReturn(Optional.of(target));
        given(bankAccountRepository.findByUserPublicIdAndIsPrimaryTrueAndIsActiveTrue(USER_PUBLIC_ID))
                .willReturn(Optional.of(existingPrimary));

        AccountResponse response = service.changePrimaryLocked(USER_PUBLIC_ID, "acct-target");

        assertThat(target.isPrimary()).as("대상이 주계좌로 승격").isTrue();
        assertThat(existingPrimary.isPrimary()).as("기존 주계좌는 해제").isFalse();
        assertThat(response.getAccountPublicId()).isEqualTo("acct-target");
        assertThat(response.getIsPrimary()).isTrue();
    }

    @Test
    @DisplayName("changePrimaryLocked: 대상이 없으면 ACCOUNT4001, 해제 finder 미호출")
    void changePrimaryLocked_미존재_ACCOUNT4001() {
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue("nope", USER_PUBLIC_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePrimaryLocked(USER_PUBLIC_ID, "nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);

        verify(bankAccountRepository, never())
                .findByUserPublicIdAndIsPrimaryTrueAndIsActiveTrue(any());
    }

    @Test
    @DisplayName("changePrimaryLocked: 이미 주계좌면 멱등 성공 — 기존 주계좌 해제 finder를 타지 않는다")
    void changePrimaryLocked_이미_주계좌_멱등() {
        BankAccount target = bankAccount("acct-target", true, true);
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue("acct-target", USER_PUBLIC_ID))
                .willReturn(Optional.of(target));

        AccountResponse response = service.changePrimaryLocked(USER_PUBLIC_ID, "acct-target");

        assertThat(response.getIsPrimary()).isTrue();
        assertThat(response.getAccountPublicId()).isEqualTo("acct-target");
        verify(bankAccountRepository, never())
                .findByUserPublicIdAndIsPrimaryTrueAndIsActiveTrue(any());
    }

    // --- deleteAccount (분산락 래퍼) ---

    @Test
    @DisplayName("deleteAccount: 락 획득 성공 시 self.deleteAccountLocked에 위임하고 락을 해제한다")
    void deleteAccount_락획득_위임_후_해제() {
        RLock lock = lock();
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(lock);

        service.deleteAccount(USER_PUBLIC_ID, "acct-uuid");

        verify(self).deleteAccountLocked(USER_PUBLIC_ID, "acct-uuid");
        verify(lock).unlock();
    }

    @Test
    @DisplayName("deleteAccount: 락 획득 실패면 COMMON5031, 삭제 본문 미진입")
    void deleteAccount_락실패_COMMON5031() {
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(null);

        assertThatThrownBy(() -> service.deleteAccount(USER_PUBLIC_ID, "acct-uuid"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        verify(self, never()).deleteAccountLocked(any(), any());
        verifyNoInteractions(bankAccountRepository);
    }

    // --- deleteAccountLocked (삭제 본문) ---

    @Test
    @DisplayName("deleteAccountLocked: 비주계좌 삭제는 soft-delete만, 승격 후보 finder를 타지 않는다")
    void deleteAccountLocked_비주계좌_softdelete() {
        BankAccount target = bankAccount("acct-target", false, true);
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue("acct-target", USER_PUBLIC_ID))
                .willReturn(Optional.of(target));

        service.deleteAccountLocked(USER_PUBLIC_ID, "acct-target");

        assertThat(target.isActive()).as("soft-delete").isFalse();
        verify(bankAccountRepository, never())
                .findFirstByUserPublicIdAndIsActiveTrueAndIdNotOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("deleteAccountLocked: 주계좌 삭제 시 남은 활성 계좌 1건을 자동 주계좌 승격")
    void deleteAccountLocked_주계좌_삭제_자동승격() {
        BankAccount target = bankAccount("acct-primary", true, true);
        BankAccount candidate = bankAccount("acct-candidate", false, true);
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue("acct-primary", USER_PUBLIC_ID))
                .willReturn(Optional.of(target));
        given(bankAccountRepository.findFirstByUserPublicIdAndIsActiveTrueAndIdNotOrderByCreatedAtDesc(
                eq(USER_PUBLIC_ID), any())).willReturn(Optional.of(candidate));

        service.deleteAccountLocked(USER_PUBLIC_ID, "acct-primary");

        assertThat(target.isActive()).as("주계좌 soft-delete").isFalse();
        assertThat(candidate.isPrimary()).as("남은 계좌 자동 승격").isTrue();
    }

    @Test
    @DisplayName("deleteAccountLocked: 주계좌가 마지막 1개면 승격 후보 없음 → 주계좌 없는 상태로 종료")
    void deleteAccountLocked_마지막계좌_승격없음() {
        BankAccount target = bankAccount("acct-primary", true, true);
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue("acct-primary", USER_PUBLIC_ID))
                .willReturn(Optional.of(target));
        given(bankAccountRepository.findFirstByUserPublicIdAndIsActiveTrueAndIdNotOrderByCreatedAtDesc(
                eq(USER_PUBLIC_ID), any())).willReturn(Optional.empty());

        service.deleteAccountLocked(USER_PUBLIC_ID, "acct-primary");

        assertThat(target.isActive()).as("주계좌 soft-delete").isFalse();
        // 승격 후보가 없으면 예외 없이 종료한다(주 계좌 없는 상태 허용).
    }

    @Test
    @DisplayName("deleteAccountLocked: 대상이 없으면 ACCOUNT4001")
    void deleteAccountLocked_미존재_ACCOUNT4001() {
        given(bankAccountRepository.findByPublicIdAndUserPublicIdAndIsActiveTrue("nope", USER_PUBLIC_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAccountLocked(USER_PUBLIC_ID, "nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }

    // --- helpers ---

    private RLock lock() {
        RLock lock = org.mockito.Mockito.mock(RLock.class);
        given(lock.isHeldByCurrentThread()).willReturn(true);
        return lock;
    }

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

    /** 주계좌 변경/삭제 테스트용 활성 계좌 엔티티. id는 미영속(null)이며 created_at도 null이다. */
    private BankAccount bankAccount(String publicId, boolean isPrimary, boolean isActive) {
        return BankAccount.builder()
                .publicId(publicId)
                .userPublicId(USER_PUBLIC_ID)
                .bank(bank("004", "KB국민은행"))
                .accountNumber("1234567890")
                .mockAccountToken("tok")
                .isVirtual(false)
                .isPrimary(isPrimary)
                .isActive(isActive)
                .build();
    }

    private AccountResponse stubAccountResponse() {
        return AccountResponse.builder()
                .accountPublicId("acct-uuid")
                .bankCode("004")
                .bankName("KB국민은행")
                .accountNumberMasked("123*****890")
                .isPrimary(true)
                .isVirtual(false)
                .isVerified(true)
                .createdAt("2026-05-29T10:00:00Z")
                .build();
    }
}