package com.gb.wallet.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.request.RegisterAccountRequest;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.repository.BankRepository;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.redis.DistributedLockHelper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 계좌 등록(register) 통합 테스트(@SpringBootTest + H2). 실제 트랜잭션 커밋을 거쳐
 * {@code mock_account_token}·{@code holder_name} 영속 / 첫 계좌 자동 주계좌 / 중복(ACCOUNT4004) /
 * 없는 은행(COMMON4001)을 검증한다(ChargeServiceIntegrationTest 패턴 복제).
 *
 * <p>분산락은 H2/Redis 미가동 환경에서 실제 경합을 모사할 수 없으므로 {@link DistributedLockHelper}를
 * 가벼운 스텁(@MockitoBean)으로 두고 항상 획득 성공을 반환한다 — register의 lock 래퍼 분기(획득/실패/해제)는
 * {@code BankAccountServiceTest}(단위)가 촘촘히 검증하므로, 여기선 self-proxy {@code @Transactional}이
 * 실제로 커밋하는 영속 동작에 집중한다. 동시성 직렬화의 실 DB 검증은 // TODO: Testcontainers(진짜 MySQL).
 *
 * <p>{@code @Transactional}을 붙이지 <b>않는다</b> — {@code registerAccountLocked}가 자기 트랜잭션을 열고
 * 커밋하는 실제 동작을 그대로 보기 위함이며, 공유 인메모리 H2 누수를 막으려 매 메서드 전·후 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class BankAccountRegisterIntegrationTest {

    @MockitoBean private BankClient bankClient;
    @MockitoBean private MemberClient memberClient;
    // 방식 B 보안 필터 체인(oauth2ResourceServer)이 요구하는 JwtDecoder를 가린다(실제 IdP 호출 차단 — 컨텍스트 로딩용).
    @MockitoBean private JwtDecoder jwtDecoder;
    // 테스트엔 Redis가 없다. RedissonClient를 가려 실제 연결을 막는다(컨텍스트 로딩용).
    @MockitoBean private RedissonClient redissonClient;
    // 분산락은 스텁으로 항상 획득 성공시켜 register 본문이 정상 진입·커밋되도록 한다.
    @MockitoBean private DistributedLockHelper distributedLockHelper;

    @Autowired private BankAccountService bankAccountService;
    @Autowired private BankRepository bankRepository;
    @Autowired private BankAccountRepository bankAccountRepository;

    private static final String USER = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        clearAll();
        bankRepository.save(Bank.builder()
                .code("004").name("KB국민은행").country("KR").isDomestic(true).isActive(true).build());
        bankRepository.save(Bank.builder()
                .code("088").name("신한은행").country("KR").isDomestic(true).isActive(true).build());

        // 분산락 항상 획득 성공(본인 보유) → register 본문이 정상 진입하고 finally의 unlock도 안전하게 통과.
        RLock lock = mock(RLock.class);
        given(lock.isHeldByCurrentThread()).willReturn(true);
        given(distributedLockHelper.tryLock(anyString())).willReturn(lock);

        // WACC-05: register는 예금주명을 은행 inquiry 권위 값으로 채운다. Mock 은행은 "홍길동"을 돌려준다.
        given(bankClient.inquiry(anyString(), anyString())).willReturn(new AccountHolder("홍길동"));

        // charge-3: register는 클라 토큰을 신뢰하지 않고 은행 verify를 재호출해 서버 발급 토큰을 저장한다.
        given(bankClient.verify(anyString(), anyString(), anyString()))
                .willReturn(new AccountToken("tok-server"));
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    /** 공유 인메모리 H2 누수 차단(@AfterEach는 실패 시에도 실행됨). 계좌 → 은행 순으로 정리. */
    private void clearAll() {
        bankAccountRepository.deleteAll();
        bankRepository.deleteAll();
    }

    @Test
    @DisplayName("첫 계좌 등록: token·holder_name이 DB에 영속되고 isPrimary=true로 커밋된다")
    void register_첫계좌_영속_및_주계좌() {
        AccountResponse response = bankAccountService.registerAccount(
                USER, registerRequest("004", "1234567890", "tok-001", "홍길동"));

        // 응답 확인
        assertThat(response.getBankCode()).isEqualTo("004");
        assertThat(response.getIsPrimary()).isTrue();
        assertThat(response.getIsVerified()).isTrue();

        // 실제 DB 영속 확인(커밋된 상태를 별도 조회).
        List<BankAccount> persisted = bankAccountRepository.findAll();
        assertThat(persisted).hasSize(1);
        BankAccount saved = persisted.get(0);
        assertThat(saved.getUserPublicId()).isEqualTo(USER);
        assertThat(saved.getAccountNumber()).isEqualTo("1234567890");
        assertThat(saved.getMockAccountToken())
                .as("charge-3: 클라 토큰(tok-001)이 아니라 서버가 verify 재호출로 발급받은 토큰이 DB에 저장")
                .isEqualTo("tok-server");
        assertThat(saved.getHolderName()).as("예금주명이 DB에 저장").isEqualTo("홍길동");
        assertThat(saved.isPrimary()).as("첫 계좌 자동 주계좌").isTrue();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getPublicId()).isNotBlank();
        assertThat(saved.getCreatedAt()).as("JPA Auditing이 created_at을 채움").isNotNull();
    }

    @Test
    @DisplayName("둘째 계좌 등록: 첫 계좌가 있으면 isPrimary=false로 커밋(다중 주계좌 방지)")
    void register_둘째계좌_isPrimary_false() {
        bankAccountService.registerAccount(USER, registerRequest("004", "1111111111", "tok-1", "홍길동"));
        AccountResponse second = bankAccountService.registerAccount(
                USER, registerRequest("088", "2222222222", "tok-2", "홍길동"));

        assertThat(second.getIsPrimary()).isFalse();

        // 주계좌는 정확히 1건(첫 계좌)만 유지된다.
        long primaryCount = bankAccountRepository.findAll().stream()
                .filter(BankAccount::isPrimary).count();
        assertThat(primaryCount).as("활성 주계좌는 1건뿐").isEqualTo(1L);
    }

    @Test
    @DisplayName("중복 등록: 같은 (bank_code, account_number) 활성 계좌가 있으면 ACCOUNT4004(existsBy 실증)")
    void register_중복_ACCOUNT4004() {
        bankAccountService.registerAccount(USER, registerRequest("004", "1234567890", "tok-1", "홍길동"));

        assertThatThrownBy(() -> bankAccountService.registerAccount(
                USER, registerRequest("004", "1234567890", "tok-2", "홍길동")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);

        // 둘째 등록은 커밋되지 않아 1건만 남는다.
        assertThat(bankAccountRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("없는 bank_code: COMMON4001, 아무것도 저장되지 않는다")
    void register_없는_bankCode_COMMON4001() {
        assertThatThrownBy(() -> bankAccountService.registerAccount(
                USER, registerRequest("999", "1234567890", "tok-1", "홍길동")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);

        assertThat(bankAccountRepository.findAll()).isEmpty();
    }

    private RegisterAccountRequest registerRequest(String bankCode, String accountNumber,
                                                   String accountToken, String holderName) {
        RegisterAccountRequest r = new RegisterAccountRequest();
        ReflectionTestUtils.setField(r, "bankCode", bankCode);
        ReflectionTestUtils.setField(r, "accountNumber", accountNumber);
        ReflectionTestUtils.setField(r, "accountToken", accountToken);
        ReflectionTestUtils.setField(r, "holderName", holderName);
        return r;
    }
}
