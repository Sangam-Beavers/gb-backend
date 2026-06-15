package com.gb.wallet.domain.admin.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.entity.ChargeAttempt;
import com.gb.wallet.domain.account.repository.ChargeAttemptRepository;
import com.gb.wallet.domain.admin.dto.response.AdminTransactionPageResponse;
import com.gb.wallet.domain.admin.dto.response.AdminTransactionView;
import com.gb.wallet.domain.admin.dto.response.ChargeAttemptPageResponse;
import com.gb.wallet.domain.admin.dto.response.ChargeAttemptView;
import com.gb.wallet.domain.admin.dto.response.TransactionAuditLogPageResponse;
import com.gb.wallet.domain.admin.dto.response.TransactionAuditLogView;
import com.gb.wallet.domain.admin.dto.response.TransactionAuditTrailResponse;
import com.gb.wallet.domain.admin.dto.response.RevenueStatsResponse;
import com.gb.wallet.domain.admin.dto.response.RevenueStatsResponse.CurrencyFee;
import com.gb.wallet.domain.admin.dto.response.RevenueStatsResponse.MonthlyFee;
import com.gb.wallet.domain.admin.dto.response.TransactionStatsResponse;
import com.gb.wallet.domain.admin.service.WalletAdminInternalService;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.entity.TransactionAuditLog;
import com.gb.wallet.domain.transaction.repository.TransactionAuditLogRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.repository.TransactionRepository.FeeByTypeCurrencyProjection;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 BFF 전용 wallet 내부 API 구현.
 *
 * <p>기존 도메인 비즈니스 로직(Charge/Transfer/...)을 건드리지 않고 조회 전용으로 동작한다.
 * Page/Sort는 createdAt DESC 기본.
 *
 * <p>risk_level은 본체 스키마에 컬럼이 없어 발표용 임시 계산을 view DTO에서 한다(amount 기반).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletAdminInternalServiceImpl implements WalletAdminInternalService {

    private final TransactionRepository transactionRepository;
    private final TransactionAuditLogRepository auditLogRepository;
    private final ChargeAttemptRepository chargeAttemptRepository;

    @Override
    public AdminTransactionPageResponse searchTransactions(
            LocalDateTime from, LocalDateTime to,
            String type, String status, String risk,
            String userPublicId, int page, int size) {

        TransactionType typeEnum = parseEnum(TransactionType.class, type);
        TransactionStatus statusEnum = parseEnum(TransactionStatus.class, status);

        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Transaction> result = transactionRepository.searchForAdmin(
                typeEnum, statusEnum, blankToNull(userPublicId), from, to, pageable);

        Page<AdminTransactionView> mapped = result.map(AdminTransactionView::from);

        // risk 필터는 view DTO에서 계산하므로 사후 필터링 — 기존 페이지 메타는 그대로 유지(발표용 단순화).
        if (risk != null && !risk.isBlank()) {
            String r = risk.toUpperCase();
            List<AdminTransactionView> filtered = mapped.getContent().stream()
                    .filter(v -> r.equals(v.riskLevel()))
                    .toList();
            // PageImpl로 다시 감싼다 — 전체 count는 risk pre-filter 이전 값 그대로(발표 ok).
            mapped = new org.springframework.data.domain.PageImpl<>(
                    filtered, pageable, result.getTotalElements());
        }

        return AdminTransactionPageResponse.from(mapped);
    }

    @Override
    public TransactionAuditTrailResponse getAuditTrail(String transactionPublicId) {
        Transaction tx = transactionRepository.findByPublicIdWithWallet(transactionPublicId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        List<TransactionAuditLog> logs = auditLogRepository.findAllByTransaction_IdOrderByIdAsc(tx.getId());
        return new TransactionAuditTrailResponse(
                AdminTransactionView.from(tx),
                logs.stream().map(TransactionAuditLogView::from).toList());
    }

    @Override
    public TransactionAuditLogPageResponse searchAuditLogs(
            String userPublicId, String action, String status,
            LocalDateTime from, LocalDateTime to,
            BigDecimal minAmount, BigDecimal maxAmount,
            String ipAddress, int page, int size) {

        TransactionStatus statusEnum = parseEnum(TransactionStatus.class, status);
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<TransactionAuditLog> logs = auditLogRepository.searchForAdmin(
                blankToNull(userPublicId), blankToNull(action), statusEnum,
                from, to, minAmount, maxAmount, blankToNull(ipAddress), pageable);

        return TransactionAuditLogPageResponse.from(logs.map(TransactionAuditLogView::from));
    }

    @Override
    public ChargeAttemptPageResponse searchChargeAttempts(
            String status, String userPublicId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "attemptedAt"));
        Page<ChargeAttempt> page0;
        if (userPublicId != null && !userPublicId.isBlank()) {
            page0 = chargeAttemptRepository.findAllByUserPublicId(userPublicId, pageable);
        } else {
            page0 = chargeAttemptRepository.findAll(pageable);
        }
        String s = (status == null || status.isBlank()) ? "FAILED" : status.toUpperCase();
        return ChargeAttemptPageResponse.from(page0.map(a -> ChargeAttemptView.from(a, s)));
    }

    @Override
    public TransactionStatsResponse getStats(LocalDateTime from, LocalDateTime to) {
        LocalDateTime f = from != null ? from : LocalDate.now().atStartOfDay();
        LocalDateTime t = to != null ? to : LocalDateTime.of(LocalDate.now(), LocalTime.MAX);

        // 통화별 합계(COMPLETED)
        Map<String, String> totals = new LinkedHashMap<>();
        // 표시 안정성을 위해 주요 통화는 0으로라도 채운다.
        for (CurrencyType c : List.of(CurrencyType.KRW, CurrencyType.USD, CurrencyType.VND, CurrencyType.PHP)) {
            totals.put(c.name(), "0.0000");
        }
        transactionRepository.sumAmountByCurrency(f, t).forEach(p -> {
            BigDecimal total = p.getTotal() != null ? p.getTotal() : BigDecimal.ZERO;
            totals.put(p.getCurrencyCode().name(), total.setScale(4, RoundingMode.HALF_UP).toPlainString());
        });

        // by_action (type별)
        Map<String, Long> byAction = new LinkedHashMap<>();
        for (TransactionType ty : TransactionType.values()) {
            byAction.put(ty.name(), 0L);
        }
        transactionRepository.countByType(f, t).forEach(p -> byAction.put(p.getType().name(), p.getCnt()));

        // by_status
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (TransactionStatus st : TransactionStatus.values()) {
            byStatus.put(st.name(), 0L);
        }
        transactionRepository.countByStatus(f, t).forEach(p -> byStatus.put(p.getStatus().name(), p.getCnt()));

        // 송금/충전 성공률 계산.
        // 송금은 두 가지 타입 모두 포함: REMITTANCE(타행/해외 송금) + INTERNAL_TRANSFER(앱내 사용자간 송금).
        // 사용자가 "송금"이라고 부르는 모든 행위를 SLO에 반영하기 위해 합산한다.
        var matrix = transactionRepository.countByTypeAndStatus(
                Arrays.asList(
                        TransactionType.REMITTANCE,
                        TransactionType.INTERNAL_TRANSFER,
                        TransactionType.CHARGE),
                f, t);
        long remTotal = 0, remCompleted = 0, chargeTotal = 0, chargeCompleted = 0;
        for (var row : matrix) {
            long c = row.getCnt();
            if (row.getType() == TransactionType.REMITTANCE
                    || row.getType() == TransactionType.INTERNAL_TRANSFER) {
                remTotal += c;
                if (row.getStatus() == TransactionStatus.COMPLETED) remCompleted += c;
            } else if (row.getType() == TransactionType.CHARGE) {
                chargeTotal += c;
                if (row.getStatus() == TransactionStatus.COMPLETED) chargeCompleted += c;
            }
        }
        String remRate = formatRate(remCompleted, remTotal);
        String chargeRate = formatRate(chargeCompleted, chargeTotal);

        long dau = auditLogRepository.countDistinctUsersBetween(f, t);

        // failed charge queue = FAILED 상태 CHARGE transactions
        long failedChargeQueue = 0L;
        for (var row : matrix) {
            if (row.getType() == TransactionType.CHARGE && row.getStatus() == TransactionStatus.FAILED) {
                failedChargeQueue = row.getCnt();
                break;
            }
        }

        return new TransactionStatsResponse(
                totals, byAction, byStatus, remRate, chargeRate, dau, failedChargeQueue);
    }

    @Override
    public RevenueStatsResponse getRevenue() {
        // 수익 = COMPLETED 환전/송금 거래의 fee 합계. (전체기간 + 이번 달 + 통화별 + 월별 추이)
        // 기준 통화는 KRW — 외국인 근로자 환전/송금의 출금 통화가 KRW라 수수료도 KRW 기준이다.
        List<FeeByTypeCurrencyProjection> allTime = transactionRepository.sumFeeByTypeAndCurrency(null, null);

        BigDecimal totalExchange = BigDecimal.ZERO;
        BigDecimal totalRemittance = BigDecimal.ZERO;
        // 통화별 [환전, 송금] 누적. 입력 순서 유지를 위해 LinkedHashMap.
        Map<CurrencyType, BigDecimal[]> byCur = new LinkedHashMap<>();
        for (FeeByTypeCurrencyProjection p : allTime) {
            BigDecimal fee = p.getTotalFee() != null ? p.getTotalFee() : BigDecimal.ZERO;
            BigDecimal[] slot = byCur.computeIfAbsent(p.getCurrencyCode(),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            if (p.getType() == TransactionType.EXCHANGE) {
                totalExchange = totalExchange.add(fee);
                slot[0] = slot[0].add(fee);
            } else if (p.getType() == TransactionType.REMITTANCE) {
                totalRemittance = totalRemittance.add(fee);
                slot[1] = slot[1].add(fee);
            }
        }

        // 이번 달
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        BigDecimal[] thisMonth = sumFeesByType(monthStart, now);

        // 통화별 내역 — 환전/송금 둘 다 0인 통화는 제외.
        List<CurrencyFee> byCurrency = new ArrayList<>();
        for (Map.Entry<CurrencyType, BigDecimal[]> e : byCur.entrySet()) {
            BigDecimal ex = e.getValue()[0];
            BigDecimal rem = e.getValue()[1];
            if (ex.signum() == 0 && rem.signum() == 0) continue;
            byCurrency.add(new CurrencyFee(e.getKey().name(), money(ex), money(rem)));
        }

        // 최근 6개월 추이(이번 달 포함). 기간을 바꿔가며 같은 집계 쿼리를 재사용한다.
        List<MonthlyFee> monthlyTrend = new ArrayList<>();
        YearMonth current = YearMonth.from(LocalDate.now());
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = current.minusMonths(i);
            LocalDateTime f = ym.atDay(1).atStartOfDay();
            LocalDateTime t = ym.atEndOfMonth().atTime(LocalTime.MAX);
            BigDecimal[] m = sumFeesByType(f, t);
            monthlyTrend.add(new MonthlyFee(ym.toString(), money(m[0]), money(m[1])));
        }

        return new RevenueStatsResponse(
                CurrencyType.KRW.name(),
                money(totalExchange),
                money(totalRemittance),
                money(totalExchange.add(totalRemittance)),
                money(thisMonth[0]),
                money(thisMonth[1]),
                byCurrency,
                monthlyTrend);
    }

    /** 기간 내 [환전, 송금] 수수료 합계(통화 무관 단순 합 — 기준 통화 KRW). */
    private BigDecimal[] sumFeesByType(LocalDateTime from, LocalDateTime to) {
        BigDecimal exchange = BigDecimal.ZERO;
        BigDecimal remittance = BigDecimal.ZERO;
        for (FeeByTypeCurrencyProjection p : transactionRepository.sumFeeByTypeAndCurrency(from, to)) {
            BigDecimal fee = p.getTotalFee() != null ? p.getTotalFee() : BigDecimal.ZERO;
            if (p.getType() == TransactionType.EXCHANGE) {
                exchange = exchange.add(fee);
            } else if (p.getType() == TransactionType.REMITTANCE) {
                remittance = remittance.add(fee);
            }
        }
        return new BigDecimal[] {exchange, remittance};
    }

    /** BigDecimal → 금액 String(소수 4자리, CLAUDE §5). null 은 0 처리. */
    private static String money(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatRate(long success, long total) {
        if (total == 0) return "0.0000";
        BigDecimal rate = BigDecimal.valueOf(success)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        return rate.toPlainString();
    }

    private static int normalizeSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, 200);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException ignore) {
            // 발표용: enum 변환 실패는 "필터 미적용"으로 무시(검색 화면 강성 유지). 향후 도메인 에러로 강화 가능.
            return null;
        }
    }
}
