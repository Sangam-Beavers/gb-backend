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

**Response 200** — `data`: `fee`(string), `fee_currency_code`(string), `total_deduct_amount`(string, amount+fee)

**Error**: 400 TRANSFER4002 (미지원 통화) / 401 COMMON4011

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

**Request Header**
| 헤더 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `Idempotency-Key` | string | O | 멱등성 키(UUID). 재시도 중복 방지 |

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `transfer_type` | string | O | INTERNAL_TRANSFER / REMITTANCE |
| `amount` | string | O | 송금 금액 (출금 통화 기준) |
| `currency_code` | string | O | 출금 통화 (KRW/USD/PHP/VND) |
| `receive_currency_code` | string | △ | 수취 통화. 통화 다른 송금 시 필수 |
| `memo` | string | X | 메모 |
| `receiver_public_id` | string | △ | 수취 회원 UUID. INTERNAL_TRANSFER 시 필수 |
| `bank_account_public_id` | string | △ | 수취 계좌 UUID. REMITTANCE 시 필수 |

**Response 201** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 거래 UUID |
| `transfer_type` | string | N | INTERNAL_TRANSFER / REMITTANCE |
| `amount` | string | N | 송금 금액 |
| `currency_code` | string | N | 출금 통화 |
| `fee` | string | N | 수수료 |
| `exchange_rate` | string | Y | "1 외화→KRW". 통화 같으면 null |
| `receive_amount` | string | N | 수취 금액 |
| `receive_currency_code` | string | N | 수취 통화 |
| `status` | string | N | COMPLETED |
| `created_at` | string | N | ISO 8601 UTC Z |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. |
| 400 | WALLET4002 | 지갑 잔액이 부족합니다. |
| 400 | TRANSFER4002 | 지원하지 않는 통화입니다. |
| 401 | COMMON4011 | 인증 정보가 유효하지 않습니다. |
| 404 | WALLET4001 | 존재하지 않는 지갑입니다. |
| 404 | MEMBER4001 | 존재하지 않는 회원입니다. |
| 404 | ACCOUNT4001 | 존재하지 않는 계좌입니다. |

> 멱등성(§12-2-2): 동일 키 재요청 시 에러 없이 첫 결과(2xx) 재반환.
> 사전 흐름: verify-password → fds-check → 본 API.

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
| 422 | ACCOUNT4007 | 충전 한도를 초과했습니다. |
| 503 | COMMON5031 | 일시적으로 처리할 수 없습니다. |

> 구현 수준 2(Mock): 실제 출금은 Mock 은행(Beaver/Quokka Bank) 응답으로 시뮬레이션. 충전 처리는 `@Transactional`에서 [잔액 조회→검증→증액→audit_log INSERT→커밋].
