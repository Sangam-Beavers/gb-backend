package com.gb.wallet.domain.account.service;

import com.gb.wallet.domain.account.dto.request.RegisterAccountRequest;
import com.gb.wallet.domain.account.dto.request.VerifyAccountRequest;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;

public interface BankAccountService {

    /** 요청 회원(user_public_id)의 등록된 활성 계좌 목록을 주 계좌 우선, 최신 등록 순으로 조회한다. */
    AccountListResponse getMyAccounts(String userPublicId);

    /**
     * 외부 Mock 은행에 계좌 인증을 위임해 {@code account_token}을 발급받는다.
     * 본체 DB에는 아무것도 쓰지 않는 외부 호출 어댑터.
     *
     * <p>한 출처(IP)의 외부 은행 인증 폭주를 막기 위해 IP 단위 rate-limit을 적용한다 — 윈도 내 허용
     * 횟수를 초과하면 {@code ACCOUNT4005}로 거부한다. Redis 장애 시에는 fail-open(통과)한다.
     *
     * @param clientIp 요청 클라이언트 IP(rate-limit 카운터 키). 컨트롤러가 {@code ClientIpResolver}로 추출해 넘긴다.
     */
    VerifyAccountResponse verifyAccount(VerifyAccountRequest request, String clientIp);

    /**
     * 계좌 등록 최종 확정 진입점. 동시 등록 race(중복 계좌·다중 주계좌)를 막기 위해 user 단위 분산락으로
     * 전체를 감싼 뒤 {@link #registerAccountLocked}에 위임한다. 락 획득 실패 시 {@code COMMON5031}(503).
     * 컨트롤러는 이 메서드만 호출한다.
     */
    AccountResponse registerAccount(String userPublicId, RegisterAccountRequest request);

    /**
     * 계좌 등록의 실제 처리(쓰기 트랜잭션). {@code bank_accounts}에 INSERT하고 {@code mock_account_token}을
     * 함께 저장한다(추후 충전 시 사용). 사용자의 첫 활성 계좌면 {@code isPrimary=true}로 강제 등록한다.
     *
     * <p><b>self-proxy 전용</b> — {@link #registerAccount}가 락을 잡은 채 프록시를 통해 호출해야
     * {@code @Transactional}이 적용되고, 락이 트랜잭션 커밋 시점까지 유지된다(같은 빈 내부 직접 호출은
     * AOP를 우회). 다른 컴포넌트에서 직접 호출하지 말 것.
     */
    AccountResponse registerAccountLocked(String userPublicId, RegisterAccountRequest request);

    /**
     * 지정한 계좌를 주 계좌로 변경한다(PATCH /api/v1/accounts/{id}/primary). 기존 주 계좌는 자동 해제해
     * "사용자당 주 계좌 1개" 불변식을 유지한다. 등록/변경/삭제가 동시에 일어나도 불변식이 깨지지 않도록
     * register와 동일한 user 단위 분산락으로 전체를 감싼 뒤 {@link #changePrimaryLocked}에 위임한다.
     * 락 획득 실패 시 {@code COMMON5031}(503). 컨트롤러는 이 메서드만 호출한다.
     */
    AccountResponse changePrimary(String userPublicId, String accountPublicId);

    /**
     * 주 계좌 변경의 실제 처리(쓰기 트랜잭션). 대상이 없으면 {@code ACCOUNT4001}, 이미 주 계좌면 멱등 성공.
     *
     * <p><b>self-proxy 전용</b> — {@link #changePrimary}가 락을 잡은 채 프록시를 통해 호출해야
     * {@code @Transactional}이 적용된다(같은 빈 내부 직접 호출은 AOP 우회). 직접 호출하지 말 것.
     */
    AccountResponse changePrimaryLocked(String userPublicId, String accountPublicId);

    /**
     * 계좌를 삭제(soft-delete)한다(DELETE /api/v1/accounts/{id}). 주 계좌를 삭제하면 남은 활성 계좌 중
     * 가장 최근 등록 1건을 자동으로 주 계좌 승격한다(마지막 1개면 주 계좌 없는 상태 허용). register와 동일한
     * user 단위 분산락으로 감싼 뒤 {@link #deleteAccountLocked}에 위임한다. 락 획득 실패 시 {@code COMMON5031}.
     */
    void deleteAccount(String userPublicId, String accountPublicId);

    /**
     * 계좌 삭제의 실제 처리(쓰기 트랜잭션). 대상이 없으면 {@code ACCOUNT4001}.
     *
     * <p><b>self-proxy 전용</b> — {@link #deleteAccount}가 락을 잡은 채 프록시를 통해 호출해야 한다.
     * 직접 호출하지 말 것.
     */
    void deleteAccountLocked(String userPublicId, String accountPublicId);
}
