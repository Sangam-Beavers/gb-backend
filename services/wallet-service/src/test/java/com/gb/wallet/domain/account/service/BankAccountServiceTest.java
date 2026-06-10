package com.gb.wallet.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.request.ConfirmAccountRequest;
import com.gb.wallet.domain.account.dto.request.RegisterAccountRequest;
import com.gb.wallet.domain.account.dto.request.VerifyAccountRequest;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.ConfirmAccountResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.repository.BankRepository;
import com.gb.wallet.domain.account.service.impl.BankAccountServiceImpl;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.client.dto.VerifyInitResult;
import com.gb.wallet.global.config.VerifyRateLimitProperties;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.redis.DistributedLockHelper;
import com.gb.wallet.global.redis.RateLimitHelper;
import com.gb.wallet.global.redis.VerifySessionStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link BankAccountServiceImpl}의 verify/confirm/register 단위 테스트. DB·Spring 컨텍스트 없이 Mockito로만.
 *
 * <p>1원 소액이체 인증 흐름(커밋 c820740): verifyAccount(initVerify, 1원 입금) → confirmAccount(confirmVerify,
 * 코드 검증 → token Redis 저장) → registerAccount(세션 token 소비 → 등록). register 중 은행 verify 재호출은
 * 더 이상 없다 — 토큰 출처는 {@link VerifySessionStore}(서버 보관)다.
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
    @Mock private VerifySessionStore verifySessionStore;
    @Mock private BankAccountService self;
    // BE-3 임시 — Fix PR 시점엔 HEAD에 publishEvent 없음, 충돌 시 이 줄만 조정.
    // 워킹트리의 registerAccountLocked 성공 경로가 eventPublisher.publishEvent를 호출하므로 null NPE 방지용.
    // 이벤트 발행 검증(verify)은 BE-3에서 별도 추가한다 — 여기서는 금지.
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private BankAccountServiceImpl service;

    private static final String USER_PUBLIC_ID = "user-uuid";
    // WACC-02: verify rate-limit은 위조불가 userPublicId로 키잉한다(과거 IP 키잉에서 전환).
    private static final String VERIFY_KEY = "ratelimit:account-verify:" + USER_PUBLIC_ID;
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

    // --- getMyAccounts (목록 조회) ---

    @Test
    @DisplayName("getMyAccounts: 활성 계좌 목록을 AccountListResponse로 매핑(순서 보존), 다른 finder·client 미호출")
    void getMyAccounts_매핑_및_다른의존_미호출() {
        BankAccount first = bankAccount("acct-1", true, true);
        BankAccount second = bankAccount("acct-2", false, true);
        given(bankAccountRepository
                .findAllByUserPublicIdAndIsActiveTrueOrderByIsPrimaryDescCreatedAtDesc(USER_PUBLIC_ID))
                .willReturn(List.of(first, second));

        AccountListResponse response = service.getMyAccounts(USER_PUBLIC_ID);

        assertThat(response.getAccounts())
                .extracting(AccountResponse::getAccountPublicId)
                .containsExactly("acct-1", "acct-2");
        verify(bankAccountRepository)
                .findAllByUserPublicIdAndIsActiveTrueOrderByIsPrimaryDescCreatedAtDesc(USER_PUBLIC_ID);
        // 목록 조회는 다른 finder·외부 의존을 건드리지 않는다(불필요 호출 부재 검증).
        verifyNoInteractions(bankRepository, bankClient, rateLimitHelper, distributedLockHelper);
    }

    @Test
    @DisplayName("getMyAccounts: 활성 계좌가 없으면 빈 목록(200 + [])")
    void getMyAccounts_빈결과() {
        given(bankAccountRepository
                .findAllByUserPublicIdAndIsActiveTrueOrderByIsPrimaryDescCreatedAtDesc(USER_PUBLIC_ID))
                .willReturn(List.of());

        assertThat(service.getMyAccounts(USER_PUBLIC_ID).getAccounts()).isEmpty();
    }

    // --- verifyAccount (1단계: 1원 소액이체 요청, rate-limit) ---

    @Test
    @DisplayName("verifyAccount 정상: rate-limit 통과 시 initVerify 결과(pending/expires_at)를 응답으로 매핑하고 DB는 만지지 않는다")
    void verifyAccount_정상() {
        VerifyAccountRequest request = verifyRequest("004", "1234567890", "홍길동");
        given(rateLimitHelper.tryAcquire(eq(VERIFY_KEY), anyLong(), any(Duration.class)))
                .willReturn(true);
        given(bankClient.initVerify("004", "1234567890", "홍길동"))
                .willReturn(new VerifyInitResult(true, "2026-06-09T12:44:56Z"));

        VerifyAccountResponse response = service.verifyAccount(request, USER_PUBLIC_ID);

        assertThat(response.isPending()).isTrue();
        assertThat(response.getExpiresAt()).isEqualTo("2026-06-09T12:44:56Z");
        // 1단계는 token을 발급하지 않으므로 세션 저장도 없다(token은 confirm 2단계에서만 저장).
        verifyNoInteractions(bankAccountRepository, bankRepository, verifySessionStore);
    }

    @Test
    @DisplayName("verifyAccount: 사용자 기반 키(ratelimit:account-verify:{userPublicId})로 카운터를 센다(WACC-02)")
    void verifyAccount_사용자기반_키() {
        VerifyAccountRequest request = verifyRequest("004", "1234567890", "홍길동");
        given(rateLimitHelper.tryAcquire(any(), anyLong(), any(Duration.class))).willReturn(true);
        given(bankClient.initVerify(any(), any(), any()))
                .willReturn(new VerifyInitResult(true, "2026-06-09T12:44:56Z"));

        service.verifyAccount(request, USER_PUBLIC_ID);

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

        assertThatThrownBy(() -> service.verifyAccount(request, USER_PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.VERIFICATION_RATE_LIMITED);

        verifyNoInteractions(bankClient, bankAccountRepository, bankRepository, verifySessionStore);
    }

    @Test
    @DisplayName("verifyAccount: BankClient가 던진 BusinessException은 그대로 전파(서비스가 try/catch 안 함)")
    void verifyAccount_bankClient_예외_전파() {
        VerifyAccountRequest request = verifyRequest("004", "1234567890", "임꺽정");
        given(rateLimitHelper.tryAcquire(eq(VERIFY_KEY), anyLong(), any(Duration.class)))
                .willReturn(true);
        // 예금주 불일치 BANK4003 → BankErrorMapper가 ACCOUNT4002로 변환해 던진 상황.
        willThrow(new BusinessException(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED))
                .given(bankClient).initVerify("004", "1234567890", "임꺽정");

        assertThatThrownBy(() -> service.verifyAccount(request, USER_PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_VERIFICATION_FAILED);

        verifyNoInteractions(bankAccountRepository, bankRepository, verifySessionStore);
    }

    // --- confirmAccount (2단계: 인증번호 검증 → token 발급 → Redis 세션 저장) ---

    @Test
    @DisplayName("confirmAccount 정상: 은행이 발급한 token을 사용자·계좌 키로 Redis 세션에 저장하고 verified=true 응답(토큰-계좌 바인딩, charge-3 등가)")
    void confirmAccount_정상_세션저장() {
        // charge-3 등가 가드: 구 흐름은 register가 verify를 재호출해 토큰-계좌 바인딩을 보장했다.
        // 새 흐름에선 confirm이 발급받은 token을 (userPublicId, bankCode, accountNumber) 키로 저장하는
        // 지점이 그 바인딩이다 — 다른 사용자/계좌의 세션과 절대 섞이지 않는다.
        ConfirmAccountRequest request = confirmRequest("004", "1234567890", "2814");
        given(bankClient.confirmVerify("004", "1234567890", "2814"))
                .willReturn(new AccountToken("tok-server"));

        ConfirmAccountResponse response = service.confirmAccount(request, USER_PUBLIC_ID);

        assertThat(response.isVerified()).isTrue();
        verify(verifySessionStore).save(USER_PUBLIC_ID, "004", "1234567890", "tok-server");
        verifyNoInteractions(bankAccountRepository, bankRepository, rateLimitHelper, distributedLockHelper);
    }

    @Test
    @DisplayName("confirmAccount: 코드 불일치(BANK4005→ACCOUNT4008)면 전파하고 세션을 저장하지 않는다")
    void confirmAccount_코드불일치_ACCOUNT4008_세션저장없음() {
        ConfirmAccountRequest request = confirmRequest("004", "1234567890", "0000");
        willThrow(new BusinessException(AccountErrorCode.VERIFY_CODE_INVALID))
                .given(bankClient).confirmVerify("004", "1234567890", "0000");

        assertThatThrownBy(() -> service.confirmAccount(request, USER_PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.VERIFY_CODE_INVALID);

        // 검증 실패면 token이 없다 — 세션에 아무것도 저장되면 안 된다(fail-closed).
        verifyNoInteractions(verifySessionStore, bankAccountRepository, bankRepository);
    }

    @Test
    @DisplayName("confirmAccount: 인증 세션 없음/만료(BANK4006→ACCOUNT4009)면 전파하고 세션을 저장하지 않는다")
    void confirmAccount_세션만료_ACCOUNT4009() {
        ConfirmAccountRequest request = confirmRequest("004", "1234567890", "2814");
        willThrow(new BusinessException(AccountErrorCode.VERIFY_SESSION_NOT_FOUND))
                .given(bankClient).confirmVerify("004", "1234567890", "2814");

        assertThatThrownBy(() -> service.confirmAccount(request, USER_PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.VERIFY_SESSION_NOT_FOUND);

        verifyNoInteractions(verifySessionStore, bankAccountRepository, bankRepository);
    }

    @Test
    @DisplayName("confirmAccount: Redis 세션 저장 실패(COMMON5000)는 fail-closed로 그대로 전파(token 유실 상태로 성공 응답 금지)")
    void confirmAccount_세션저장실패_failClosed() {
        ConfirmAccountRequest request = confirmRequest("004", "1234567890", "2814");
        given(bankClient.confirmVerify("004", "1234567890", "2814"))
                .willReturn(new AccountToken("tok-server"));
        willThrow(new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR))
                .given(verifySessionStore).save(USER_PUBLIC_ID, "004", "1234567890", "tok-server");

        assertThatThrownBy(() -> service.confirmAccount(request, USER_PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    // --- registerAccount (분산락 래퍼) ---

    @Test
    @DisplayName("registerAccount: 락 획득 성공 시 findByCode·inquiry·세션 소비(락 밖) 후 self.registerAccountLocked에 위임하고 락을 해제한다")
    void registerAccount_락획득_위임_후_해제() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        Bank bank = bank("004", "KB국민은행");
        AccountResponse expected = stubAccountResponse();
        RLock lock = lock();
        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank));
        given(bankClient.inquiry("004", "1234567890")).willReturn(new AccountHolder("홍길동"));
        given(verifySessionStore.consume(USER_PUBLIC_ID, "004", "1234567890")).willReturn("tok-server");
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(lock);
        given(self.registerAccountLocked(eq(USER_PUBLIC_ID), eq(request), eq(bank), eq("홍길동"), eq("tok-server")))
                .willReturn(expected);

        AccountResponse result = service.registerAccount(USER_PUBLIC_ID, request);

        assertThat(result).isSameAs(expected);
        verify(self).registerAccountLocked(USER_PUBLIC_ID, request, bank, "홍길동", "tok-server");
        verify(lock).unlock();
    }

    @Test
    @DisplayName("F1(ACC1 회귀): inquiry(동기 HTTP)·세션 소비는 분산락 획득 '이전'에 호출된다 — 락 안에 외부호출이 없다")
    void registerAccount_inquiry_락_밖에서_먼저_호출_F1() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        Bank bank = bank("004", "KB국민은행");
        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank));
        given(bankClient.inquiry("004", "1234567890")).willReturn(new AccountHolder("홍길동"));
        given(verifySessionStore.consume(USER_PUBLIC_ID, "004", "1234567890")).willReturn("tok-server");
        RLock lock = lock();
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(lock);
        given(self.registerAccountLocked(eq(USER_PUBLIC_ID), eq(request), eq(bank), eq("홍길동"), eq("tok-server")))
                .willReturn(stubAccountResponse());

        service.registerAccount(USER_PUBLIC_ID, request);

        // 락 lease(5s) < bank read-timeout(10s)이라, 외부호출이 락 안에 있으면 lease 만료 창에 2번째 등록이
        // 끼어 다중 주계좌가 생긴다(ACC1). inquiry(외부 HTTP)·세션 소비(GETDEL, 구 흐름의 verify 재발급 대체)가
        // 모두 tryLock '이전'임을 호출 순서로 단언해 회귀를 막는다.
        // (실제 동시 race는 락이 stub이라 H2로 재현 불가 — 호출 순서 단언으로 갈음.)
        InOrder order = inOrder(bankRepository, bankClient, verifySessionStore, distributedLockHelper);
        order.verify(bankRepository).findByCode("004");
        order.verify(bankClient).inquiry("004", "1234567890");
        order.verify(verifySessionStore).consume(USER_PUBLIC_ID, "004", "1234567890");
        order.verify(distributedLockHelper).tryLock(REGISTER_LOCK_KEY);
    }

    @Test
    @DisplayName("registerAccount: 은행 예금주명이 컬럼 한도(100자) 초과면 COMMON5000, 세션 소비/락/등록 본문 미진입(ACCOUNT4004 오매핑 방지)")
    void registerAccount_예금주명_길이초과_COMMON5000() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank("004", "KB국민은행")));
        // 은행이 holder_name 컬럼 한도(VARCHAR(100))를 넘는 예금주명을 돌려준 경우.
        given(bankClient.inquiry("004", "1234567890")).willReturn(new AccountHolder("가".repeat(101)));

        assertThatThrownBy(() -> service.registerAccount(USER_PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);

        // 길이 선검사는 세션 소비(GETDEL)·락 획득 이전 → 셋 다 진입하지 않는다.
        // (소비 이전 차단이 중요 — 등록이 실패할 요청에 1회용 인증 세션을 낭비하지 않는다.)
        verify(verifySessionStore, never()).consume(any(), any(), any());
        verify(distributedLockHelper, never()).tryLock(REGISTER_LOCK_KEY);
        verify(self, never()).registerAccountLocked(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("registerAccount: 인증 세션 없음/만료(consume→ACCOUNT4009)면 전파, 락/등록 본문 미진입 — confirm을 먼저 완료해야 한다")
    void registerAccount_세션없음_ACCOUNT4009_등록차단() {
        // charge-3 등가 가드: 구 흐름은 "클라 토큰 무시 + verify 재발급"으로 무인증 등록을 막았다.
        // 새 흐름에선 confirm이 저장한 세션이 없으면 등록 자체가 차단되는 것이 그 방어선이다.
        RegisterAccountRequest request = registerRequest("004", "1234567890", "forged-token");
        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank("004", "KB국민은행")));
        given(bankClient.inquiry("004", "1234567890")).willReturn(new AccountHolder("홍길동"));
        willThrow(new BusinessException(AccountErrorCode.VERIFY_SESSION_NOT_FOUND))
                .given(verifySessionStore).consume(USER_PUBLIC_ID, "004", "1234567890");

        assertThatThrownBy(() -> service.registerAccount(USER_PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.VERIFY_SESSION_NOT_FOUND);

        // 클라가 request에 token을 실어 보내도(vestigial 필드) 세션 없이는 등록 불가 — 락·본문 미진입.
        verify(distributedLockHelper, never()).tryLock(REGISTER_LOCK_KEY);
        verify(self, never()).registerAccountLocked(any(), any(), any(), any(), any());
        verifyNoInteractions(bankAccountRepository);
    }

    @Test
    @DisplayName("registerAccount: 락 획득 실패(tryLock=null)면 COMMON5031(503), 등록 본문 미진입")
    void registerAccount_락실패_COMMON5031() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        // F1: findByCode·inquiry·세션 소비는 락 밖에서 먼저 끝난 뒤 tryLock에서 실패한다.
        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank("004", "KB국민은행")));
        given(bankClient.inquiry("004", "1234567890")).willReturn(new AccountHolder("홍길동"));
        given(verifySessionStore.consume(USER_PUBLIC_ID, "004", "1234567890")).willReturn("tok-server");
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(null);

        assertThatThrownBy(() -> service.registerAccount(USER_PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        // 락 획득 실패는 등록 본문(self.registerAccountLocked) 미진입 — bank_accounts는 손대지 않는다.
        verify(self, never()).registerAccountLocked(any(), any(), any(), any(), any());
        verifyNoInteractions(bankAccountRepository);
    }

    // --- registerAccountLocked (등록 본문) ---

    @Test
    @DisplayName("registerAccountLocked: 사용자의 첫 활성 계좌면 자동으로 isPrimary=true로 저장(critical section=DB only)")
    void registerAccountLocked_첫_계좌면_주계좌_자동() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        Bank bank = bank("004", "KB국민은행");

        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "004", "1234567890")).willReturn(false);
        given(bankAccountRepository.countByUserPublicIdAndIsActiveTrue(USER_PUBLIC_ID))
                .willReturn(0L);
        given(bankAccountRepository.saveAndFlush(any(BankAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // bank·holderName·accountToken은 락 밖(registerAccount)에서 확정돼 파라미터로 전달된다(F1·charge-3 등가).
        AccountResponse response = service.registerAccountLocked(USER_PUBLIC_ID, request, bank, "홍길동", "tok-server");

        ArgumentCaptor<BankAccount> captor = ArgumentCaptor.forClass(BankAccount.class);
        verify(bankAccountRepository).saveAndFlush(captor.capture());
        BankAccount saved = captor.getValue();
        assertThat(saved.getUserPublicId()).isEqualTo(USER_PUBLIC_ID);
        assertThat(saved.getAccountNumber()).isEqualTo("1234567890");
        assertThat(saved.getMockAccountToken())
                .as("charge-3 등가: 클라 토큰(tok-abc)이 아니라 confirm 세션에서 소비해 확정한 서버 토큰 저장")
                .isEqualTo("tok-server");
        assertThat(saved.getHolderName()).as("F1: 호출자가 넘긴 은행 권위 예금주명 저장").isEqualTo("홍길동");
        assertThat(saved.isPrimary()).as("첫 계좌면 자동 true").isTrue();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.isVirtual()).isFalse();
        assertThat(saved.getPublicId()).isNotBlank();
        assertThat(saved.getBank()).isEqualTo(bank);

        assertThat(response.getBankCode()).isEqualTo("004");
        assertThat(response.getBankName()).isEqualTo("KB국민은행");
        assertThat(response.getIsPrimary()).isTrue();
        assertThat(response.getIsVerified()).isTrue();

        // F1(ACC1 회귀 가드): 등록 본문 critical section엔 외부호출(inquiry)·은행조회(findByCode)·
        // 세션 소비(GETDEL)가 없어야 한다 — 모두 락 밖 registerAccount에서 끝난다.
        verifyNoInteractions(bankClient, bankRepository, verifySessionStore);
    }

    @Test
    @DisplayName("registerAccountLocked: 첫 계좌가 아니면 항상 isPrimary=false (주 계좌 변경은 PATCH /primary 책임)")
    void registerAccountLocked_첫_계좌_아니면_isPrimary_false() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        Bank bank = bank("004", "KB국민은행");

        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "004", "1234567890")).willReturn(false);
        given(bankAccountRepository.countByUserPublicIdAndIsActiveTrue(USER_PUBLIC_ID))
                .willReturn(2L);
        given(bankAccountRepository.saveAndFlush(any(BankAccount.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.registerAccountLocked(USER_PUBLIC_ID, request, bank, "홍길동", "tok-server");

        ArgumentCaptor<BankAccount> captor = ArgumentCaptor.forClass(BankAccount.class);
        verify(bankAccountRepository).saveAndFlush(captor.capture());
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

        assertThatThrownBy(() -> service.registerAccountLocked(
                USER_PUBLIC_ID, request, bank("004", "KB국민은행"), "홍길동", "tok-server"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);

        verify(bankAccountRepository, never()).saveAndFlush(any());
        // 등록 본문엔 외부호출·세션 소비가 없다(inquiry·consume은 락 밖 registerAccount에서 끝남).
        verifyNoInteractions(bankClient, verifySessionStore);
    }

    @Test
    @DisplayName("WACC-05: registerAccount는 클라 입력이 아니라 은행 inquiry 예금주명을 등록 본문에 넘긴다(위조 차단)")
    void registerAccount_holderName_은행권위명_전달() {
        // 클라가 register에 위조 예금주명을 보내도 무시하고 은행 inquiry 결과를 registerAccountLocked로 넘긴다.
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        ReflectionTestUtils.setField(request, "holderName", "위조된이름"); // 클라 입력 — 무시돼야 함
        Bank bank = bank("004", "KB국민은행");

        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank));
        given(bankClient.inquiry("004", "1234567890")).willReturn(new AccountHolder("진짜예금주"));
        given(verifySessionStore.consume(USER_PUBLIC_ID, "004", "1234567890")).willReturn("tok-server");
        RLock lock = lock();
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(lock);
        given(self.registerAccountLocked(eq(USER_PUBLIC_ID), eq(request), eq(bank), any(), any()))
                .willReturn(stubAccountResponse());

        service.registerAccount(USER_PUBLIC_ID, request);

        ArgumentCaptor<String> holderCaptor = ArgumentCaptor.forClass(String.class);
        verify(self).registerAccountLocked(eq(USER_PUBLIC_ID), eq(request), eq(bank), holderCaptor.capture(), any());
        assertThat(holderCaptor.getValue())
                .as("클라 입력(위조된이름) 무시, 은행 권위 예금주명 전달")
                .isEqualTo("진짜예금주");
    }

    @Test
    @DisplayName("charge-3 등가: registerAccount는 클라 토큰이 아니라 confirm이 Redis 세션에 저장한 서버 토큰을 소비해 등록 본문에 넘긴다(세션-토큰 바인딩)")
    void registerAccount_accountToken_세션_소비_전달() {
        // 구 흐름의 가드(charge-3)는 "register가 은행 verify를 재호출해 토큰을 재발급"하는 것이었다.
        // 새 1원 인증 흐름에선 register 중 verify 재호출이 없다(재confirm = 1원 재입금 비용). 등가 보장은:
        // ① 클라가 위조/타 계좌의 account_token을 보내도(vestigial 필드) 서버는 그 값을 절대 쓰지 않고
        // ② confirm이 (userPublicId, bankCode, accountNumber) 키로 저장한 세션 토큰을 GETDEL로 소비해
        //    등록 본문에 넘긴다 — 토큰-사용자-계좌 바인딩 보장.
        RegisterAccountRequest request = registerRequest("004", "1234567890", "forged-token");
        Bank bank = bank("004", "KB국민은행");

        given(bankRepository.findByCode("004")).willReturn(Optional.of(bank));
        given(bankClient.inquiry("004", "1234567890")).willReturn(new AccountHolder("홍길동"));
        given(verifySessionStore.consume(USER_PUBLIC_ID, "004", "1234567890")).willReturn("tok-server");
        RLock lock = lock();
        given(distributedLockHelper.tryLock(REGISTER_LOCK_KEY)).willReturn(lock);
        given(self.registerAccountLocked(eq(USER_PUBLIC_ID), eq(request), eq(bank), any(), any()))
                .willReturn(stubAccountResponse());

        service.registerAccount(USER_PUBLIC_ID, request);

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(self).registerAccountLocked(eq(USER_PUBLIC_ID), eq(request), eq(bank), any(), tokenCaptor.capture());
        assertThat(tokenCaptor.getValue())
                .as("클라 토큰(forged-token) 무시, confirm 세션에서 소비한 서버 토큰 전달")
                .isEqualTo("tok-server");
        // 세션 키는 요청 사용자·계좌에 바인딩된다 — 타인/타계좌 세션을 집어 쓸 수 없다.
        verify(verifySessionStore).consume(USER_PUBLIC_ID, "004", "1234567890");
        // register 중 은행 인증 API(initVerify/confirmVerify) 재호출은 없다 — inquiry(예금주 조회)만 허용.
        verify(bankClient, never()).initVerify(any(), any(), any());
        verify(bankClient, never()).confirmVerify(any(), any(), any());
    }

    @Test
    @DisplayName("WACC-06: saveAndFlush가 부분 UNIQUE 위반(DataIntegrityViolation)을 던지면 ACCOUNT4004로 매핑(락 우회 백스톱)")
    void registerAccountLocked_UNIQUE위반_백스톱_ACCOUNT4004() {
        RegisterAccountRequest request = registerRequest("004", "1234567890", "tok-abc");
        given(bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                USER_PUBLIC_ID, "004", "1234567890")).willReturn(false); // 선검사는 통과(락 만료/split-brain)
        given(bankAccountRepository.countByUserPublicIdAndIsActiveTrue(USER_PUBLIC_ID)).willReturn(0L);
        given(bankAccountRepository.saveAndFlush(any(BankAccount.class)))
                .willThrow(new DataIntegrityViolationException("uk_bank_accounts_user_bank_acct_active"));

        assertThatThrownBy(() -> service.registerAccountLocked(
                USER_PUBLIC_ID, request, bank("004", "KB국민은행"), "홍길동", "tok-server"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);
    }

    @Test
    @DisplayName("registerAccount: 알 수 없는 bank_code면 COMMON4001 — inquiry(은행 호출)·락 획득 전에 끊긴다(B안 보장)")
    void registerAccount_없는_bank_code() {
        RegisterAccountRequest request = registerRequest("999", "1234567890", "tok-abc");
        given(bankRepository.findByCode("999")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerAccount(USER_PUBLIC_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        // B안 보장: 잘못된 은행코드는 은행 inquiry·세션 소비·분산락 이전에 차단된다(불필요한 외부호출/락/세션 낭비 없음).
        verifyNoInteractions(bankClient, verifySessionStore, distributedLockHelper);
        verify(self, never()).registerAccountLocked(any(), any(), any(), any(), any());
        verify(bankAccountRepository, never()).saveAndFlush(any());
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

    private ConfirmAccountRequest confirmRequest(String bankCode, String accountNumber, String code) {
        ConfirmAccountRequest r = new ConfirmAccountRequest();
        ReflectionTestUtils.setField(r, "bankCode", bankCode);
        ReflectionTestUtils.setField(r, "accountNumber", accountNumber);
        ReflectionTestUtils.setField(r, "code", code);
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