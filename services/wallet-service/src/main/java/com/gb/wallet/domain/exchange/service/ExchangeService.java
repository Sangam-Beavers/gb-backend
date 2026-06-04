package com.gb.wallet.domain.exchange.service;

import com.gb.wallet.domain.exchange.dto.QuoteData;
import com.gb.wallet.domain.exchange.dto.request.ExchangeExecuteRequest;
import com.gb.wallet.domain.exchange.dto.request.QuoteRequest;
import com.gb.wallet.domain.exchange.dto.response.ExchangeListResponse;
import com.gb.wallet.domain.exchange.dto.response.ExchangeResponse;
import com.gb.wallet.domain.exchange.dto.response.QuoteResponse;
import com.gb.wallet.domain.exchange.dto.response.SupportedCurrenciesResponse;

public interface ExchangeService {

    /** 환전·재환전에서 사용 가능한 지원 통화 목록을 반환한다. */
    SupportedCurrenciesResponse getSupportedCurrencies();

    /** 실시간 환율로 예상 수령액을 계산해 견적을 발급한다(Redis에 TTL 저장). */
    QuoteResponse createQuote(String userPublicId, QuoteRequest request);

    /** 견적 식별자로 환전을 실행하고 지갑 잔액을 갱신한다. */
    ExchangeResponse execute(String userPublicId, String idempotencyKey, ExchangeExecuteRequest request);

    /** 환전 완료 내역 단건을 조회한다(본인 것만). */
    ExchangeResponse getExchange(String userPublicId, String exchangePublicId);

    /** 회원의 환전 완료 내역을 페이지로 조회한다(본인 것만, 최근순). */
    ExchangeListResponse getExchanges(String userPublicId, int page, int size);

    /**
     * 트랜잭션 경계 안에서 실제 잔액 변경·거래 기록을 수행한다.
     * self-proxy 호출용으로 인터페이스에 노출한다(@Transactional 프록시 적용 — TransferService 동일 패턴).
     */
    ExchangeResponse executeInTransaction(String userPublicId, String idempotencyKey, QuoteData quote);

    /**
     * 동시 race(멱등 키 UNIQUE 위반) 시 별도 트랜잭션으로 첫 거래를 재조회한다. self-proxy 호출용.
     * {@code idempotency_key}는 전역 UNIQUE(도메인·사용자 무관)라, 재조회한 거래가 요청자 본인의 EXCHANGE인지
     * 검증한 뒤 반환한다(교차 사용자/유형 노출 차단 — 충전 {@code readPrior}와 동일 정책).
     */
    ExchangeResponse readPrior(String userPublicId, String idempotencyKey);
}
