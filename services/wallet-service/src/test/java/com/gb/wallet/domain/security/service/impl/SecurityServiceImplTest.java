package com.gb.wallet.domain.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gb.wallet.domain.security.dto.response.SecuritySummaryResponse;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;

/**
 * {@link SecurityServiceImpl} 단위 테스트 — 규칙(심야/단시간 다발/대액/실패) 판정과 안전/경고 종합을 검증한다.
 * Transaction 은 엔티티 빌더가 없어 Mockito mock 으로 getter 를 시뮬레이션한다(LENIENT — 규칙마다 보는 필드가 달라
 * 미사용 stub 이 생길 수 있으므로).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecurityServiceImplTest {

    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @InjectMocks private SecurityServiceImpl service;

    private static final String USER = "11111111-1111-1111-1111-111111111111";

    /** 최근(2일 전) 기준 시각 — since(30일 전) 이후라 점검 대상에 포함된다. */
    private LocalDateTime base() {
        return LocalDateTime.now(ZoneOffset.UTC).minusDays(2).withSecond(0).withNano(0);
    }

    private Transaction tx(String id, TransactionType type, TransactionStatus status,
            BigDecimal amount, LocalDateTime createdAt) {
        Transaction t = mock(Transaction.class);
        when(t.getPublicId()).thenReturn(id);
        when(t.getType()).thenReturn(type);
        when(t.getStatus()).thenReturn(status);
        when(t.getCurrencyCode()).thenReturn(CurrencyType.KRW);
        when(t.getAmount()).thenReturn(amount);
        when(t.getCreatedAt()).thenReturn(createdAt);
        return t;
    }

    private void haveWallet() {
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.of(mock(Wallet.class)));
    }

    private void givenTransactions(Transaction... txs) {
        when(transactionRepository.findByWallet_UserPublicId(eq(USER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(txs)));
    }

    private String statusOf(SecuritySummaryResponse r, String code) {
        return r.getChecks().stream()
                .filter(c -> c.getCode().equals(code))
                .findFirst().orElseThrow()
                .getStatus();
    }

    @Test
    @DisplayName("지갑 없음(신규 사용자): 404 대신 SAFE 빈 요약, 거래 조회 안 함")
    void 지갑없음_안전빈요약() {
        when(walletRepository.findByUserPublicId(USER)).thenReturn(Optional.empty());

        SecuritySummaryResponse r = service.getSecuritySummary(USER);

        assertThat(r.getStatus()).isEqualTo("SAFE");
        assertThat(r.getCheckedCount()).isZero();
        assertThat(r.getFlaggedTransactions()).isEmpty();
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("정상 거래만: SAFE, 플래그 0, 모든 항목 SAFE")
    void 정상거래_안전() {
        haveWallet();
        LocalDateTime b = base().withHour(4); // KST 13시 — 주간
        givenTransactions(
                tx("t1", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(100), b),
                tx("t2", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(120), b.plusHours(5)));

        SecuritySummaryResponse r = service.getSecuritySummary(USER);

        assertThat(r.getStatus()).isEqualTo("SAFE");
        assertThat(r.getSuspiciousCount()).isZero();
        assertThat(r.getCheckedCount()).isEqualTo(2);
        assertThat(r.getChecks()).allSatisfy(c -> assertThat(c.getStatus()).isEqualTo("SAFE"));
    }

    @Test
    @DisplayName("심야(KST 00~05시) 거래: NIGHT_TRANSACTION 경고, 종합 WARNING, 해당 거래 플래그")
    void 심야거래_경고() {
        haveWallet();
        LocalDateTime night = base().withHour(18); // +9h => KST 03시
        givenTransactions(
                tx("night1", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(100), night));

        SecuritySummaryResponse r = service.getSecuritySummary(USER);

        assertThat(statusOf(r, "NIGHT_TRANSACTION")).isEqualTo("WARNING");
        assertThat(r.getStatus()).isEqualTo("WARNING");
        assertThat(r.getFlaggedTransactions()).extracting(SecuritySummaryResponse.FlaggedTransaction::getPublicId)
                .contains("night1");
    }

    @Test
    @DisplayName("10분 내 3건 송금: RAPID_SUCCESSION 경고")
    void 단시간다발_경고() {
        haveWallet();
        LocalDateTime b = base().withHour(4).withMinute(0);
        givenTransactions(
                tx("r1", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(100), b),
                tx("r2", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(100), b.plusMinutes(2)),
                tx("r3", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(100), b.plusMinutes(4)));

        SecuritySummaryResponse r = service.getSecuritySummary(USER);

        assertThat(statusOf(r, "RAPID_SUCCESSION")).isEqualTo("WARNING");
        assertThat(statusOf(r, "LARGE_AMOUNT")).isEqualTo("SAFE"); // 동일 금액이라 대액 아님
        assertThat(r.getSuspiciousCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("실패 거래 3건 이상: FAILED_ATTEMPTS 경고")
    void 연속실패_경고() {
        haveWallet();
        LocalDateTime b = base().withHour(4);
        givenTransactions(
                tx("f1", TransactionType.CHARGE, TransactionStatus.FAILED, BigDecimal.valueOf(100), b),
                tx("f2", TransactionType.CHARGE, TransactionStatus.FAILED, BigDecimal.valueOf(100), b.plusHours(1)),
                tx("f3", TransactionType.CHARGE, TransactionStatus.FAILED, BigDecimal.valueOf(100), b.plusHours(2)));

        SecuritySummaryResponse r = service.getSecuritySummary(USER);

        assertThat(statusOf(r, "FAILED_ATTEMPTS")).isEqualTo("WARNING");
        assertThat(r.getStatus()).isEqualTo("WARNING");
    }

    @Test
    @DisplayName("평균 대비 3배 초과 송금: LARGE_AMOUNT 경고, 해당 1건만 플래그")
    void 대액송금_경고() {
        haveWallet();
        LocalDateTime b = base().withHour(4);
        givenTransactions(
                tx("s1", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(100), b),
                tx("s2", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(100), b.plusHours(2)),
                tx("s3", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(100), b.plusHours(4)),
                tx("s4", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(100), b.plusHours(6)),
                tx("big", TransactionType.REMITTANCE, TransactionStatus.COMPLETED, BigDecimal.valueOf(2000), b.plusHours(8)));

        SecuritySummaryResponse r = service.getSecuritySummary(USER);

        assertThat(statusOf(r, "LARGE_AMOUNT")).isEqualTo("WARNING");
        assertThat(r.getFlaggedTransactions()).extracting(SecuritySummaryResponse.FlaggedTransaction::getPublicId)
                .containsExactly("big");
    }
}
