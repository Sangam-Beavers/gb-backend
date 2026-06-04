package com.gb.wallet.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gb.wallet.domain.account.entity.ChargeAttempt;
import com.gb.wallet.domain.account.repository.ChargeAttemptRepository;
import com.gb.wallet.domain.account.service.impl.ChargeAttemptWriterImpl;
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
 * {@link ChargeAttemptWriterImpl} 단위 테스트(WACC-01). "흔적 기록(record-once)"의 세 경로를 검증한다 —
 * 이미 흔적이 있으면 no-op / 없으면 INSERT / 동시 race(UNIQUE 위반)는 흡수(예외 비전파).
 * (RemittanceAttemptWriterTest와 동일 골격 — 충전·송금 흔적 인프라가 대칭임을 회귀로 고정.)
 */
@ExtendWith(MockitoExtension.class)
class ChargeAttemptWriterTest {

    @Mock private ChargeAttemptRepository chargeAttemptRepository;
    @InjectMocks private ChargeAttemptWriterImpl writer;

    private static final String KEY = "idem-key-001";
    private static final String USER = "user-public-id-1";
    private static final Long BANK_ACCOUNT_ID = 10L;
    private static final BigDecimal AMOUNT = new BigDecimal("100000.0000");
    private static final CurrencyType CURRENCY = CurrencyType.KRW;

    @Test
    @DisplayName("이미 흔적이 있으면 no-op — INSERT하지 않는다")
    void 이미있으면_noop() {
        given(chargeAttemptRepository.existsByIdempotencyKey(KEY)).willReturn(true);

        writer.record(KEY, USER, BANK_ACCOUNT_ID, AMOUNT, CURRENCY);

        verify(chargeAttemptRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("흔적이 없으면 INSERT한다")
    void 없으면_INSERT() {
        given(chargeAttemptRepository.existsByIdempotencyKey(KEY)).willReturn(false);

        writer.record(KEY, USER, BANK_ACCOUNT_ID, AMOUNT, CURRENCY);

        ArgumentCaptor<ChargeAttempt> captor = ArgumentCaptor.forClass(ChargeAttempt.class);
        verify(chargeAttemptRepository).saveAndFlush(captor.capture());
        ChargeAttempt saved = captor.getValue();
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
        given(chargeAttemptRepository.existsByIdempotencyKey(KEY)).willReturn(false);
        willThrow(new DataIntegrityViolationException("duplicate idempotency_key"))
                .given(chargeAttemptRepository).saveAndFlush(any());

        assertThatCode(() -> writer.record(KEY, USER, BANK_ACCOUNT_ID, AMOUNT, CURRENCY))
                .doesNotThrowAnyException();
    }
}