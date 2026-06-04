package com.gb.wallet.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.account.service.impl.HolderServiceImpl;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.config.VerifyRateLimitProperties;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.redis.RateLimitHelper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link HolderServiceImpl}의 단위 테스트. {@link BankClient}·{@link RateLimitHelper}만 mock.
 * 본 서비스는 DB가 없으므로 Spring 컨텍스트도 띄우지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class HolderServiceTest {

    @Mock private BankClient bankClient;
    @Mock private RateLimitHelper rateLimitHelper;
    @InjectMocks private HolderServiceImpl holderService;

    private static final String USER = "user-public-id-1";

    @BeforeEach
    void setUp() {
        // record 프로퍼티는 @Mock 없이 실제 객체(기본 60s/10회)를 박는다(BankAccountServiceTest 동일 기법).
        ReflectionTestUtils.setField(holderService, "rateLimitProperties",
                new VerifyRateLimitProperties(60, 10));
        // 기본은 rate-limit 통과 — 초과 시나리오만 개별 테스트에서 false로 덮어쓴다.
        Mockito.lenient().when(rateLimitHelper.tryAcquire(anyString(), Mockito.anyLong(), any(Duration.class)))
                .thenReturn(true);
    }

    @Test
    @DisplayName("정상: BankClient가 반환한 예금주명을 응답 DTO로 매핑한다")
    void getAccountHolder_정상() {
        given(bankClient.inquiry("004", "12345678901234"))
                .willReturn(new AccountHolder("홍길동"));

        AccountHolderResponse response = holderService.getAccountHolder("004", "12345678901234", USER);

        assertThat(response.getAccountHolderName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("BankClient가 ACCOUNT4001 던지면 그대로 전파된다 (래핑/재해석 없음)")
    void getAccountHolder_계좌없음_전파() {
        willThrow(new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND))
                .given(bankClient).inquiry("004", "00000000000000");

        assertThatThrownBy(() -> holderService.getAccountHolder("004", "00000000000000", USER))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("WACC-03: rate-limit 초과(tryAcquire=false) → COMMON4291, 외부 inquiry 미호출(PII enumeration 차단)")
    void getAccountHolder_rateLimit_초과_COMMON4291() {
        given(rateLimitHelper.tryAcquire(anyString(), Mockito.anyLong(), any(Duration.class)))
                .willReturn(false);

        assertThatThrownBy(() -> holderService.getAccountHolder("004", "12345678901234", USER))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.TOO_MANY_REQUESTS);

        verifyNoInteractions(bankClient); // rate-limit에서 차단 → 외부 PII 조회 미진입
    }
}
