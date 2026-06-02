package com.gb.wallet.domain.transaction.scheduled.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.scheduled.dto.request.CreateScheduledTransferRequest;
import com.gb.wallet.domain.transaction.scheduled.dto.response.ScheduledTransferHistoryResponse;
import com.gb.wallet.domain.transaction.scheduled.dto.response.ScheduledTransferListResponse;
import com.gb.wallet.domain.transaction.scheduled.dto.response.ScheduledTransferResponse;
import com.gb.wallet.domain.transaction.scheduled.entity.ScheduledTransfer;
import com.gb.wallet.domain.transaction.scheduled.repository.ScheduledTransferRepository;
import com.gb.wallet.domain.transaction.scheduled.service.NextRunDateCalculator;
import com.gb.wallet.domain.transaction.scheduled.service.ScheduledTransferService;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.ScheduledTransferStatus;
import com.gb.wallet.global.common.enums.TransactionType;
import com.gb.wallet.global.common.enums.TransferFrequency;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import com.gb.wallet.global.exception.code.TransferErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ScheduledTransferService} 구현. 검증 패턴은 {@code TransferServiceImpl.validateScheduled}와
 * 일관 — 향후 공용 헬퍼로 추출 검토(현 사이클은 중복 허용, 두 흐름 다 단순).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTransferServiceImpl implements ScheduledTransferService {

    /** 정기 송금 허용 유형 (CHARGE/EXCHANGE 제외). */
    private static final Set<TransactionType> ALLOWED_TRANSFER_TYPES =
            EnumSet.of(TransactionType.INTERNAL_TRANSFER, TransactionType.REMITTANCE);

    private final ScheduledTransferRepository scheduledTransferRepository;
    private final BankAccountRepository bankAccountRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final MemberClient memberClient;
    private final NextRunDateCalculator nextRunDateCalculator;

    @Override
    @Transactional
    public ScheduledTransferResponse create(String userPublicId, CreateScheduledTransferRequest request) {
        // (1) enum 파싱 — transferType은 허용 유형 외 거부, currency 2개, frequency 모두 파싱.
        TransactionType type = TransactionType.fromCode(request.transferType())
                .filter(ALLOWED_TRANSFER_TYPES::contains)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_TRANSFER_TYPE));
        CurrencyType currency = CurrencyType.fromCode(request.currencyCode())
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY));
        CurrencyType receiveCurrency = CurrencyType.fromCode(request.receiveCurrencyCode())
                .orElseThrow(() -> new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY));
        TransferFrequency frequency = TransferFrequency.fromCode(request.frequency())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));

        // (2) schedule_day 범위 검증 — 값 자체가 유효 범위 벗어남은 COMMON4221(422)로 분리.
        //     형식 오류(@NotNull 위반 등)는 @Valid가 COMMON4001로 잡음.
        int scheduleDay = request.scheduleDay();
        if (!frequency.isValidDay(scheduleDay)) {
            throw new BusinessException(CommonErrorCode.UNPROCESSABLE_ENTITY);
        }

        // (3) same-currency 강제 (1·2단계). 다통화는 3단계 도입 후 본 검증 완화.
        //     값 자체가 비호환 조합이므로 COMMON4221(422)로 분리(@Valid의 400과 의미 구분).
        if (currency != receiveCurrency) {
            throw new BusinessException(CommonErrorCode.UNPROCESSABLE_ENTITY);
        }

        // (4) 도메인별 대상 검증 + receiver_name snapshot.
        String receiverPublicId = null;
        Long bankAccountId = null;
        String receiverName;

        if (type == TransactionType.REMITTANCE) {
            String accountPublicId = request.bankAccountPublicId();
            if (accountPublicId == null || accountPublicId.isBlank()) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
            }
            BankAccount account = bankAccountRepository
                    .findByPublicIdAndUserPublicIdAndIsActiveTrue(accountPublicId, userPublicId)
                    .orElseThrow(() -> new BusinessException(AccountErrorCode.ACCOUNT_NOT_FOUND));
            if (account.getMockAccountToken() == null) {
                throw new BusinessException(AccountErrorCode.UNVERIFIED_ACCOUNT);
            }
            bankAccountId = account.getId();
            // REMITTANCE: bankAccount.holderName을 snapshot. 구 계좌면 null.
            receiverName = account.getHolderName();
        } else {
            // INTERNAL_TRANSFER
            receiverPublicId = request.receiverPublicId();
            if (receiverPublicId == null || receiverPublicId.isBlank()) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
            }
            if (receiverPublicId.equals(userPublicId)) {
                throw new BusinessException(TransferErrorCode.SELF_TRANSFER_NOT_ALLOWED);
            }
            walletRepository.findByUserPublicId(receiverPublicId)
                    .orElseThrow(() -> new BusinessException(WalletErrorCode.WALLET_NOT_FOUND));
            // INTERNAL: MemberClient로 receiver name snapshot (fail-open — TransferServiceImpl 동일 정책).
            receiverName = fetchMemberNameSafe(receiverPublicId);
        }

        // (5) next_run_date 계산 (KST 기준, 월말 fallback, "오늘 지났으면 다음 주기").
        LocalDate nextRunDate = nextRunDateCalculator.calculate(frequency, scheduleDay);

        // (6) ScheduledTransfer INSERT — 설정 즉시 ACTIVE.
        ScheduledTransfer saved = scheduledTransferRepository.save(ScheduledTransfer.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .transferType(type)
                .receiverPublicId(receiverPublicId)
                .bankAccountId(bankAccountId)
                .receiverName(receiverName)
                .amount(new BigDecimal(request.amount()))
                .currencyCode(currency)
                .receiveCurrencyCode(receiveCurrency)
                .frequency(frequency)
                .scheduleDay(scheduleDay)
                .nextRunDate(nextRunDate)
                .status(ScheduledTransferStatus.ACTIVE)
                .memo(request.memo())
                .build());

        return ScheduledTransferResponse.from(saved);
    }

    /**
     * 회원 본명을 안전하게 조회한다 — fail-open. MemberClient 장애 시 null로 저장하고 설정은 진행.
     * (TransferServiceImpl.fetchMemberNameSafe와 동일 정책. 향후 공용 헬퍼로 추출 검토.)
     */
    private String fetchMemberNameSafe(String userPublicId) {
        try {
            MemberInfo info = memberClient.getMember(userPublicId);
            return info != null ? info.name() : null;
        } catch (RuntimeException e) {
            log.warn("MemberClient 조회 실패 — receiverName=null로 저장. user={}", userPublicId, e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내역 조회 (GET /api/v1/transfers/scheduled)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ScheduledTransferListResponse list(String userPublicId, String statusFilter,
                                              int page, int size) {
        // 정렬: 최신 설정 우선(created_at DESC). 향후 status 우선 정렬 검토 가능(현 사이클은 단순).
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<ScheduledTransfer> result;
        if (statusFilter == null || statusFilter.isBlank()) {
            // status 미지정 → 전체.
            result = scheduledTransferRepository.findByUserPublicId(userPublicId, pageable);
        } else {
            // 잘못된 status 값은 COMMON4001 (TRANSFER 도메인이 아닌 일반 입력 오류).
            ScheduledTransferStatus parsed = parseStatus(statusFilter);
            result = scheduledTransferRepository
                    .findByUserPublicIdAndStatus(userPublicId, parsed, pageable);
        }

        return ScheduledTransferListResponse.from(result);
    }

    private ScheduledTransferStatus parseStatus(String raw) {
        try {
            return ScheduledTransferStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 회차 실행 이력 조회 (GET /api/v1/transfers/scheduled/{id}/history)
    // ─────────────────────────────────────────────────────────────────────────

    /** 스케줄러가 회차마다 박는 idempotency_key prefix — Repository 검색에 사용. */
    private static final String SCHEDULED_KEY_PREFIX = "scheduled:";

    @Override
    @Transactional(readOnly = true)
    public ScheduledTransferHistoryResponse getHistory(
            String userPublicId, String transferPublicId, int page, int size) {

        // (1) 정기송금 조회 — 본인 검증 통합. 미존재·본인 아님 모두 TRANSFER4001로 모호 매핑(정보 누설 방지).
        //     충전 rebuildFromPrior / 송금 확인증과 동일 정책.
        ScheduledTransfer scheduled = scheduledTransferRepository.findByPublicId(transferPublicId)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.TRANSFER_NOT_FOUND));

        if (!scheduled.getUserPublicId().equals(userPublicId)) {
            throw new BusinessException(TransferErrorCode.TRANSFER_NOT_FOUND);
        }

        // (2) 회차 이력 페이지 조회 — idempotency_key가 "scheduled:{publicId}:" prefix로 시작하는 transactions.
        //     정렬: created_at DESC (= 실행 시각 역순).
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        String keyPrefix = SCHEDULED_KEY_PREFIX + transferPublicId + ":";
        Page<Transaction> historyPage = transactionRepository
                .findByIdempotencyKeyStartingWith(keyPrefix, pageable);

        return ScheduledTransferHistoryResponse.from(historyPage);
    }
}
