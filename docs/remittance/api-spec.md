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

### 송금 (/transfers)
| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 최근 송금 앱 사용자 | GET | `/api/v1/transfers/recent-recipients/members` | ✅ |
| 앱 사용자 유효성 검증 | GET | `/api/v1/transfers/validate-member?email={}` | ✅ |
| 지원 통화 조회 | GET | `/api/v1/transfers/supported-currencies` | ✅ |
| 최근 송금 계좌 | GET | `/api/v1/transfers/recent-accounts` | ✅ |
| 지원 은행 목록 (※ `/accounts/supported-banks`로 제공 — 별도 transfers 경로 미구현) | GET | `/api/v1/transfers/supported-banks` | ✅ |
| 송금 수수료 조회 | POST | `/api/v1/transfers/fee` | ✅ |
| 송금 PIN 설정 | POST | `/api/v1/transfers/pin` | ✅ |
| 송금 PIN 검증 | POST | `/api/v1/transfers/pin-verify` | ✅ |
| **송금 실행** | POST | `/api/v1/transfers` | ✅ |
| 송금 확인증 조회 | GET | `/api/v1/transfers/{id}/receipt` | ✅ |
| 정기 송금 대상 검증 | POST | `/api/v1/transfers/scheduled/validate` | ✅ |
| 정기 송금 설정 | POST | `/api/v1/transfers/scheduled` | ✅ |
| 정기 송금 내역 조회 | GET | `/api/v1/transfers/scheduled` | ✅ |
| 정기 송금 진행 완료 조회 | GET | `/api/v1/transfers/scheduled/{id}/history` | ✅ |

### 환전 (/exchanges)
| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 지원 (재)환전 통화 | GET | `/api/v1/exchanges/supported-currencies` | ✅ |
| 견적 조회·검증 | POST | `/api/v1/exchanges/quote` | ✅ |
| 환전 실행 | POST | `/api/v1/exchanges` | ✅ |
| 환전 완료 내역 조회 | GET | `/api/v1/exchanges/{id}` | ✅ |
| 환전 내역 목록 조회 | GET | `/api/v1/exchanges` | ✅ |

> 환전·재환전은 **별도 엔드포인트가 아니라** 견적/실행 요청의 `exchange_type`(EXCHANGE/RE_EXCHANGE) 파라미터로 구분한다(정본 §8/§9). 지원 통화 목록은 양방향 공통이므로 단일 `GET /supported-currencies` 하나가 커버한다.

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

**Error**: 401 AUTH4011 / 404 WALLET4001

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

**Error**: 401 AUTH4011 / 404 WALLET4001

---

## 3. 주요 통화 환율 / 거래내역

- 환율 조회: `GET /api/v1/wallets/exchange-rates` → 통화별 환율 목록(+표시용 등락률 `change_rate`는 number 허용). 환율 값 자체는 string.
- 거래내역: `GET /api/v1/wallets/me/transactions?page=&size=` · Auth ✅ → 본인 전 유형(CHARGE/INTERNAL_TRANSFER/REMITTANCE/EXCHANGE) 거래를 `created_at` DESC 페이지 조회. 거래가 없으면 200 + 빈 배열.

**Response 200** — `data` (페이지 메타 + `transactions` 배열)
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `transactions` | array | N | 거래 항목 배열(최근순). 비어 있으면 `[]` |
| `page` | number | N | 현재 페이지(0부터) |
| `size` | number | N | 페이지당 건수 |
| `total_elements` | number | N | 전체 건수 |
| `total_pages` | number | N | 전체 페이지 수 |

**`transactions[]` 항목** (현재 구현 `TransactionHistoryItemResponse` 기준)
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 거래 식별자(UUID) |
| `type` | string | N | CHARGE / INTERNAL_TRANSFER / REMITTANCE / EXCHANGE |
| `status` | string | N | PENDING / PROCESSING / COMPLETED / FAILED / CANCELLED |
| `amount` | string | N | 거래(출금) 금액 (string, 소수 4자리) |
| `currency_code` | string | N | 출금 통화 코드 |
| `fee` | string | N | 수수료 (string, 소수 4자리, 무료면 `0.0000`) |
| `receive_amount` | string | Y | 수령액 (환전·송금만) |
| `receive_currency_code` | string | Y | 수령 통화 코드 (환전·송금만) |
| `receiver_name` | string | Y | 수취인 이름 (송금만) |
| `created_at` | string | N | 거래 시각 (ISO 8601 UTC Z) |

**Error**: 400 COMMON4001 (`page<0` 또는 `size` 1~100 범위 위반) / 401 AUTH4011

> ⚠️ `transactions[]` 항목 필드는 **현재 구현 DTO 기준으로 정합**한 것이다(코드가 명세를 앞서 확정한 상태를 명문화). 팀 거래내역 와이어프레임 확정 시 유형별 노출 필드·마스킹이 조정될 수 있다.

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

**Error**: 400 COMMON4001 (Body 검증 실패) / 400 TRANSFER4002 (미지원 통화) / 400 TRANSFER4003 (미지원 송금 유형) / 401 AUTH4011

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
- 송금 PIN 설정: `POST /api/v1/transfers/pin` (Body: `{ "pin": "123456" }`, 숫자 6자리) → 201. 형식 오류 `COMMON4001`, 이미 설정됨 `COMMON4091`. (방식 B라 계정 비밀번호는 IdP가 보유 → 송금 본인확인은 별도 송금 PIN 6자리로 한다.)
- 송금 PIN 검증: `POST /api/v1/transfers/pin-verify` (Body: `{ "pin": "123456" }`) → 200. 불일치 `TRANSFER4007`, 미설정 `TRANSFER4009`, 5회 연속 실패 시 10분 잠금 `TRANSFER4008`(429). 성공해야 송금 실행(§6)으로 진행.

---

## 6. 송금 실행 ★

`POST /api/v1/transfers` · Auth ✅ · **1·2단계 즉시 실행**

> ⚠️ **구현 단계 (점진 확장):**
> - **1단계 (완료)**: INTERNAL_TRANSFER + 같은 통화 송금. `currency_code == receive_currency_code` 필수.
>   `exchange_rate=null`, `receive_amount=amount`.
> - **2단계 (완료)**: REMITTANCE 추가 (BankClient.payout 호출). **같은 통화만 지원** —
>   `currency_code == receive_currency_code` 강제 유지(다르면 TRANSFER4005). 다통화는 3단계로 이연.
> - **3단계 (후속)**: 다통화 송금 (환율 적용 — `currency_code != receive_currency_code`).

**Request Header**
| 헤더 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `Idempotency-Key` | string | O | 멱등성 키(UUID). 재시도 중복 방지. 동일 키 재요청 시 첫 결과(2xx) 그대로 재반환 |
| `X-User-Public-Id` | string | O | 송신자 식별 (인증 구현 전 임시). 인증 구현 후 JWT sub로 교체 |

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `transfer_type` | string | O | INTERNAL_TRANSFER / REMITTANCE 모두 지원(2단계 완료) |
| `amount` | string | O | 송금 금액 (출금 통화 기준, string 십진수). 양수 |
| `currency_code` | string | O | 출금 통화 (KRW/USD/PHP/VND) |
| `receive_currency_code` | string | O | 수취 통화. 1·2단계는 `currency_code`와 동일해야 함. 다르면 TRANSFER4005 (다통화는 3단계) |
| `memo` | string | X | 메모 (255자 이내) |
| `receiver_public_id` | string | △ | 수취 회원 UUID. INTERNAL_TRANSFER 시 필수 (DTO `@NotBlank`로 강제) |
| `bank_account_public_id` | string | △ | 수취 계좌 UUID. REMITTANCE 시 필수 (누락 시 COMMON4001) |

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
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (Body 검증 실패, REMITTANCE 시 `bank_account_public_id` 누락 포함) |
| 422 | WALLET4002 | 지갑 잔액이 부족합니다. (REMITTANCE는 amount + fee ≤ 송신자 잔액. 요청 형식은 정상이나 잔액 부족으로 처리 불가) |
| 400 | TRANSFER4002 | 지원하지 않는 통화입니다. |
| 400 | TRANSFER4003 | 지원하지 않는 송금 유형입니다. (INTERNAL_TRANSFER/REMITTANCE 외 — 예: CHARGE/EXCHANGE) |
| 400 | TRANSFER4004 | 자기 자신에게 송금할 수 없습니다. (INTERNAL_TRANSFER 한정) |
| 400 | TRANSFER4005 | 지원하지 않는 통화 조합입니다. (1·2단계는 같은 통화만) |
| 429 | TRANSFER4006 | 송금 요청 횟수를 초과했습니다. (user 단위 rate-limit, 기본 60초 / 30회) |
| 400 | ACCOUNT4002 | 계좌 인증에 실패했습니다. (REMITTANCE — Mock 은행 BANK4003 매핑) |
| 400 | ACCOUNT4003 | 연동 계좌의 잔액이 부족합니다. (REMITTANCE — Mock 은행 BANK4002 매핑) |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 403 | ACCOUNT4006 | 인증되지 않은 계좌입니다. (REMITTANCE — `mock_account_token` 미발급, 또는 Mock 은행 BANK4010 매핑) |
| 404 | ACCOUNT4001 | 존재하지 않는 계좌입니다. (REMITTANCE — 본인 + active 미매칭, 또는 Mock 은행 BANK4040 매핑) |
| 404 | WALLET4001 | 존재하지 않는 지갑입니다. (송신자/수신자 wallet 부재, 또는 송신자 통화 잔액 행 부재 — 송신자 잔액 행은 자동 생성하지 않음) |
| 500 | COMMON5000 | 서버 오류가 발생했습니다. (수신자 잔액 행 자동 생성 직후에도 조회되지 않는 정합성 불변식 위반 — 정상 흐름에서 발생 불가, 방어) |
| 503 | COMMON5031 | 일시적으로 처리할 수 없습니다. (분산 락 획득 실패, 락 경합 재시도 소진(`PessimisticLockingFailureException` × 3회), 또는 Mock 은행 일시 장애/타임아웃/연결 실패 — REMITTANCE) |

> **수신자 잔액 행 자동 생성** — 수신자가 지갑은 있으나 해당 통화 잔액 행이 없으면 송금 시점에 0원 행을 자동 생성한 뒤 입금한다(`WalletBalanceWriter.ensureBalanceRow` — `REQUIRES_NEW`로 독립 커밋, `uk_wallet_balances_wallet_currency` UNIQUE로 동시 생성 race 흡수). 따라서 미보유 통화 수신 요청도 성공한다. **송신자**는 자동 생성하지 않으며(돈이 있어야 보내는 게 정상), 잔액 행 부재 시 `WALLET4001`. 수신자 행이 자동 생성 직후에도 없는 경우 정합성 불변식 위반으로 `COMMON5000`(정상 흐름에서 발생 불가 — 방어).

### 6-0. REMITTANCE 동작 노트 (2단계)

INTERNAL_TRANSFER와 분기를 분리해 처리한다. 외부 계좌 송금이라 receiver wallet/self-check/분산 락이 의미 없어, 단일 송신자 잔액 행 비관적 락(`SELECT … FOR UPDATE`)만으로 직렬화한다.

**흐름** (`TransferServiceImpl.executeRemittanceInTransaction`):

1. 송신자 wallet 재조회 + 본인 소유 + active `bank_account` 조회(없으면 `ACCOUNT4001` — 사유 미구분, 정보 누설 방지)
2. `mock_account_token == null` → `ACCOUNT4006`(외부 호출 전 차단, 충전과 동일 정책)
3. 송신자 잔액 행 `FOR UPDATE`(없으면 `WALLET4001` — 자동 생성 안 함)
4. 수수료 계산: `amount × 0.5%`, `RoundingMode.HALF_UP`, scale 4 (§4 정책 SSOT 공유). `totalDeduct = amount + fee`
5. 잔액 검증: `totalDeduct > 잔액` → `WALLET4002`
6. **외부 호출 *직전* 시도 흔적 별도 커밋** — `RemittanceAttemptWriter.record`로 `remittance_attempts`에 1행을 `REQUIRES_NEW`로 INSERT. 메인 트랜잭션이 이후 단계에서 rollback돼도 흔적은 살아남는다.
7. `BankClient.payout(bank_code, account_number, amount, currency_code, idempotency_key)` 호출. 외부 에러는 `BankErrorMapper`가 자동 변환(§13-4)해 그대로 전파(메인 tx rollback → 잔액 미차감).
8. 응답 `status != "COMPLETED"` → `COMMON5031`(방어, 충전 패턴 동일)
9. 송신자 잔액 차감(`totalDeduct`) → `transactions` INSERT(`type=REMITTANCE`, `bank_account_id=계좌.id`, `receiver_wallet_id=null`, `receive_amount=amount`, `receive_currency_code=currency`, `exchange_rate=null`) → 감사 로그 1건(`action="REMITTANCE"`, 송신자 한 줄만 — 외부 계좌라 수신 감사 없음) → 커밋

**시도 흔적을 `transaction_audit_logs`가 아닌 신규 `remittance_attempts`에 박는 이유**: audit log는 `transaction_id` NOT NULL이라 본 `transactions` INSERT 전엔 행을 만들 수 없고, "거래 1:1 흔적" 의미를 흐린다. 별도 테이블로 분리해 충전·INTERNAL_TRANSFER 흐름엔 영향 없게 한다(database.md `remittance_attempts` 섹션 SSOT).

> **reconcile 배치는 미구현.** 현재 `remittance_attempts`는 timeout-but-success 사건의 단서로만 쌓이며, 자동 정합성 복구 배치는 향후 운영 도입 시 본 테이블을 입력으로 추가한다.

### 6-1. 멱등성 처리 (3-layer)

동일 `Idempotency-Key` 재요청 시 에러 없이 첫 결과(2xx)를 그대로 재반환한다. **모든 layer가 `(요청자 user_public_id, 스코프 ID)`로 본 요청과 일치하는지 검증**해 cross-user/scope 응답 노출을 차단한다(스코프 ID = REMITTANCE는 `bank_account.public_id`, INTERNAL_TRANSFER는 수신자 `user_public_id`).

1. **Redis 캐시 조회 (Layer 1)** — 키는 도메인·요청자·스코프로 스코프화된 4-튜플. `idempotency:remittance:{key}:{user_public_id}:{bank_account_public_id}` 또는 `idempotency:internal_transfer:{key}:{user_public_id}:{receiver_public_id}`. hit이면 즉시 반환 (TTL 24h). 같은 `key`라도 (user, scope)가 다르면 캐시 미스 → Layer 2 흐름으로 진입.
2. **DB 조회 (Layer 2)** — `transactions.idempotency_key`로 조회 → 있으면 `rebuildFromPrior`로 **키 소유자(`prior.wallet.user_public_id` == 요청자) + 거래 유형 + 스코프** 일치 검증. 검증 실패 시 도메인 부재 에러로 모호 매핑(REMITTANCE→`ACCOUNT4001`, INTERNAL→`WALLET4001`) — 정보 누설 방지(충전 정책 답습). 통과 시 응답 재구성 + Redis 캐시 후 반환.
3. **DB UNIQUE 위반 (Layer 3)** — 동시 race로 다른 트랜잭션이 먼저 INSERT 시 `DataIntegrityViolationException` catch → 별도 `REQUIRES_NEW` 트랜잭션(`readPriorTransaction`)에서 첫 결과 재조회 + Layer 2와 동일한 검증 후 반환.

#### 6-1-1. 락 경합 재시도

`PessimisticLockingFailureException`(InnoDB 패자 트랜잭션 롤백 — 동일 잔액 행을 환전 등과 다툴 때)은 일시 충돌이므로 최대 **3회** 재시도. 소진 시 `COMMON5031`(503). 충전 `doChargeWithRetry` 패턴 답습.
### 6-2. 분산 락 정책 (데드락 회피 — INTERNAL_TRANSFER 한정)

> REMITTANCE는 단일 송신자 잔액 행만 잠그면 충분해 Redisson MultiLock을 사용하지 않는다(§6-0 참조). 아래 정책은 INTERNAL_TRANSFER 전용.

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

### 6-3. Rate-limit (user 단위, 진입 최상단)

`execute()` 진입 직후(캐시·DB·락 진입 전) **user 단위 고정 윈도** rate-limit으로 폭주를 차단한다. 같은 사용자가 연속 송금 시 외부 자금 이동의 부담을 제한하는 보안 보조 장치.

- 키: `ratelimit:transfer:{user_public_id}`
- 정책: `wallet.transfer.rate-limit.{window-seconds, limit}` (기본 60초 / 30회)
- 초과 시: `TRANSFER4006` (429)
- Redis 장애 시: **fail-open**(통과) — 가용성 우선, 분산 락의 fail-closed(503)와 성격이 다름

> 계좌 인증(`/accounts/verify`)은 IP 단위 rate-limit인데, 송금은 인증된 사용자가 호출하므로 user 단위가 더 적합하다(같은 사용자의 폭주 차단).


### 6-4. Audit Log

**INTERNAL_TRANSFER (두 건)** — 송신자/수신자 각각 잔액 변화를 별도 행으로 기록 (감사 완전성).

| action | user_public_id | before_balance | after_balance | amount |
| --- | --- | --- | --- | --- |
| `INTERNAL_TRANSFER_SEND` | sender | sender 변경 전 | sender 변경 후 | amount + fee (차감액) |
| `INTERNAL_TRANSFER_RECEIVE` | receiver | receiver 변경 전 | receiver 변경 후 | amount (수령액) |

**REMITTANCE (한 건)** — 외부 계좌라 수신 감사 없음. 송신자 한 줄만.

| action | user_public_id | before_balance | after_balance | amount |
| --- | --- | --- | --- | --- |
| `REMITTANCE` | sender | sender 변경 전 | sender 변경 후 | amount + fee (차감액) |

> 모든 행은 같은 `transaction_id` 참조. 같은 `@Transactional` 안에서 INSERT.
> REMITTANCE 시도 흔적(외부 호출 직전)은 `transaction_audit_logs`가 아닌 별도 `remittance_attempts`에 박는다(§6-0 참조).

> 사전 흐름: `POST /transfers/pin-verify`(송금 PIN 검증, §5) 성공 후 본 API 호출.
---

## 7. 송금 확인증 / 정기 송금

### 7-1. 송금 확인증 조회 ★

`GET /api/v1/transfers/{transferPublicId}/receipt` · Auth ✅

완료된 송금 한 건의 확인증(송·수취인, 금액, 수수료, 적용 환율 등)을 반환한다.

**대상 거래**: `INTERNAL_TRANSFER` · `REMITTANCE`만. 충전·환전·기타 유형은 `TRANSFER4001`로 차단(확인증 대상 아님).

**본인 검증**: 송신자(거래 wallet 주인) 본인만 조회 가능. 수신자는 별도 "받은 거래 내역" API 영역. 본인 아님·미존재·미지원 유형 실패는 모두 `TRANSFER4001`로 모호 매핑(충전 `rebuildFromPrior` 정책 답습 — cross-user 응답 노출 방지).

**Path Variable**
| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `transferPublicId` | string | O | 송금 거래 식별자(UUID, `transactions.public_id`). 최대 36자 |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 거래 식별자(UUID) |
| `sender_name` | string | N | 송금인 본명. 요청자(JWT `public_id`)의 회원 본명. MemberClient 조회 |
| `receiver_name` | string | Y | 수취인 본명. INTERNAL은 수신자 본명(MemberClient 장애 시 null), REMITTANCE는 계좌 등록 시 verify 응답으로 받은 예금주(컬럼 추가 전 등록된 구 계좌면 null) |
| `bank_name` | string | Y | 수취 은행명. REMITTANCE만 값 있음, INTERNAL은 null |
| `account_number` | string | Y | 수취 계좌번호(마스킹). REMITTANCE만 값 있음, INTERNAL은 null |
| `amount` | string | N | 송금 금액 (string 십진수, 소수점 4자리) |
| `currency_code` | string | N | 출금 통화 코드 |
| `fee` | string | N | 수수료 (string 십진수) |
| `exchange_rate` | string | Y | 적용 환율. 1·2단계 same-currency는 항상 null. 3단계(다통화)부터 값 |
| `receive_amount` | string | N | 수취 금액 (1·2단계는 amount와 동일) |
| `receive_currency_code` | string | N | 수취 통화 코드 |
| `status` | string | N | 거래 상태 (예: COMPLETED) |
| `created_at` | string | N | 송금 시각 (ISO 8601, UTC `Z`) |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다(path variable 형식 위반). |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 404 | TRANSFER4001 | 존재하지 않는 송금 내역입니다. (미존재 / 본인 아님 / 미지원 유형(CHARGE·EXCHANGE 등) 모두 동일 매핑 — 정보 누설 방지) |
| 500 | COMMON5000 | 서버 오류가 발생했습니다. (REMITTANCE 거래의 `bank_account_id`가 사라진 정합성 불변식 위반 — 정상 흐름에서 발생 불가) |

**구현 노트 — receiver_name snapshot 정책**

`Transaction.receiverName`은 송금 시점에 **snapshot으로 박힌 값**을 그대로 응답한다(외부 호출 없음).
- INTERNAL_TRANSFER: 송금 시 `MemberClient.getMember(receiverPublicId).name`을 snapshot (fail-open — MemberClient 장애 시 null로 저장하고 송금 진행)
- REMITTANCE: 송금 시 `BankAccount.holderName`을 snapshot. 그 holder_name은 계좌 등록(`POST /accounts`) 시 `POST /accounts/verify` 응답의 예금주를 받아 저장됨(외부 신뢰 source, 사용자 입력 X)

snapshot 방식이라 회원이 본명을 바꾸거나 외부 계좌의 명의가 바뀌어도 과거 거래 영수증은 송금 당시 이름 그대로 유지된다(금융 영수증 표준 패턴).

### 7-2. 정기 송금

전체 흐름: `validate`(대상 검증, POST) → `supported-currencies`(GET, 정기·일반 공통 — 별도 `scheduled/supported-currencies`는 없음) → `scheduled`(설정 POST / 내역 GET) → `scheduled/{id}/history`(진행 완료 GET).

#### 7-2-1. 정기 송금 대상 유효성 검증 ★

`POST /api/v1/transfers/scheduled/validate` · Auth ✅

정기 송금 설정 화면에서 본 설정 전에 (수취 대상, 금액, 통화) 조합이 정합한지 사전 검증한다.

**대상 송금 유형**: `INTERNAL_TRANSFER` · `REMITTANCE` 둘 다 (송금 실행 API와 동일하게 `transfer_type`으로 분기).

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `transfer_type` | string | O | `INTERNAL_TRANSFER` / `REMITTANCE` |
| `receiver_public_id` | string | △ | INTERNAL_TRANSFER 필수 (수신자 회원 UUID) |
| `bank_account_public_id` | string | △ | REMITTANCE 필수 (수신 은행 계좌 UUID) |
| `amount` | string | O | 회차당 송금액 (string 십진수, 소수점 최대 4자리) |
| `currency_code` | string | O | 출금 통화 코드 |
| `receive_currency_code` | string | O | 수취 통화 코드 |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `is_valid` | boolean | N | 검증 통과 여부 |
| `reason` | string | Y | 미통과 사유. `is_valid=true`면 null |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (Body 검증 실패, 조건부 필수 필드 누락 포함) |
| 400 | TRANSFER4002 | 지원하지 않는 통화입니다. |
| 400 | TRANSFER4003 | 지원하지 않는 송금 유형입니다. (CHARGE/EXCHANGE 등) |
| 400 | TRANSFER4004 | 자기 자신에게 송금할 수 없습니다. (INTERNAL_TRANSFER 한정) |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 403 | ACCOUNT4006 | 인증되지 않은 계좌입니다. (REMITTANCE — `mock_account_token` 미발급) |
| 404 | ACCOUNT4001 | 존재하지 않는 계좌입니다. (REMITTANCE — 본인 + active 미매칭) |
| 404 | WALLET4001 | 존재하지 않는 지갑입니다. (INTERNAL — 수신자 wallet 부재) |

**검증 흐름**

1. `transfer_type` 파싱 → 허용 유형(INTERNAL_TRANSFER/REMITTANCE)이 아니면 `TRANSFER4003`
2. 통화 enum 파싱 → 미지원이면 `TRANSFER4002`
3. 도메인별 대상 검증:
    - **REMITTANCE**: `bank_account_public_id` 누락 시 `COMMON4001`. 본인 소유 + 활성 계좌 검증(`ACCOUNT4001`). 계좌 인증 토큰 검증(`ACCOUNT4006`).
    - **INTERNAL_TRANSFER**: `receiver_public_id` 누락 시 `COMMON4001`. 자기 자신 송금 차단(`TRANSFER4004`). 수신자 wallet 존재 검증(`WALLET4001`).
4. `currency_code == receive_currency_code` 검증 — 1·2단계는 same-currency 강제, 다르면 200 + `is_valid=false` + `reason="1·2단계는 같은 통화 송금만 지원합니다. 다통화는 3단계 도입 후 지원 예정."`. 3단계(다통화) 도입 시 본 검증 조건 완화.

**mock data (통과)**
```json
{
  "success": true,
  "data": { "is_valid": true, "reason": null },
  "message": "요청이 성공적으로 처리되었습니다."
}
```

**mock data (미통과 — currency 불일치)**
```json
{
  "success": true,
  "data": {
    "is_valid": false,
    "reason": "1·2단계는 같은 통화 송금만 지원합니다. 다통화는 3단계 도입 후 지원 예정."
  },
  "message": "요청이 성공적으로 처리되었습니다."
}
```

> 외부 호출 없음 — 우리 DB만으로 사전 검증한다(빠른 검증). 정기 송금 실제 실행(`POST /scheduled`) 시점에 외부 은행 호출이 일어난다.

#### 7-2-2. 정기 송금 설정 ★

`POST /api/v1/transfers/scheduled` · Auth ✅

매주/매월 자동 실행되는 정기 송금을 설정한다. 설정 즉시 `ACTIVE` 상태로 등록되며 다음 실행 예정일(`next_run_date`)이 **KST(`Asia/Seoul`) 기준**으로 계산된다.

**대상 송금 유형**: `INTERNAL_TRANSFER` · `REMITTANCE` 둘 다 (송금 실행·validate와 동일하게 `transfer_type`으로 분기).

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `transfer_type` | string | O | `INTERNAL_TRANSFER` / `REMITTANCE` |
| `receiver_public_id` | string | △ | INTERNAL_TRANSFER 필수 (수신자 회원 UUID) |
| `bank_account_public_id` | string | △ | REMITTANCE 필수 (수신 은행 계좌 UUID) |
| `amount` | string | O | 회차당 송금액 (string 십진수, 소수점 최대 4자리) |
| `currency_code` | string | O | 출금 통화 코드 (KRW/USD/PHP/VND) |
| `receive_currency_code` | string | O | 수취 통화 코드 |
| `frequency` | string | O | 반복 주기 — `WEEKLY` / `MONTHLY` |
| `schedule_day` | integer | O | 실행 기준일 — MONTHLY=1~31, WEEKLY=1~7 (ISO 요일, 1=월요일) |
| `memo` | string | X | 메모, 최대 255자 |

**Response 201** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 정기 송금 식별자(UUID) |
| `transfer_type` | string | N | INTERNAL_TRANSFER / REMITTANCE |
| `amount` | string | N | 회차당 송금액 |
| `currency_code` | string | N | 출금 통화 |
| `receive_currency_code` | string | N | 수취 통화 |
| `frequency` | string | N | WEEKLY / MONTHLY |
| `schedule_day` | integer | N | 실행 기준일 |
| `next_run_date` | string | N | 다음 실행 예정일 (ISO 8601 date `YYYY-MM-DD`, KST 기준) |
| `last_run_at` | string | Y | 마지막 실행 시각 (ISO 8601 UTC `Z`). 최초 실행 전이면 null (설정 직후 응답엔 항상 null) |
| `status` | string | N | 상태 (ACTIVE) |
| `created_at` | string | N | 생성 시각 (ISO 8601 UTC `Z`) |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (필수 필드 누락·형식 오류·미지원 frequency) |
| 400 | TRANSFER4002 | 지원하지 않는 통화입니다. |
| 400 | TRANSFER4003 | 지원하지 않는 송금 유형입니다. (CHARGE/EXCHANGE 등) |
| 400 | TRANSFER4004 | 자기 자신에게 송금할 수 없습니다. (INTERNAL_TRANSFER 한정) |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 403 | ACCOUNT4006 | 인증되지 않은 계좌입니다. (REMITTANCE — `mock_account_token` 미발급) |
| 404 | ACCOUNT4001 | 존재하지 않는 계좌입니다. (REMITTANCE — 본인 + active 미매칭) |
| 404 | WALLET4001 | 존재하지 않는 지갑입니다. (INTERNAL — 수신자 wallet 부재) |
| 422 | COMMON4221 | 처리할 수 없는 요청입니다. (`schedule_day` 범위 초과 또는 `currency_code != receive_currency_code` — 값 자체가 비호환) |

**next_run_date 계산 정책 (`NextRunDateCalculator`)**

- KST(`Asia/Seoul`) 기준 `LocalDate` 계산.
- **WEEKLY**: 오늘 이후 가장 가까운 `schedule_day` 요일. 오늘이 그 요일이면 **다음 주** (오늘 이미 지났음 정책).
- **MONTHLY**: 이번 달의 `schedule_day` 일자가 오늘 이후면 채택, 같거나 지났으면 다음 달. **해당 달 일수보다 큰 값은 그 달 마지막 날로 fallback** (예: 31일인데 4월이면 4/30, 2월 비윤년이면 2/28).

**receiver_name snapshot 정책**

설정 시점에 `receiverName`을 미리 박아 둠 — 정기 송금 실행 회차마다 외부 호출 없이 빠르게 transactions에 복사.
- INTERNAL → `MemberClient.getMember(receiver).name` (fail-open: 장애 시 null로 저장, 송금 자체는 진행)
- REMITTANCE → `bankAccount.holderName` (구 계좌면 null)

> **연관 API**: 설정한 정기송금 목록은 §7-2-3, 자동 실행 스케줄러는 §7-2-4(KST 매일 새벽 1시 `0 0 1 * * *`, Redisson 분산 락으로 단일 인스턴스 실행 보장), 회차 실행 이력은 §7-2-5에서 조회. 단건 조회·일시정지(PAUSED)·취소(CANCELLED)·재개는 후속 사이클.

#### 7-2-3. 정기 송금 내역 조회 ★

`GET /api/v1/transfers/scheduled` · Auth ✅

로그인한 회원 본인이 설정한 정기 송금 목록을 페이지 단위로 조회한다. `status`로 선택적 필터링.

**Query Parameter**
| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `status` | string | X | 상태 필터 (`ACTIVE` / `PAUSED` / `CANCELLED`). 미지정 시 전체. 허용 외 값은 COMMON4001 |
| `page` | integer | X | 페이지 번호 (0-base, 기본 0) |
| `size` | integer | X | 페이지 크기 (기본 20, 최대 100) |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `scheduled_transfers` | array | N | 정기 송금 목록 |
| `scheduled_transfers[].public_id` | string | N | 정기송금 식별자(UUID) |
| `scheduled_transfers[].receiver_name` | string | Y | 수취인명 (설정 시 snapshot. INTERNAL은 MemberClient.name, REMITTANCE는 bankAccount.holderName. 외부 장애·구 계좌면 null) |
| `scheduled_transfers[].amount` | string | N | 회차당 송금액 (string 십진수) |
| `scheduled_transfers[].currency_code` | string | N | 출금 통화 |
| `scheduled_transfers[].receive_currency_code` | string | N | 수취 통화 |
| `scheduled_transfers[].frequency` | string | N | 반복 주기 (`WEEKLY` / `MONTHLY`) |
| `scheduled_transfers[].schedule_day` | integer | N | 실행 기준일 |
| `scheduled_transfers[].next_run_date` | string | N | 다음 실행 예정일 (ISO 8601 date) |
| `scheduled_transfers[].last_run_at` | string | Y | 마지막 실행 시각 (ISO 8601 UTC `Z`). 최초 실행 전이면 null |
| `scheduled_transfers[].status` | string | N | 상태 (`ACTIVE` / `PAUSED` / `CANCELLED`) |
| `scheduled_transfers[].created_at` | string | N | 생성 시각 (ISO 8601 UTC `Z`) |
| `page` | integer | N | 현재 페이지 (0-base) |
| `size` | integer | N | 페이지 크기 |
| `total_elements` | integer | N | 전체 건수 |
| `total_pages` | integer | N | 전체 페이지 수 |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (status 허용 enum 외, page·size 범위 위반 등) |
| 401 | AUTH4011 | 인증이 필요합니다. |

**정렬**

`created_at DESC` (최신 설정 우선). 향후 status 우선 정렬·next_run_date 정렬 옵션 검토 가능.

**전용 응답 DTO**

설정 응답({@link ScheduledTransferResponse})과 별도 DTO. 목록 응답엔 `transfer_type`/`bank_name`/`account_number` 미포함 — 명세 단순화. 수신자 식별은 `receiver_name` snapshot으로. `last_run_at`은 스케줄러 도입과 함께 두 응답(설정·목록)에 추가됨.

#### 7-2-4. 정기 송금 자동 실행 (스케줄러) ★

API 엔드포인트는 아니지만(시스템 자동 동작) 정기 송금 도메인의 핵심 동작이라 본 절에 정리한다.

**동작 개요**

| 항목 | 값 |
|---|---|
| 실행 주체 | `ScheduledTransferRunner` (`com.gb.wallet.domain.transaction.scheduled.service`) |
| 트리거 | `@Scheduled(cron = "${wallet.scheduled-transfer.cron:0 0 1 * * *}", zone = "Asia/Seoul")` — 운영 기본: KST 매일 새벽 1시. dev/데모는 yml로 덮어쓰기 |
| 분산 락 | Redisson `lock:scheduler:scheduled-transfer` (k8s multi-replica 환경에서 단일 인스턴스 실행 보장) |
| 대상 조회 | `status=ACTIVE AND next_run_date <= today_kst` (인덱스 `idx_scheduled_transfers_status_next` 활용) |
| 실행 단위 | 각 회차를 별도 트랜잭션(`REQUIRES_NEW`)으로 처리 — 한 회차 실패가 다른 회차 차단하지 않음 |
| 송금 호출 | `TransferService.execute(userPublicId, idempotencyKey, request)` 그대로 재사용 (멱등성·재시도·rate-limit·remittance_attempts 일괄) |
| 멱등성 키 | `scheduled:{public_id}:{today}` — 같은 날 두 번 트리거돼도 Layer 1/2/3 멱등으로 송금 1회만 |
| 성공 후 처리 | `markExecuted(now, nextRunDate)` — `last_run_at` 기록 + `next_run_date` 다음 주기로 갱신 |
| 실패 처리 | 로그만 + status 유지(ACTIVE). 다음 트리거에서 자동 재시도 (resume API 없는 현재 단계에서 PAUSED 자동 전환은 데드락 위험) |
| 누락 회차 | 가장 최근 1회만 실행 — `next_run_date <= today` 조건이 한 번만 만족하고 markExecuted 후 다음 주기로 점프하므로 자연스럽게 이중 실행 방지 |

**도래 행 조회 시점부터 트랜잭션 진입까지 race 회피**

스케줄러가 락 안에서 `findAll(...)`로 가져온 행을 별도 트랜잭션 안에서 다시 `findById`하는 시점에 status/next_run_date가 바뀌어 있을 수 있다(사용자가 그 사이 취소 등). `executeSingle`은 진입 직후 **double-check** — `status != ACTIVE` 또는 `next_run_date > today`면 송금 호출 없이 종료한다.

**변환 (ScheduledTransfer → TransferExecuteRequest)**

- INTERNAL_TRANSFER → `receiverPublicId` 그대로
- REMITTANCE → 저장된 `bankAccountId`(internal id)를 `bankAccountRepository.findById`로 풀어 `public_id`를 채움 (execute API 시그니처가 public_id를 받음)

**데모 시연**

운영 cron 그대로 두면 새벽 1시까지 기다려야 함. 데모용으로는 `application-dev.yml`에 임시로:
```yaml
wallet:
  scheduled-transfer:
    cron: "0 * * * * *"   # 매 분 0초마다 (데모 후 원복 또는 yml 항목 삭제)
```
변경 + wallet-service 재기동. 정기 송금 설정 후 1분 내 자동 실행 시연 가능.

**응답 필드 갱신**

스케줄러 도입과 함께 `last_run_at` 필드가 §7-2-2(설정) / §7-2-3(목록) 응답에 추가됨 — 사용자가 "마지막 실행이 언제?" 확인 가능. 최초 실행 전이면 `null`.

#### 7-2-5. 정기 송금 회차 실행 이력 조회 ★

`GET /api/v1/transfers/scheduled/{transferPublicId}/history` · Auth ✅

특정 정기 송금의 회차별 실행 이력을 페이지 단위로 조회한다. 회차 거래는 `transactions` 테이블의 송금 거래 중 스케줄러가 `idempotency_key = "scheduled:{publicId}:{date}"` 형태로 INSERT한 행 — `idempotency_key` prefix 검색(UNIQUE 인덱스 활용)으로 잡는다.

**Path Variable**
| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `transferPublicId` | string | O | 정기송금 식별자(UUID, `scheduled_transfers.public_id`). 최대 36자 |

**Query Parameter**
| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `page` | integer | X | 페이지 번호 (0-base, 기본 0) |
| `size` | integer | X | 페이지 크기 (기본 20, 최대 100) |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `histories` | array | N | 회차 실행 이력 목록 (회차 0건이면 빈 배열) |
| `histories[].public_id` | string | N | 회차 거래 식별자(UUID, `transactions.public_id`) |
| `histories[].amount` | string | N | 송금 금액 (string 십진수) |
| `histories[].currency_code` | string | N | 출금 통화 |
| `histories[].fee` | string | N | 수수료 (string 십진수) |
| `histories[].receive_amount` | string | N | 수취 금액 (string 십진수) |
| `histories[].receive_currency_code` | string | N | 수취 통화 |
| `histories[].status` | string | N | 거래 상태 (`COMPLETED` / `FAILED`). **현 단계는 COMPLETED만** — 송금 실패 시 transactions INSERT 자체 안 일어남 (FAILED 흔적은 `remittance_attempts`에만). 향후 FAILED 저장 도입 시 자연스럽게 노출 |
| `histories[].executed_at` | string | N | 실행 시각 (ISO 8601 UTC `Z`) = `transactions.created_at` |
| `page` | integer | N | 현재 페이지 (0-base) |
| `size` | integer | N | 페이지 크기 |
| `total_elements` | integer | N | 전체 회차 건수 |
| `total_pages` | integer | N | 전체 페이지 수 |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (page·size 범위 위반·path variable 형식 위반) |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 404 | TRANSFER4001 | 존재하지 않는 송금 내역입니다. (정기송금 미존재 / 본인 아님 모두 동일 매핑 — 정보 누설 방지) |

**정렬**

`executed_at DESC` (= `transactions.created_at DESC`) — 최근 실행 우선.

**본인 검증**

정기송금 조회 후 `userPublicId` 일치 확인. 불일치 시 미존재와 동일한 `TRANSFER4001`로 모호 매핑 (충전 `rebuildFromPrior` / 송금 확인증 정책 답습).

**구현 노트 — idempotency_key prefix 검색**

회차 거래를 정기송금과 연결하기 위해 `transactions.scheduled_transfer_id` 같은 FK 컬럼을 추가하는 대안도 있었으나, 다음 이유로 prefix 검색 채택:
- 정기송금 회차는 보통 수~수십(월 1회 × 1년 = 12개) — 인덱스 효율 큰 차이 없음
- `idempotency_key` UNIQUE 인덱스의 prefix 검색이 RDBMS에서 활용됨 (`LIKE 'prefix%'`)
- transactions 테이블 변경·스케줄러 변경 없이 가능
- 운영에서 회차가 비대화하면 그때 FK 컬럼으로 전환 검토

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

**Error**: 400 TRANSFER4002 (미지원 통화) / 400 COMMON4001 (요청 값 오류) / 401 AUTH4011
> 견적은 잔액을 차감하지 않으므로 WALLET4002(잔액 부족)는 발생하지 않는다 — 잔액 검증은 실행(§9)에서만 한다.

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
| 401 | AUTH4011 | 인증이 필요합니다. |
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
- 계좌 연결+자동이체 인증 요청: `POST /api/v1/accounts/verify` (※ Mock/화면용. 실제 인증 미구현). 응답으로 `account_token`만 반환한다(§13 은행 연동·`VerifyAccountResponse` 정본). 예금주 실명은 위 `GET /api/v1/accounts/holder`로 받는다.
- 계좌 등록 최종 완료: `POST /api/v1/accounts` → 201, `bank_accounts` INSERT
    - **Body 필수 필드**: `bank_code`, `account_number`, `account_token`(verify 응답), **`holder_name`(`GET /accounts/holder` 응답의 `account_holder_name`을 그대로 전달, 최대 100자)**.
    - holder_name은 REMITTANCE 송금 시 `Transaction.receiverName`에 snapshot되어 송금 확인증의 `receiver_name` 출처가 된다(외부 신뢰 source, 사용자 임의 입력 금지).
- 주 계좌 변경: `PATCH /api/v1/accounts/{id}/primary` → 200, 변경된 `AccountResponse`. 이미 주 계좌면 부수효과 없이 **멱등 200**.
- 계좌 삭제: `DELETE /api/v1/accounts/{id}` → 200, `data:null` (soft-delete, `is_active=false`).
    - **자동 승격 정책**: 주 계좌를 삭제하면 남은 활성 계좌 중 **가장 최근 등록 1건**이 자동으로 주 계좌로 승격된다(마지막 1개를 삭제하면 주 계좌 없는 상태 허용).
- 위 변경/삭제는 "사용자당 주 계좌 1개" 불변식을 user 단위 분산락으로 직렬화한다 — 락 획득 실패 시 503 `COMMON5031`.

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
| 401 | AUTH4011 | 인증이 필요합니다. |
| 403 | ACCOUNT4006 | 인증되지 않은 계좌입니다. |
| 404 | ACCOUNT4001 | 존재하지 않는 계좌입니다. |
| 404 | WALLET4001 | 존재하지 않는 지갑입니다. (계좌는 있으나 해당 회원의 지갑이 없는 방어 케이스) |
| 422 | ACCOUNT4007 | 충전 한도를 초과했습니다. |
| 500 | COMMON5000 | 서버 오류가 발생했습니다. (멱등성 일관성 위반 등 정상 흐름에서 발생 불가 — 방어) |
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
| `BANK4001` (잘못된 요청/invalid request) | 400 | `COMMON4001` (요청 값이 올바르지 않습니다) |
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
