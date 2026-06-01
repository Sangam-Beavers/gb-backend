package com.gb.wallet.domain.transaction.service;

import com.gb.wallet.domain.transaction.dto.request.TransferExecuteRequest;
import com.gb.wallet.domain.transaction.dto.request.TransferFeeRequest;
import com.gb.wallet.domain.transaction.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferExecuteResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferFeeResponse;
import com.gb.wallet.domain.transaction.dto.response.ValidateMemberResponse;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.TransactionType;

public interface TransferService {

    /**
     * "최근 송금 앱 사용자" 조회. 내가 송신자였던 INTERNAL_TRANSFER(COMPLETED) 중
     * 수신자별 가장 최근 송금 1건씩, 최근순으로 최대 10명 반환한다.
     */
    RecentRecipientsResponse getRecentInternalRecipients(String userPublicId);

    /**
     * 이메일로 앱 사용자 존재 여부 검증. 없으면 MEMBER_NOT_FOUND(MEMBER4001).
     * wallet DB는 조회하지 않고 MemberClient만 사용한다.
     */
    ValidateMemberResponse validateMember(String email);

    /**
     * 지원 통화 목록 조회(KRW/USD/PHP/VND). CurrencyType enum이 SSOT라 DB/외부 호출 없이
     * enum 순회로 응답을 만든다.
     */
    SupportedCurrenciesResponse getSupportedCurrencies();

    /**
     * "최근 송금 계좌" 조회. 내가 송신자였던 REMITTANCE(COMPLETED) 중 bank_account별로 가장 최근
     * 1건씩, 최근순으로 size건 반환. size는 1~50, 기본 10. wallet 도메인 내부 DB만 사용한다.
     */
    RecentAccountsResponse getRecentRemittanceAccounts(String userPublicId, int size);

    /**
     * 예금주 실명 조회. {@code BankClient.inquiry}로 외부 Mock 은행만 호출하며 DB는 보지 않는다.
     * 외부 에러는 BankErrorMapper가 BusinessException으로 변환해 던지므로 Service는 그대로 전파한다.
     */
    AccountHolderResponse getAccountHolder(String bankCode, String accountNumber);

    /**
     * 송금 수수료 계산. 순수 계산 로직 — DB·외부 호출 없음.
     * 정책은 docs/remittance/api-spec.md §4 SSOT: INTERNAL_TRANSFER=0, REMITTANCE=amount×0.5%(HALF_UP 4자리).
     * 미지원 통화는 {@code TransferErrorCode.UNSUPPORTED_CURRENCY}(TRANSFER4002).
     */
    TransferFeeResponse getTransferFee(TransferFeeRequest request);

    /**
     * 송금 실행 진입점(1단계: INTERNAL_TRANSFER + 같은 통화 전용).
     *
     * <p>흐름: 멱등성 캐시(Layer 1) → DB 조회(Layer 2) → 입력·도메인 검증(통화/송금유형/같은 통화/자기송금) →
     * 송신/수신 wallet 조회 → 분산 락(wallet_id 오름차순 MultiLock) → 트랜잭션 내 처리({@link #executeInTransaction}) →
     * Redis 캐시 저장 → 락 해제. 동시 race로 idempotency_key UNIQUE 위반이 나면
     * {@link #readPriorTransaction}(Layer 3)이 별도 트랜잭션에서 첫 결과를 재반환한다.
     *
     * @param userPublicId   요청자(인증 미구현 — 헤더 수신, CLAUDE.md §9)
     * @param idempotencyKey 멱등성 키(헤더)
     * @param request        송금 요청 본문
     */
    TransferExecuteResponse execute(String userPublicId, String idempotencyKey, TransferExecuteRequest request);

    /**
     * 실제 송금 처리(쓰기 트랜잭션). 잔액 행 비관적 락(wallet_id 오름차순) → 잔액 검증 → 차감/증액 →
     * transaction INSERT → audit_log × 2(SEND/RECEIVE). <b>self-proxy 전용</b> — {@link #execute}가
     * {@code @Lazy} self 프록시를 통해 호출해야 {@code @Transactional}이 적용된다(자기 호출은 AOP 우회).
     * 다른 컴포넌트에서 직접 호출하지 말 것.
     */
    TransferExecuteResponse executeInTransaction(
            Long senderWalletId, Long receiverWalletId,
            CurrencyType currency, TransactionType transferType,
            String idempotencyKey, TransferExecuteRequest request);

    /**
     * 멱등성 race로 idempotency_key UNIQUE 위반이 난 뒤, 먼저 커밋된 첫 거래의 결과를 별도 readOnly
     * 트랜잭션에서 재조회한다(Layer 3). <b>self-proxy 전용</b>. 메인 트랜잭션이 이미 롤백돼 있으므로
     * REQUIRES_NEW 필수.
     */
    TransferExecuteResponse readPriorTransaction(String idempotencyKey);
}
