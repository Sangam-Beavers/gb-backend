# 송금 · 환전 · 충전 — 처리 흐름 (Flow)

> 정본 명세: [`api-spec.md`](./api-spec.md)

---

## 1. 메인 화면 조회 흐름

```
[메인 진입]
├─ 주머니 잔액         GET /api/v1/wallets/me/balances
├─ 원화 환산(지금 나의 원화) GET /api/v1/wallets/me
└─ 실시간 환율         GET /api/v1/wallets/exchange-rates
```

---

## 2. 충전(가져오기) 흐름

```
[가져오기]
등록된 내 계좌 목록  GET /api/v1/accounts
  │
  ├─ (계좌 없음/추가) ───────────────────────────────┐
  │                                                  │
  │   [계좌 등록 프로세스]                            │
  │   지원 은행 목록  GET /api/v1/accounts/supported-banks
  │   예금주 실명 조회 GET /api/v1/accounts/holder?bankCode=&accountNumber=
  │   계좌 연결+자동이체 인증 요청 POST /api/v1/accounts/verify
  │     → Mock 은행: POST /api/v1/bank/accounts/verify → account_token 수신
  │     (※ 실제 인증 불가 → Mock/화면만)
  │   계좌 등록 최종 완료  POST /api/v1/accounts
  │     → bank_accounts INSERT (mock_account_token = 받은 토큰 저장)
  └──────────────────────────────────────────────────┘
  │
[충전 실행]
출금 계좌 선택 → 금액 입력
충전 금액 검증 및 실행  POST /api/v1/accounts/{id}/charge
  (Header: Idempotency-Key)
  → 본체: bank_accounts에서 mock_account_token 조회
  → Mock 은행: POST /api/v1/bank/transfers/withdrawal
       (account_token, amount, currency_code) → 외부계좌 차감, COMPLETED
  → 본체: 주머니 KRW +P (Mock 성공 응답이 트리거)
  → transactions(type=CHARGE) + audit_log INSERT
  → 201 { public_id, amount, wallet_balance, status=COMPLETED }
```

**충전 실패/한도**: 계좌 잔액 부족 `ACCOUNT4003`, 미인증 계좌 `ACCOUNT4006`, 한도 초과 `ACCOUNT4007`.

---

## 3. 송금(보내기) 흐름

```
[보내기] → 방식 선택
│
├── (A) 앱 사용자 송금 (INTERNAL_TRANSFER)
│     최근 송금 사용자  GET /api/v1/transfers/recent-recipients/members
│     사용자 검증       GET /api/v1/transfers/validate-member?email=
│     금액 입력
│
└── (B) 타행 송금 (REMITTANCE)
      최근 송금 계좌     GET /api/v1/transfers/recent-recipients/accounts
      지원 은행 목록     GET /api/v1/transfers/supported-banks
      예금주 실명 조회   GET /api/v1/transfers/account-holder?bankCode=&accountNumber=
      금액·통화 입력

[공통 사전 단계]
수수료 조회       POST /api/v1/transfers/fee      (또는 GET /transfers/fees — 정본 확인)
송금 비밀번호 검증 POST /api/v1/transfers/verify-password
        │
[송금 실행] — 1단계 즉시 실행
송금 실행  POST /api/v1/transfers   (Header: Idempotency-Key)
  → @Transactional:
      수수료·수취금액 계산
      → 잔액 검증 (WALLET4002 부족 시 실패)
      → wallet_balances 차감
      → transactions INSERT
      → transaction_audit_logs INSERT(before/after_balance)
      → 커밋
  → 201 { public_id, amount, fee, exchange_rate, receive_amount, status=COMPLETED }
        │
송금 확인증  GET /api/v1/transfers/{id}/receipt
```

> **이중 송금 방지:** 동일 `Idempotency-Key` 재요청 시 첫 결과(2xx) 그대로 재반환. Redis 분산 락 `lock:user:{userPublicId}` 병행.

### 정기 송금
```
대상 유효성 검증  GET /api/v1/transfers/scheduled/validate
지원 통화 조회    GET /api/v1/transfers/scheduled/supported-currencies
정기 송금 설정    POST /api/v1/transfers/scheduled
내역 조회         GET /api/v1/transfers/scheduled
진행 완료 조회    GET /api/v1/transfers/scheduled/{id}/history
```

---

## 4. 환전 흐름 (견적 → 실행 2단계)

```
[환전]
지원 (재)환전 통화   GET /api/v1/exchanges/supported-currencies  (환전·재환전 공통 단일 엔드포인트)
  │
견적 조회·검증      POST /api/v1/exchanges/quote
  Body: exchange_type(EXCHANGE/RE_EXCHANGE), from_currency_code, to_currency_code, amount
  → 200 { quote_public_id, exchange_rate, fee, receive_amount, expires_at }
  │
[실행]
환전 실행          POST /api/v1/exchanges   (Header: Idempotency-Key)
  Body: quote_public_id
  → 견적 만료 검사(EXCHANGE4002) → 잔액 검증(WALLET4002)
  → wallet_balances 갱신 (from 통화 차감 / to 통화 증가)
  → transactions(type=EXCHANGE) + audit_log
  → 201 { public_id, exchange_type, from/to_currency_code, amount, exchange_rate, fee, receive_amount, status=COMPLETED, exchanged_at }
  │
완료 내역 조회      GET /api/v1/exchanges/{id}
내역 목록          GET /api/v1/exchanges
```

---

## 5. 마이페이지 연계 조회

```
주머니 거래내역  GET /api/v1/wallets/me/transactions
환전 내역 목록    GET /api/v1/exchanges
```

---

## 6. 상태/예외 처리 포인트

- 견적 만료(`expires_at` 경과) 후 실행 → `EXCHANGE4002`.
- 잔액 부족 → `WALLET4002`.
- 미지원 통화 → `TRANSFER4002`.
- 충전 시 Mock 계좌 잔액 부족 → `ACCOUNT4003`.
- 모든 실행 계열은 `201 Created` + 멱등성 헤더.
