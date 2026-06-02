package com.gb.wallet.domain.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gb.wallet.domain.transaction.entity.RemittanceAttempt;
import com.gb.wallet.domain.transaction.repository.RemittanceAttemptRepository;
import com.gb.wallet.domain.transaction.service.impl.RemittanceAttemptWriterImpl;
import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link RemittanceAttemptWriterImpl} 단위 테스트. "흔적 기록(record-once)"의 세 경로를 검증한다 —
 * 이미 흔적이 있으면 no-op / 없으면 INSERT / 동시 race(UNIQUE 위반)는 흡수(예외 비전파).
 *
 * <p>{@link com.gb.wallet.domain.wallet.service.WalletBalanceWriterTest}와 동일한 3 케이스 골격을 따른다.
 */
@ExtendWith(MockitoExtension.class)
class RemittanceAttemptWriterTest {

    @Mock private RemittanceAttemptRepository remittanceAttemptRepository;
    @InjectMocks private RemittanceAttemptWriterImpl writer;

    private static final String KEY = "idem-key-001";
    private static final String USER = "user-public-id-1";
    private static final Long BANK_ACCOUNT_ID = 10L;
    private static final BigDecimal AMOUNT = new BigDecimal("10000.0000");
    private static final CurrencyType CURRENCY = CurrencyType.KRW;

    @Test
    @DisplayName("이미 흔적이 있으면 no-op — INSERT하지 않는다")
    void 이미있으면_noop() {
        given(remittanceAttemptRepository.existsByIdempotencyKey(KEY)).willReturn(true);

        writer.record(KEY, USER, BANK_ACCOUNT_ID, AMOUNT, CURRENCY);

        verify(remittanceAttemptRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("흔적이 없으면 INSERT한다")
    void 없으면_INSERT() {
        given(remittanceAttemptRepository.existsByIdempotencyKey(KEY)).willReturn(false);

        writer.record(KEY, USER, BANK_ACCOUNT_ID, AMOUNT, CURRENCY);

        ArgumentCaptor<RemittanceAttempt> captor = ArgumentCaptor.forClass(RemittanceAttempt.class);
        verify(remittanceAttemptRepository).saveAndFlush(captor.capture());
        RemittanceAttempt saved = captor.getValue();
        assertThat(saved.getIdempotencyKey()).isEqualTo(KEY);
        assertThat(saved.getUserPublicId()).isEqualTo(USER);
        assertThat(saved.getBankAccountId()).isEqualTo(BANK_ACCOUNT_ID);
        assertThat(saved.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(saved.getCurrencyCode()).isEqualTo(CURRENCY);
        assertThat(saved.getAttemptedAt()).isNotNull();
    }

    @Test
    @DisplayName("사전 체크 직후 동시 INSERT로 UNIQUE 위반이 나도 흡수한다(메인 트랜잭션에 예외 비전파)")
    void 동시생성_위반_흡수() {
        // 사전 체크는 false였는데, saveAndFlush 시점에 다른 트랜잭션이 먼저 INSERT한 잔여 race 시나리오.
        given(remittanceAttemptRepository.existsByIdempotencyKey(KEY)).willReturn(false);
        willThrow(new DataIntegrityViolationException("duplicate idempotency_key"))
                .given(remittanceAttemptRepository).saveAndFlush(any());

        // record는 REQUIRES_NEW로 호출 측 트랜잭션과 분리돼 있어 본 트랜잭션만 rollback되고
        // 호출자(메인 트랜잭션)로는 어떤 예외도 전파되지 않아야 한다 — UnexpectedRollbackException 포함.
        assertThatCode(() -> writer.record(KEY, USER, BANK_ACCOUNT_ID, AMOUNT, CURRENCY))
                .doesNotThrowAnyException();
    }
}
