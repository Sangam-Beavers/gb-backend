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
import com.gb.wallet.domain.transaction.service.TransferPinGate;
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
    private final TransferPinGate transferPinGate;

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

        // (3-2) amount 양수(>0) 검증 — DTO의 @Pattern으로도 0을 막지만, 정규식 우회/프로그램 경로(Bean
        //       Validation 미적용) 방어를 위해 도메인 단에서 한 번 더 거른다. signum 0/음수는 COMMON4001.
        //       0이 통과되면 회차 실행 시 WalletBalance.subtract가 IllegalArgumentException을 던져
        //       markExecuted까지 도달하지 못해 같은 항목이 무한 재시도되는 위험이 있다(CodeRabbit 리뷰).
        BigDecimal amount = new BigDecimal(request.amount());
        if (amount.signum() <= 0) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
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

        // (4-2) 송금 PIN 서버측 게이트(TX-PIN, standing order) — 정기송금은 "미래 자금 이동을 예약"하는
        //       행위라 설정 시 1회 PIN 검증으로 인가한다. 회차 실행(스케줄러)은 이 인가를 근거로 면제된다
        //       (executePreAuthorized). pin-verify 성공 마커를 원자 소비 — 미설정 TRANSFER4009 / 미검증 TRANSFER4010.
        //       입력·대상 검증을 모두 통과한 뒤 소비해, 검증 실패가 1회용 마커를 헛되이 태우지 않게 한다(게이트는 persist 직전).
        transferPinGate.requireVerified(userPublicId);

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
                .amount(amount)
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
     * 회원 본명을 안전하게 조회한다 — 조회 실패 시 null로 저장하고 설정은 진행.
     * snapshot({@code scheduled_transfers.receiver_name})은 영속·회차 복사되는 값이라
     * {@link MemberClient#findMember}(원장용 — 미존재·장애 = empty, 폴백 객체 없음)를 쓴다. 표시용
     * {@code getMember}의 "Unknown" 폴백이 snapshot에 박히면 회차마다 가짜 이름이 원장에 복사된다.
     * (TransferServiceImpl.fetchMemberNameSafe와 동일 정책. 향후 공용 헬퍼로 추출 검토.)
     *
     * <p><b>수용된 한계(wallet-sched-1):</b> 등록 순간 장애로 null이 박히면 이후 member-service가
     * 복구돼도 모든 회차의 {@code transactions.receiver_name}이 null로 남는다 — 회차 실행은 스케줄러
     * 스레드(無JWT)라 재조회가 불가능하다. 가짜 이름 영속보다 null이 낫다는 정책 선택이며, 복구가
     * 필요하면 사용자가 정기송금을 재등록하면 된다(등록은 HTTP 컨텍스트라 정상 조회).
     */
    private String fetchMemberNameSafe(String userPublicId) {
        try {
            return memberClient.findMember(userPublicId).map(MemberInfo::name).orElse(null);
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
