package com.gb.wallet.domain.transaction.service;

import com.gb.wallet.domain.transaction.dto.request.TransferExecuteRequest;
import com.gb.wallet.domain.transaction.dto.request.TransferFeeRequest;
import com.gb.wallet.domain.transaction.dto.request.ValidateScheduledRequest;
import com.gb.wallet.domain.transaction.dto.response.AccountHolderResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentAccountsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferExecuteResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferFeeResponse;
import com.gb.wallet.domain.transaction.dto.response.TransferReceiptResponse;
import com.gb.wallet.domain.transaction.dto.response.ValidateMemberResponse;
import com.gb.wallet.domain.transaction.dto.response.ValidateScheduledResponse;
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
     * 트랜잭션에서 재조회하고 <b>키 소유자/유형/스코프 일치</b>를 검증한 뒤 응답한다(Layer 3).
     * <b>self-proxy 전용</b>. 메인 트랜잭션이 이미 롤백돼 있으므로 REQUIRES_NEW 필수.
     *
     * <p>{@code idempotency_key}는 전역 UNIQUE라 같은 키로 다른 사용자/유형/계좌의 거래가 잡힐 수 있다.
     * 본 요청의 응답으로 재현해도 되는 거래인지 검증해 cross-user/account 응답 노출을 차단한다
     * (충전 {@code ChargeServiceImpl.rebuildFromPrior} 정책 답습 — 사유 미구분으로 정보 누설 방지).
     *
     * @param idempotencyKey 멱등성 키
     * @param userPublicId   요청자 (키 소유자와 일치 검증)
     * @param expectedType   요청 송금 유형 (저장된 거래 유형과 일치 검증)
     * @param expectedScopeId REMITTANCE면 bank_account.public_id, INTERNAL_TRANSFER면 receiver wallet의 user_public_id
     */
    TransferExecuteResponse readPriorTransaction(
            String idempotencyKey, String userPublicId,
            TransactionType expectedType, String expectedScopeId);

    /**
     * 송금 확인증 조회 — 완료된 송금 한 건의 송수신자/금액/수수료/환율 등을 반환한다.
     *
     * <p>대상 거래: {@code INTERNAL_TRANSFER} · {@code REMITTANCE}만. 충전·환전·기타 유형은
     * {@code TRANSFER4001}(존재하지 않는 송금 내역)로 차단한다.
     *
     * <p>본인 검증: 요청자가 송신자(=거래 wallet 주인)일 때만 조회 가능. 다른 사용자가 조회 시도하면
     * 정보 누설 방지로 같은 {@code TRANSFER4001}로 모호 매핑한다(충전 정책 답습).
     *
     * @param userPublicId 요청자(JWT public_id)
     * @param transferPublicId 송금 거래의 public_id(UUID)
     * @return 확인증 응답 DTO. REMITTANCE면 bank/account 정보 포함, INTERNAL이면 null.
     */
    TransferReceiptResponse getReceipt(String userPublicId, String transferPublicId);

    /**
     * 정기 송금 대상 유효성 사전 검증 — 정기 송금 설정 전 (수취 대상, 금액, 통화) 조합이 정합한지 확인한다.
     *
     * <p>송금 실행({@link #execute})과 동일하게 두 유형(INTERNAL_TRANSFER · REMITTANCE)을 한 엔드포인트에서
     * {@code transferType}으로 분기한다. 대상 식별자는 유형별로 다름:
     * <ul>
     *   <li>INTERNAL_TRANSFER → {@code receiverPublicId} 필수. 수신자 wallet 존재 + 자기 자신 차단 검증.</li>
     *   <li>REMITTANCE → {@code bankAccountPublicId} 필수. 본인 소유 + 활성 + 인증 토큰 검증.</li>
     * </ul>
     *
     * <p>도메인 검증(통화 정합성 등) 미통과는 200 + {@code is_valid=false} + {@code reason}으로 응답한다.
     * 입력 형식·계좌 미존재·미인증·자기송금 등은 도메인 에러(400/403/404)로 응답된다.
     */
    ValidateScheduledResponse validateScheduled(String userPublicId, ValidateScheduledRequest request);
}
