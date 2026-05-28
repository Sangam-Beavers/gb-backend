package com.gb.wallet.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.account.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.account.service.impl.HolderServiceImpl;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.AccountHolder;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link HolderServiceImpl}의 단위 테스트. {@link BankClient}만 mock.
 * 본 서비스는 DB가 없으므로 Spring 컨텍스트도 띄우지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class HolderServiceTest {

    @Mock private BankClient bankClient;
    @InjectMocks private HolderServiceImpl holderService;

    @Test
    @DisplayName("정상: BankClient가 반환한 예금주명을 응답 DTO로 매핑한다")
    void getAccountHolder_정상() {
        given(bankClient.inquiry("004", "12345678901234"))
                .willReturn(new AccountHolder("홍길동"));

        AccountHolderResponse response = holderService.getAccountHolder("004", "12345678901234");

        assertThat(response.getAccountHolderName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("BankClient가 ACCOUNT4001 던지면 그대로 전파된다 (래핑/재해석 없음)")
    void getAccountHolder_계좌없음_전파() {
        willThrow(new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND))
                .given(bankClient).inquiry("004", "00000000000000");

        assertThatThrownBy(() -> holderService.getAccountHolder("004", "00000000000000"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AccountErrorCode.ACCOUNT_NOT_FOUND);
    }
}
