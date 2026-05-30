package com.gb.wallet.domain.account.service;

import com.gb.wallet.domain.account.dto.request.ChargeRequest;
import com.gb.wallet.domain.account.dto.response.ChargeResponse;

/**
 * 충전 실행 서비스. 등록 계좌의 {@code mock_account_token}으로 Mock 은행에 출금을 요청하고,
 * 성공 시 본체 KRW 잔액을 증액한다. {@code Idempotency-Key}로 멱등성을 보장한다.
 *
 * <p>멱등성 race 처리(§5-1) 때문에 트랜잭션 경계가 메서드별로 갈린다:
 * <ul>
 *   <li>{@link #charge} — 트랜잭션 <b>밖</b>의 얇은 래퍼. UNIQUE 위반(동시 충전)을 잡아 첫 결과를
 *       재조회한다. 컨트롤러는 이 메서드만 호출한다.</li>
 *   <li>{@link #doCharge} / {@link #readPrior} — 각자 독립 트랜잭션을 여는 내부 경계. self-proxy를
 *       통해서만 호출돼야 {@code @Transactional}이 적용된다(같은 빈 내부 호출은 AOP를 우회하므로).
 *       <b>다른 컴포넌트에서 직접 호출하지 말 것.</b></li>
 * </ul>
 */
public interface ChargeService {

    /**
     * 충전 실행 진입점. 정상 흐름은 {@link #doCharge}에 위임하고, 동시 충전으로 인한
     * {@link org.springframework.dao.DataIntegrityViolationException}(idempotency_key UNIQUE 위반)이
     * 나면 별도 트랜잭션({@link #readPrior})에서 먼저 커밋된 첫 결과를 재반환한다(§5-1).
     *
     * @param userPublicId   요청 사용자(인증 미구현 — 헤더 수신, CLAUDE.md §9)
     * @param accountPublicId 출금 계좌 public_id(요청 path)
     * @param idempotencyKey  멱등성 키(헤더). Mock 은행에도 그대로 forward한다.
     * @param request         충전 요청(amount)
     * @param clientIp        요청 IP(audit_log 기록용)
     */
    ChargeResponse charge(String userPublicId, String accountPublicId,
                          String idempotencyKey, ChargeRequest request, String clientIp);

    /**
     * 실제 충전 처리(쓰기 트랜잭션). [멱등성 선검사 → 계좌·토큰·한도·지갑 검증 → Mock 출금 →
     * 잔액 락·증액 → transaction/audit_log INSERT] 순으로 한 트랜잭션 안에서 처리한다.
     * <b>self-proxy 전용</b> — {@link #charge}가 프록시를 통해 호출해야 트랜잭션이 걸린다.
     */
    ChargeResponse doCharge(String userPublicId, String accountPublicId,
                            String idempotencyKey, ChargeRequest request, String clientIp);

    /**
     * race로 UNIQUE 위반이 난 뒤, 먼저 커밋된 첫 거래의 결과를 별도 readOnly 트랜잭션에서 재조회한다.
     * <b>self-proxy 전용</b>. 키 소유자가 다르면 ACCOUNT4001로 차단한다(정보 누설 방지).
     */
    ChargeResponse readPrior(String idempotencyKey, String accountPublicId, String userPublicId);
}