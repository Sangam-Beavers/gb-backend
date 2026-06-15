package com.gb.wallet.domain.security.service.impl;

import com.gb.wallet.domain.security.dto.response.SecuritySummaryResponse;
import com.gb.wallet.domain.security.dto.response.SecuritySummaryResponse.CheckItem;
import com.gb.wallet.domain.security.dto.response.SecuritySummaryResponse.FlaggedTransaction;
import com.gb.wallet.domain.security.service.SecurityService;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.common.enums.TransactionStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전자지갑 보안 점검 — 규칙 기반(휴리스틱). 새 테이블/스키마 없이 기존 {@code transactions} 를 읽어 계산만 한다.
 *
 * <p>점검 항목 4종(최근 30일, 최대 100건):
 * <ul>
 *   <li>LARGE_AMOUNT — 같은 통화 평균 대비 3배 이상 큰 송금</li>
 *   <li>RAPID_SUCCESSION — 10분 내 3건 이상 연속 송금</li>
 *   <li>NIGHT_TRANSACTION — 심야(KST 00~05시) 거래</li>
 *   <li>FAILED_ATTEMPTS — 실패한 거래 3건 이상</li>
 * </ul>
 *
 * <p>이건 실제 사기탐지(FDS)가 아니라 사용자에게 본인 거래를 돌아보게 하는 휴리스틱 안내다.
 * 지갑이 없으면(신규 사용자) 점검 대상이 없으므로 404 대신 안전(SAFE) 빈 요약을 반환한다 — 홈 카드가
 * 신규 사용자에게도 깨지지 않게 하기 위함.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SecurityServiceImpl implements SecurityService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    private static final int SCAN_LIMIT = 100;
    private static final int SCAN_DAYS = 30;
    private static final long RAPID_WINDOW_MINUTES = 10;
    private static final int RAPID_THRESHOLD = 3;
    private static final int FAILED_THRESHOLD = 3;
    private static final BigDecimal LARGE_MULTIPLIER = BigDecimal.valueOf(3);
    private static final long KST_OFFSET_HOURS = 9; // createdAt 은 UTC 저장 → 심야 판정은 KST 기준

    private static final Set<TransactionType> OUTGOING =
            Set.of(TransactionType.REMITTANCE, TransactionType.INTERNAL_TRANSFER);

    @Override
    public SecuritySummaryResponse getSecuritySummary(String userPublicId) {
        Instant now = Instant.now();

        // 지갑 없으면 점검 대상 없음 → 안전 빈 요약(잔액 조회와 달리 404를 던지지 않는다, 위 Javadoc 참고).
        if (walletRepository.findByUserPublicId(userPublicId).isEmpty()) {
            return SecuritySummaryResponse.safeEmpty(now, allSafeChecks());
        }

        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(SCAN_DAYS);
        List<Transaction> recent = transactionRepository
                .findByWallet_UserPublicId(
                        userPublicId,
                        PageRequest.of(0, SCAN_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent()
                .stream()
                .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isAfter(since))
                .toList();

        // 플래그된 거래 누적(거래별로 사유를 모은다, 입력 순서 유지).
        Map<String, Transaction> flaggedTx = new LinkedHashMap<>();
        Map<String, Set<String>> flaggedReasons = new LinkedHashMap<>();

        boolean largeWarn = checkLargeAmount(recent, flaggedTx, flaggedReasons);
        boolean rapidWarn = checkRapidSuccession(recent, flaggedTx, flaggedReasons);
        boolean nightWarn = checkNightTransactions(recent, flaggedTx, flaggedReasons);
        long failedCount = recent.stream().filter(t -> t.getStatus() == TransactionStatus.FAILED).count();
        boolean failedWarn = failedCount >= FAILED_THRESHOLD;
        if (failedWarn) {
            recent.stream()
                    .filter(t -> t.getStatus() == TransactionStatus.FAILED)
                    .forEach(t -> addFlag(flaggedTx, flaggedReasons, t, "거래 실패"));
        }

        List<CheckItem> checks = List.of(
                CheckItem.of("LARGE_AMOUNT", "비정상적으로 큰 송금", largeWarn,
                        largeWarn ? "평소 평균 대비 3배 이상 큰 송금이 감지되었습니다."
                                : "비정상적으로 큰 송금이 없습니다."),
                CheckItem.of("RAPID_SUCCESSION", "단시간 다발 송금", rapidWarn,
                        rapidWarn ? "10분 내 3건 이상 연속 송금이 감지되었습니다."
                                : "단시간 다발 송금이 없습니다."),
                CheckItem.of("NIGHT_TRANSACTION", "심야 시간대 거래", nightWarn,
                        nightWarn ? "심야(00~05시) 시간대 거래가 있습니다."
                                : "심야 시간대 거래가 없습니다."),
                CheckItem.of("FAILED_ATTEMPTS", "연속된 거래 실패", failedWarn,
                        failedWarn ? "최근 실패한 거래가 " + failedCount + "건 있습니다."
                                : "연속된 거래 실패가 없습니다."));

        boolean anyWarning = largeWarn || rapidWarn || nightWarn || failedWarn;
        List<FlaggedTransaction> flagged = buildFlagged(flaggedTx, flaggedReasons);

        return SecuritySummaryResponse.of(anyWarning, recent.size(), checks, flagged, now);
    }

    /** LARGE_AMOUNT: 같은 통화 송금(OUTGOING) 평균의 3배 초과 거래를 플래그. 통화별 표본 3건 이상일 때만 판정. */
    private boolean checkLargeAmount(List<Transaction> recent, Map<String, Transaction> flaggedTx,
            Map<String, Set<String>> reasons) {
        Map<com.gb.wallet.global.common.enums.CurrencyType, List<Transaction>> byCurrency =
                new EnumMap<>(com.gb.wallet.global.common.enums.CurrencyType.class);
        for (Transaction t : recent) {
            if (OUTGOING.contains(t.getType()) && t.getStatus() == TransactionStatus.COMPLETED) {
                byCurrency.computeIfAbsent(t.getCurrencyCode(), k -> new ArrayList<>()).add(t);
            }
        }
        boolean warn = false;
        for (List<Transaction> group : byCurrency.values()) {
            if (group.size() < 3) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Transaction t : group) {
                sum = sum.add(t.getAmount());
            }
            BigDecimal avg = sum.divide(BigDecimal.valueOf(group.size()), 4, RoundingMode.HALF_UP);
            BigDecimal threshold = avg.multiply(LARGE_MULTIPLIER);
            for (Transaction t : group) {
                if (t.getAmount().compareTo(threshold) > 0) {
                    addFlag(flaggedTx, reasons, t, "평소보다 큰 금액");
                    warn = true;
                }
            }
        }
        return warn;
    }

    /** RAPID_SUCCESSION: 송금(OUTGOING)을 시간 오름차순으로 보고 10분 윈도우에 3건 이상이면 해당 거래들을 플래그. */
    private boolean checkRapidSuccession(List<Transaction> recent, Map<String, Transaction> flaggedTx,
            Map<String, Set<String>> reasons) {
        List<Transaction> outgoing = new ArrayList<>(recent.stream()
                .filter(t -> OUTGOING.contains(t.getType()) && t.getCreatedAt() != null)
                .toList());
        outgoing.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt())); // 오름차순
        boolean warn = false;
        int n = outgoing.size();
        for (int i = 0; i < n; i++) {
            int j = i;
            while (j < n && !outgoing.get(j).getCreatedAt()
                    .isAfter(outgoing.get(i).getCreatedAt().plusMinutes(RAPID_WINDOW_MINUTES))) {
                j++;
            }
            if (j - i >= RAPID_THRESHOLD) {
                for (int k = i; k < j; k++) {
                    addFlag(flaggedTx, reasons, outgoing.get(k), "단시간 연속 송금");
                }
                warn = true;
            }
        }
        return warn;
    }

    /** NIGHT_TRANSACTION: KST 00~05시 거래를 플래그(createdAt 은 UTC 저장이라 +9h 후 시각 판정). */
    private boolean checkNightTransactions(List<Transaction> recent, Map<String, Transaction> flaggedTx,
            Map<String, Set<String>> reasons) {
        boolean warn = false;
        for (Transaction t : recent) {
            if (t.getCreatedAt() == null) {
                continue;
            }
            int kstHour = t.getCreatedAt().plusHours(KST_OFFSET_HOURS).getHour();
            if (kstHour >= 0 && kstHour < 5) {
                addFlag(flaggedTx, reasons, t, "심야 시간대");
                warn = true;
            }
        }
        return warn;
    }

    private void addFlag(Map<String, Transaction> flaggedTx, Map<String, Set<String>> reasons,
            Transaction t, String reason) {
        flaggedTx.putIfAbsent(t.getPublicId(), t);
        reasons.computeIfAbsent(t.getPublicId(), k -> new LinkedHashSet<>()).add(reason);
    }

    private List<FlaggedTransaction> buildFlagged(Map<String, Transaction> flaggedTx,
            Map<String, Set<String>> reasons) {
        List<FlaggedTransaction> result = new ArrayList<>();
        for (Map.Entry<String, Transaction> e : flaggedTx.entrySet()) {
            Transaction t = e.getValue();
            result.add(FlaggedTransaction.builder()
                    .publicId(t.getPublicId())
                    .type(t.getType().name())
                    .amount(t.getAmount().setScale(4, RoundingMode.HALF_UP).toPlainString())
                    .currencyCode(t.getCurrencyCode().name())
                    .createdAt(toUtcZ(t.getCreatedAt()))
                    .reason(String.join(", ", reasons.getOrDefault(e.getKey(), Set.of())))
                    .build());
        }
        return result;
    }

    private List<CheckItem> allSafeChecks() {
        return List.of(
                CheckItem.of("LARGE_AMOUNT", "비정상적으로 큰 송금", false, "비정상적으로 큰 송금이 없습니다."),
                CheckItem.of("RAPID_SUCCESSION", "단시간 다발 송금", false, "단시간 다발 송금이 없습니다."),
                CheckItem.of("NIGHT_TRANSACTION", "심야 시간대 거래", false, "심야 시간대 거래가 없습니다."),
                CheckItem.of("FAILED_ATTEMPTS", "연속된 거래 실패", false, "연속된 거래 실패가 없습니다."));
    }

    private static String toUtcZ(LocalDateTime dateTime) {
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}
