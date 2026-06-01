# 송금 · 환전 · 충전 — API 명세 (정본)

> Notion 개별 상세 명세 기반 정본. 전역 규칙은 [`../conventions.md`](../conventions.md).
> 모든 성공 응답은 `{ "success": true, "data": {...}, "message": "..." }` 래퍼. 아래 표 필드는 `data` 내부.
> **금액·환율은 모두 `string` 십진수.** 실행 계열(POST 생성)은 `201` + `Idempotency-Key` 헤더.

---

## 엔드포인트 목록

### 전자지갑 (/wallets)
| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 잔액 조회 | GET | `/api/v1/wallets/me/balances` | ✅ |
| 원화 환산 조회 (지금 나의 원화) | GET | `/api/v1/wallets/me` | ✅ |
| 거래내역 조회 | GET | `/api/v1/wallets/me/transactions` | ✅ |
| 주요 통화 환율 조회 | GET | `/api/v1/wallets/exchange-rates` | ✅ |
| 이상거래 탐지 알림 | GET | `/api/v1/wallets/me/fraud-alerts` | ✅ |

### 송금 (/transfers)
| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 최근 송금 앱 사용자 | GET | `/api/v1/transfers/recent-recipients/members` | ✅ |
| 앱 사용자 유효성 검증 | GET | `/api/v1/transfers/validate-member?email={}` | ✅ |
| 지원 통화 조회 | GET | `/api/v1/transfers/supported-currencies` | ✅ |
| 최근 송금 계좌 | GET | `/api/v1/transfers/recent-recipients/accounts` | ✅ |
| 지원 은행 목록 | GET | `/api/v1/transfers/supported-banks` | ✅ |
| 예금주 실명 조회 | GET | `/api/v1/transfers/account-holder?bankCode={}&accountNumber={}` | ✅ |
| 송금 수수료 조회 | POST | `/api/v1/transfers/fee` | ✅ |
| 송금 비밀번호 검증 | POST | `/api/v1/transfers/verify-password` | ✅ |
| FDS 검증 | POST | `/api/v1/transfers/fds-check` | ✅ |
| **송금 실행** | POST | `/api/v1/transfers` | ✅ |
| 송금 확인증 조회 | GET | `/api/v1/transfers/{id}/receipt` | ✅ |
| 정기 송금 대상 검증 | GET | `/api/v1/transfers/scheduled/validate` | ✅ |
| 정기 송금 지원 통화 | GET | `/api/v1/transfers/scheduled/supported-currencies` | ✅ |
| 정기 송금 설정 | POST | `/api/v1/transfers/scheduled` | ✅ |
| 정기 송금 내역 조회 | GET | `/api/v1/transfers/scheduled` | ✅ |
| 정기 송금 진행 완료 조회 | GET | `/api/v1/transfers/scheduled/{id}/history` | ✅ |

### 환전 (/exchanges)
| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 지원 환전 통화 | GET | `/api/v1/exchanges/supported-currencies` | ✅ |
| 지원 재환전 통화 | GET | `/api/v1/exchanges/re-exchange/supported-currencies` | ✅ |
| 견적 조회·검증 | POST | `/api/v1/exchanges/quote` | ✅ |
| 환전 실행 | POST | `/api/v1/exchanges` | ✅ |
| 환전 완료 내역 조회 | GET | `/api/v1/exchanges/{id}` | ✅ |
| 환전 내역 목록 조회 | GET | `/api/v1/exchanges` | ✅ |

### 계좌/충전 (/accounts)
| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 등록된 내 계좌 목록 | GET | `/api/v1/accounts` | ✅ |
| 지원 은행 목록 | GET | `/api/v1/accounts/supported-banks` | ✅ |
| 예금주 실명 조회 | GET | `/api/v1/accounts/holder?bankCode={}&accountNumber={}` | ✅ |
| 계좌 연결+자동이체 인증 요청 | POST | `/api/v1/accounts/verify` | ✅ |
| 계좌 등록 최종 완료 | POST | `/api/v1/accounts` | ✅ |
| 충전 금액 검증·실행 | POST | `/api/v1/accounts/{id}/charge` | ✅ |
| 주 계좌 변경 | PATCH | `/api/v1/accounts/{id}/primary` | ✅ |
| 계좌 삭제 | DELETE | `/api/v1/accounts/{id}` | ✅ |

> ⚠️ 경로 표기 충돌 메모: CSV 작업표에는 일부 GET이 POST(예: 수수료, 환전 견적)로, 일부 경로가 다르게(`fee`/`fees`, `validate-member`/`receivers/search`) 적혀 있다. **위 표는 개별 상세 명세(정본) 기준**이다. 수수료는 개별 명세상 `POST /transfers/fee`, 환전 견적은 `POST /exchanges/quote`로 확정.

---

## 1. 전자지갑 잔액 조회

`GET /api/v1/wallets/me/balances` · Auth ✅

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `wallet_public_id` | string | N | 지갑 UUID |
| `status` | string | N | ACTIVE / SUSPENDED / CLOSED |
| `balances` | array | N | 통화별 잔액 목록 |
| `balances[].currency_code` | string | N | KRW/USD/PHP/VND |
| `balances[].balance` | string | N | 잔액 (string 십진수) |
| `updated_at` | string | N | ISO 8601 UTC Z |

**Error**: 401 COMMON4011 / 404 WALLET4001

---

## 2. 원화 환산 조회 (지금 나의 원화)

`GET /api/v1/wallets/me` · Auth ✅

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `wallet_public_id` | string | N | 지갑 UUID |
| `status` | string | N | ACTIVE / SUSPENDED / CLOSED |
| `total_balance_in_krw` | string | N | 전체 보유 통화의 원화 환산 합계 |
| `balances` | array | N | 통화별 잔액·환산 목록 |
| `balances[].currency_code` | string | N | 통화 코드 |
| `balances[].balance` | string | N | 잔액 |
| `balances[].exchange_rate` | string | N | "1 외화→KRW" 고정. KRW는 "1" |
| `balances[].balance_in_krw` | string | N | 원화 환산액 |
| `updated_at` | string | N | ISO 8601 UTC Z |

**Error**: 401 COMMON4011 / 404 WALLET4001

---

## 3. 주요 통화 환율 / 거래내역 / 이상거래 알림

- 환율 조회: `GET /api/v1/wallets/exchange-rates` → 통화별 환율 목록(+표시용 등락률 `change_rate`는 number 허용). 환율 값 자체는 string.
- 거래내역: `GET /api/v1/wallets/me/transactions?page=&size=` → 페이지네이션 (배열 키 `transactions`).
- 이상거래 알림: `GET /api/v1/wallets/me/fraud-alerts` → FDS 경고 카드 목록.

---

## 4. 송금 수수료 조회

`POST /api/v1/transfers/fee` · Auth ✅

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `transfer_type` | string | O | INTERNAL_TRANSFER / REMITTANCE |
| `currency_code` | string | O | KRW/USD/PHP/VND |
| `amount` | string | O | 송금 금액 (string 십진수) |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `fee` | string | N | 수수료 (string 십진수, 소수점 4자리) |
| `fee_currency_code` | string | N | 수수료 통화 (송금 통화와 동일) |
| `total_deduct_amount` | string | N | 총 차감 금액 (amount + fee, string 십진수, 소수점 4자리) |

**Error**: 400 COMMON4001 (Body 검증 실패) / 400 TRANSFER4002 (미지원 통화) / 400 TRANSFER4003 (미지원 송금 유형) / 401 COMMON4011

### 수수료 정책 (임시 — 실제 정책 확정 시 교체)
- `INTERNAL_TRANSFER`: 무료 (`fee = 0`)
- `REMITTANCE`: `amount × 0.5%` (소수점 4자리, `RoundingMode.HALF_UP`)
- `fee_currency_code`: 송금 통화(`currency_code`)와 동일
- `total_deduct_amount`: `amount + fee`

**예시:**
- `REMITTANCE`, `KRW`, `amount=10000.0000` → `fee=50.0000`, `total_deduct_amount=10050.0000`
- `INTERNAL_TRANSFER`, `KRW`, `amount=10000.0000` → `fee=0.0000`, `total_deduct_amount=10000.0000`

> ⚠️ 위 비율(0.5%)은 명세 확정 전 임시 값이다. 실제 정책 확정 시 정책 테이블/외부 조회로 교체될 수 있다.

> 앱 내 송금(INTERNAL_TRANSFER)은 수수료 무료, 타행(REMITTANCE)은 수수료 발생.
---

## 5. 송금 사전 검증

- 앱 사용자 검증: `GET /api/v1/transfers/validate-member?email={}` → `data: { receiver_public_id, nickname, is_verified }`
- 예금주 실명 조회: `GET /api/v1/transfers/account-holder?bankCode={}&accountNumber={}` → `data: { account_holder_name }`
- 송금 비밀번호 검증: `POST /api/v1/transfers/verify-password` (Body: `password`) → 200/실패
- FDS 검증: `POST /api/v1/transfers/fds-check` → `data: { passed: true/false, ... }`. 차단 시 송금 실행 진입 차단.

---

## 6. 송금 실행 ★

`POST /api/v1/transfers` · Auth ✅ · **1단계 즉시 실행**

> ⚠️ **구현 단계 (점진 확장):**
> - **1단계 (현재 작업)**: INTERNAL_TRANSFER + 같은 통화 송금만.
    >   `currency_code == receive_currency_code` 필수. `exchange_rate=null`, `receive_amount=amount`.
> - **2단계 (후속)**: REMITTANCE 추가 (BankClient.payout 호출).
> - **3단계 (후속)**: 다통화 송금 (환율 적용 — `currency_code != receive_currency_code`).

**Request Header**
| 헤더 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `Idempotency-Key` | string | O | 멱등성 키(UUID). 재시도 중복 방지. 동일 키 재요청 시 첫 결과(2xx) 그대로 재반환 |
| `X-User-Public-Id` | string | O | 송신자 식별 (인증 구현 전 임시). 인증 구현 후 JWT sub로 교체 |

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `transfer_type` | string | O | INTERNAL_TRANSFER / REMITTANCE. 1단계는 INTERNAL_TRANSFER만 지원 |
| `amount` | string | O | 송금 금액 (출금 통화 기준, string 십진수). 양수 |
| `currency_code` | string | O | 출금 통화 (KRW/USD/PHP/VND) |
| `receive_currency_code` | string | O | 수취 통화. 1단계는 `currency_code`와 동일해야 함. 다르면 TRANSFER4005 |
| `memo` | string | X | 메모 (255자 이내) |
| `receiver_public_id` | string | △ | 수취 회원 UUID. INTERNAL_TRANSFER 시 필수 |
| `bank_account_public_id` | string | △ | 수취 계좌 UUID. REMITTANCE 시 필수 (2단계) |

**Response 201** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 거래 UUID |
| `transfer_type` | string | N | INTERNAL_TRANSFER / REMITTANCE |
| `amount` | string | N | 송금 금액 (string 십진수) |
| `currency_code` | string | N | 출금 통화 |
| `fee` | string | N | 수수료 (string 십진수, 소수점 4자리). INTERNAL_TRANSFER=0 |
| `exchange_rate` | string | Y | 적용 환율. **1단계는 항상 null** (같은 통화 송금) |
| `receive_amount` | string | N | 수취 금액. 1단계는 amount와 동일 |
| `receive_currency_code` | string | N | 수취 통화. 1단계는 currency_code와 동일 |
| `status` | string | N | COMPLETED |
| `created_at` | string | N | ISO 8601 UTC Z |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (Body 검증 실패) |
| 400 | WALLET4002 | 지갑 잔액이 부족합니다. |
| 400 | TRANSFER4002 | 지원하지 않는 통화입니다. |
| 400 | TRANSFER4003 | 지원하지 않는 송금 유형입니다. (1단계는 INTERNAL_TRANSFER만) |
| 400 | TRANSFER4004 | 자기 자신에게 송금할 수 없습니다. |
| 400 | TRANSFER4005 | 지원하지 않는 통화 조합입니다. (1단계는 같은 통화만) |
| 401 | COMMON4011 | 인증 정보가 유효하지 않습니다. |
| 404 | WALLET4001 | 존재하지 않는 지갑입니다. (송신자/수신자 wallet 부재, 또는 송신자 통화 잔액 행 부재 — 송신자 잔액 행은 자동 생성하지 않음) |
| 503 | COMMON5031 | 일시적으로 처리할 수 없습니다. (분산 락 획득 실패) |

> **수신자 잔액 행 자동 생성** — 수신자가 지갑은 있으나 해당 통화 잔액 행이 없으면 송금 시점에 0원 행을 자동 생성한 뒤 입금한다(`WalletBalanceWriter.ensureBalanceRow` — `REQUIRES_NEW`로 독립 커밋, `uk_wallet_balances_wallet_currency` UNIQUE로 동시 생성 race 흡수). 따라서 미보유 통화 수신 요청도 성공한다. **송신자**는 자동 생성하지 않으며(돈이 있어야 보내는 게 정상), 잔액 행 부재 시 `WALLET4001`. 수신자 행이 자동 생성 직후에도 없는 경우 정합성 불변식 위반으로 `COMMON5000`(정상 흐름에서 발생 불가 — 방어).

### 6-1. 멱등성 처리 (3-layer)

동일 `Idempotency-Key` 재요청 시 에러 없이 첫 결과(2xx)를 그대로 재반환한다.

1. **Redis 캐시 조회** — `idempotency:{key}` → 있으면 그대로 반환 (빠른 경로, TTL 24h)
2. **DB 조회** — `transactions.idempotency_key`로 조회 → 있으면 응답 재구성 + Redis 캐시 후 반환
3. **DB UNIQUE 위반** — 동시 race로 다른 트랜잭션이 먼저 INSERT 시 `DataIntegrityViolationException` catch → 별도 트랜잭션으로 첫 결과 조회 + 반환

### 6-2. 분산 락 정책 (데드락 회피)

INTERNAL_TRANSFER는 송신자/수신자 두 잔액 행을 동시에 잠그므로 데드락 위험이 있다. **Resource Ordering** 패턴으로 `wallet_id` 오름차순으로 일관 락 획득.

1. **Redis 분산 락** (Redisson MultiLock):
    - 키: `lock:wallet:{walletId}` 두 개
    - `wallet_id` 오름차순으로 묶어 `tryLock(waitTime=3s, leaseTime=5s)`
    - 실패 시 `COMMON5031`
2. **DB 비관적 락** (Redis 락 내부):
    - `WalletBalanceRepository.findForUpdateByWalletAndCurrency` × 2
    - 같은 순서(`wallet_id` 오름차순)로 획득
3. 잔액 변경 → 트랜잭션 INSERT × 1 + audit log INSERT × 2 → 커밋 → Redis 락 해제

> 두 단계 락은 의도적 중복이다. Redis는 다중 인스턴스 환경 분산 보호, DB는 같은 인스턴스 내 직렬화. 둘 다 동일 순서로 획득해야 안전.

### 6-3. Audit Log (두 건 — INTERNAL_TRANSFER)

송신자/수신자 각각 잔액 변화를 별도 행으로 기록 (감사 완전성).

| action | user_public_id | before_balance | after_balance | amount |
| --- | --- | --- | --- | --- |
| `INTERNAL_TRANSFER_SEND` | sender | sender 변경 전 | sender 변경 후 | amount + fee (차감액) |
| `INTERNAL_TRANSFER_RECEIVE` | receiver | receiver 변경 전 | receiver 변경 후 | amount (수령액) |

> 두 행 모두 같은 `transaction_id` 참조. 같은 `@Transactional` 안에서 INSERT.

> 사전 흐름: verify-password → fds-check → 본 API (1단계는 verify-password/fds-check 미구현, 별도 작업).
---

## 7. 송금 확인증 / 정기 송금

- 확인증: `GET /api/v1/transfers/{id}/receipt` → 적용 환율·수수료 등 상세.
- 정기 송금: `validate`(대상 검증, GET) → `supported-currencies`(GET) → `scheduled`(설정 POST / 내역 GET) → `scheduled/{id}/history`(진행 완료 GET).

---

## 8. 환전 견적 조회·검증

`POST /api/v1/exchanges/quote` · Auth ✅

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `exchange_type` | string | O | EXCHANGE(원화→외화) / RE_EXCHANGE(외화→원화) |
| `from_currency_code` | string | O | 출금 통화 (ISO 4217) |
| `to_currency_code` | string | O | 입금 통화 (ISO 4217) |
| `amount` | string | O | 환전 신청 금액 |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `quote_public_id` | string | N | 견적 UUID (실행 시 사용) |
| `exchange_rate` | string | N | "1 외화→KRW" |
| `fee` | string | N | 수수료 (KRW 기준) |
| `fee_currency_code` | string | N | 수수료 통화 |
| `receive_amount` | string | N | 예상 수령액 |
| `receive_currency_code` | string | N | 수령 통화 |
| `expires_at` | string | N | 견적 만료 시각 (ISO 8601 UTC Z) |

**Error**: 400 TRANSFER4002 (미지원 통화) / 401 COMMON4011 / 422 WALLET4002 (잔액 부족)

---

## 9. 환전 실행 ★

`POST /api/v1/exchanges` · Auth ✅ · Header `Idempotency-Key`

**Request Body**: `quote_public_id`(string, O)

**Response 201** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 환전 내역 UUID |
| `exchange_type` | string | N | EXCHANGE / RE_EXCHANGE |
| `from_currency_code` | string | N | |
| `to_currency_code` | string | N | |
| `amount` | string | N | 신청 금액 |
| `exchange_rate` | string | N | "1 외화→KRW" |
| `fee` | string | N | 수수료 (KRW 기준) |
| `receive_amount` | string | N | 실제 수령액 |
| `receive_currency_code` | string | N | |
| `status` | string | N | COMPLETED |
| `exchanged_at` | string | N | ISO 8601 UTC Z |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | EXCHANGE4002 | 환율 견적이 만료되었습니다. |
| 401 | COMMON4011 | 인증 정보가 유효하지 않습니다. |
| 404 | EXCHANGE4001 | 존재하지 않는 환전 내역입니다. |
| 422 | WALLET4002 | 지갑 잔액이 부족합니다. |

---

## 10. 환전 내역 조회

- 완료 내역 단건: `GET /api/v1/exchanges/{id}`
- 내역 목록: `GET /api/v1/exchanges?page=&size=` (배열 키 `exchanges`)

---

## 11. 계좌 등록 (충전 준비)

- 목록: `GET /api/v1/accounts` → `data: { accounts: [...] }`
- 지원 은행: `GET /api/v1/accounts/supported-banks`
- 예금주 실명 조회: `GET /api/v1/accounts/holder?bankCode={}&accountNumber={}`
- 계좌 연결+자동이체 인증 요청: `POST /api/v1/accounts/verify` (※ Mock/화면용. 실제 인증 미구현)
- 계좌 등록 최종 완료: `POST /api/v1/accounts` → 201, `bank_accounts` INSERT
- 주 계좌 변경: `PATCH /api/v1/accounts/{id}/primary`
- 계좌 삭제: `DELETE /api/v1/accounts/{id}`

**계좌 에러 코드**: ACCOUNT4001(없음) / ACCOUNT4002(인증 실패) / ACCOUNT4004(이미 등록, 409) / ACCOUNT4005(인증 요청 초과, 429) / ACCOUNT4006(미인증 계좌, 403)

---

## 12. 충전 금액 검증·실행 ★

`POST /api/v1/accounts/{id}/charge` · Auth ✅ · Header `Idempotency-Key`

**Path Variable**: `id` = 출금 계좌 public_id (UUID)

**Request Body**: `amount`(string, O) — 충전 금액

**Response 201** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 충전 거래 UUID |
| `account_public_id` | string | N | 출금 계좌 UUID |
| `amount` | string | N | 충전 금액 |
| `currency_code` | string | N | KRW 고정 |
| `wallet_balance` | string | N | 충전 후 지갑 잔액 |
| `status` | string | N | COMPLETED |
| `created_at` | string | N | ISO 8601 UTC Z |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. |
| 400 | ACCOUNT4003 | 연동 계좌의 잔액이 부족합니다. |
| 401 | COMMON4011 | 인증 정보가 유효하지 않습니다. |
| 403 | ACCOUNT4006 | 인증되지 않은 계좌입니다. |
| 404 | ACCOUNT4001 | 존재하지 않는 계좌입니다. |
| 404 | WALLET4001 | 존재하지 않는 지갑입니다. (계좌는 있으나 해당 회원의 지갑이 없는 방어 케이스) |
| 422 | ACCOUNT4007 | 충전 한도를 초과했습니다. |
| 503 | COMMON5031 | 일시적으로 처리할 수 없습니다. |

> 구현 수준 2(Mock): 실제 출금은 Mock 은행(Beaver/Quokka Bank) 응답으로 시뮬레이션. 충전 처리는 `@Transactional`에서 [잔액 조회→검증→증액→audit_log INSERT→커밋].

---

## 13. Mock 은행 연동 (구현 수준 2)

> 충전·현금화 시 본체(`com.gb.wallet`)가 **외부 Mock 은행 서버**(Beaver/Quokka Bank)를 HTTP 클라이언트로 호출한다.
> 이 서버는 본체와 **장부(DB)가 완전히 분리**돼 있다 — 본체 MySQL(`wallet_balances`)은 앱 포인트, Mock 은행 SQLite(`bank_accounts`)는 외부 현금. 서로의 DB를 직접 만지지 않는다.
> Mock 은행 측 명세 정본은 **mock-bank 레포의 `API-SPEC.md`**. 본 섹션은 본체 관점의 연동 계약이다.
> 실서비스 전환 시 호출 URL만 실제 PG/은행 API로 교체하면 본체 로직은 그대로 동작한다.

### 13-1. 본체 API ↔ Mock 은행 엔드포인트 매핑

| 본체 API | 내부에서 호출하는 Mock 은행 | 방향 |
| --- | --- | --- |
| `GET /accounts/holder` (예금주 실명조회) | `POST /api/v1/bank/accounts/inquiry` | 조회 |
| `POST /accounts/verify` (계좌 인증) | `POST /api/v1/bank/accounts/verify` → `account_token` 수신 | 인증 |
| `POST /accounts/{id}/charge` (충전 실행) | `POST /api/v1/bank/transfers/withdrawal` (저장해둔 `mock_account_token` 사용) | **외부계좌 차감** |
| 현금화 실행 (REMITTANCE 출금) | `POST /api/v1/bank/transfers/payout` | **외부계좌 증액** |

> **충전 = 출금(외부계좌 ↓), 현금화 = 지급(외부계좌 ↑).** 방향이 정반대다.
> Mock 은행 통신 금액은 모두 **string 십진수**, 실행 계열(withdrawal/payout)은 **`Idempotency-Key` 헤더**(동일 키 재요청 시 첫 응답 재반환).

### 13-2. 계좌 등록 → 충전 토큰 흐름

```
[계좌 등록]
POST /accounts/verify
  → Mock: POST /bank/accounts/verify { bank_code, account_number, holder_name }
  → Mock 응답: { account_token, ... }
POST /accounts (등록 확정)
  → bank_accounts INSERT, mock_account_token = 받은 account_token 저장

[충전 실행]
POST /accounts/{id}/charge { amount }
  → 본체: bank_accounts에서 mock_account_token 조회
  → Mock: POST /bank/transfers/withdrawal
          Header Idempotency-Key
          Body { account_token, amount, currency_code }
  → Mock 응답 COMPLETED → 본체 @Transactional:
     wallet_balances 증액 → transaction_audit_logs INSERT → 커밋
```

> 토큰이 없는(미인증) 계좌는 충전 불가 → `ACCOUNT4006`.
> Mock `withdrawal` 응답의 `balance_after`는 외부 계좌 잔액일 뿐, 본체 주머니 잔액과 무관하다.

### 13-3. 현금화(지급) 흐름

```
현금화 실행 (사용자가 포인트를 외부 계좌 현금으로)
  → 본체 @Transactional: wallet_balances 차감(또는 차감 예약)
  → Mock: POST /bank/transfers/payout
          Header Idempotency-Key
          Body { bank_code, account_number, amount, currency_code }
          (amount는 본체가 이미 환전 완료한 최종 외화 금액)
  → Mock 응답 COMPLETED → 본체 차감 확정 + audit_log INSERT → 커밋
```

> 환율은 **본체가 환전 시점에 적용**하고, Mock 은행에는 최종 외화 금액만 넘긴다. Mock은 환율을 모른다.

### 13-4. Mock 은행 에러 → 본체 에러 매핑

Mock 은행은 외부 시스템이라 자체 코드(`BANK####`)를 쓴다. 본체는 이를 받아 자기 도메인 코드로 변환해 사용자에게 응답한다.

| Mock 은행 코드 | HTTP | 본체 변환 |
| --- | --- | --- |
| `BANK4002` (출금 잔액부족) | 400 | `ACCOUNT4003` (연동 계좌의 잔액이 부족합니다) |
| `BANK4040` (계좌 없음) | 404 | `ACCOUNT4001` (존재하지 않는 계좌입니다) |
| `BANK4003` (예금주 불일치) | 400 | `ACCOUNT4002` (계좌 인증에 실패했습니다) |
| `BANK4010` (유효하지 않은 토큰) | 401 | `ACCOUNT4006` (인증되지 않은 계좌입니다) |
| `BANK4004` (통화 불일치) | 400 | `COMMON4001` (요청 값이 올바르지 않습니다) |
| `BANK5000` / 타임아웃 / 연결 실패 | 500 | `COMMON5031` (일시적으로 처리할 수 없습니다) |

### 13-5. BankClient 인터페이스 (구현체 교체 지점)

본체는 Mock 은행을 직접 호출하지 않고 인터페이스에 의존한다. 실서비스 전환 시 구현체만 교체한다.

```java
public interface BankClient {
    AccountHolder    inquiry(String bankCode, String accountNumber);                 // 예금주 조회
    AccountToken     verify(String bankCode, String accountNumber, String holder);   // 계좌 인증 → token
    WithdrawalResult withdraw(String accountToken, BigDecimal amount,
                              String currencyCode, String idempotencyKey);           // 충전 출금
    PayoutResult     payout(String bankCode, String accountNumber, BigDecimal amount,
                            String currencyCode, String idempotencyKey);             // 현금화 지급
}

@Profile({"dev","stage"}) @Component
class MockBankClient implements BankClient { /* Mock 은행 서버 호출 */ }

@Profile("prod") @Component
class RealBankClient implements BankClient { /* 토스페이먼츠/Vietcombank 등 */ }
```

- 비즈니스 로직(`WalletService`)은 `BankClient`만 의존 → 구현체 교체 시 로직 무변경.
- Mock 은행 base URL은 환경변수 `BANK_API_BASE_URL`로 분리 (학원/홈서버/실서비스 간 URL만 교체).
- **mTLS:** Mock 은행이 `TLS_ENABLED=true`이면 본체는 클라이언트 인증서(keystore.p12) + truststore로 상호 인증. 키스토어 경로도 환경변수로 분리. (개발 초기엔 `TLS_ENABLED=false` 평문으로 흐름 검증)
