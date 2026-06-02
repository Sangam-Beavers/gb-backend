# 데이터베이스 설계 (Database)

> **DB:** MySQL 8.0 (Aurora MySQL = 운영/스테이징 · 온프렘 MySQL = 개발, 공통 스키마)
> **총 테이블 수:** 14개
> **AI 분석 결과:** MySQL `document_results`에 **직접 저장** — **DynamoDB 미사용**
> Claude Code는 Entity/Repository를 만들 때 이 스키마와 참조 규칙을 그대로 따른다.

---

## 0. 가장 중요한 두 가지 원칙

### (1) MSA 경계 참조 = `user_public_id` (UUID, 물리 FK 없음)

- **member 도메인(`users`, `user_verifications`) 내부**에서는 `users.id`(BIGINT FK)를 쓴다.
- **member 도메인 밖**(wallet/document/community)에서 회원을 가리킬 때는 **`user_public_id`(VARCHAR(36), 물리 FK 없음, 논리 참조)** 만 쓴다.
- `users.id`(BIGINT 순번)는 **member 도메인 경계를 절대 벗어나지 않는다.** → 순번 노출/추측 차단 + 향후 물리 DB 분리 대비.

> 현재는 단일 Aurora 안에 스키마만 분리한 상태다. 이 규칙의 실효는 "지금 장애 격리"가 아니라 "미래에 wallet/community/document를 별도 물리 DB로 승급할 때 무비용 대비"다.

대상(밖에서 `user_public_id`로 참조): `wallets`, `bank_accounts`, `transaction_audit_logs`, `document_submissions`, `posts`, `comments`, `likes`, `user_reviews(reviewer/reviewee)`.

### (2) 금융 무결성

- `wallet_balances.balance`는 **절대 캐시 금지** — 항상 DB 직접 조회
- 금액 컬럼은 모두 `DECIMAL` (FLOAT/DOUBLE 금지)
- `transactions.idempotency_key` UNIQUE + Redis 분산 락으로 중복 거래 방지
- `transaction_audit_logs`는 **append-only** (INSERT만, UPDATE/DELETE 금지)
- 외부 노출 ID는 `public_id`(UUID)

---

## 1. 테이블 목록 (도메인별)

| # | 도메인 | 테이블 | 핵심 역할 |
| --- | --- | --- | --- |
| 1 | member | `users` | 회원 기본 정보 + 이웃 온도 등급 |
| 2 | member | `user_verifications` | 신분증 인증 → 인증 배지 근거 |
| 3 | wallet | `banks` | 은행 마스터 (Beaver/Quokka Bank 포함) |
| 4 | wallet | `wallets` | 사용자 주머니 메타 |
| 5 | wallet | `wallet_balances` | 통화별 잔액 (캐시 금지) |
| 6 | wallet | `bank_accounts` | 타행 계좌 + 가상계좌 통합 |
| 7 | wallet | `transactions` | 모든 금융 거래 마스터 |
| 8 | wallet | `transaction_audit_logs` | 거래 감사 로그 + 상태 이력 (append-only) |
| 9 | document | `document_submissions` | 문서 업로드 ~ 분석 전 메타 |
| 10 | document | `document_results` | 분석 완료 결과 메타 **+ 분석 내용** |
| 11 | community | `posts` | 게시글 + 번역 캐시 |
| 12 | community | `comments` | 댓글 + 대댓글 |
| 13 | community | `likes` | 게시글/댓글 좋아요 통합 |
| 14 | community | `user_reviews` | 이웃 온도 평가 기록 |

> **통화 마스터 테이블 없음** — 지원 통화 4개(KRW/USD/PHP/VND) 고정. `currency_code`를 VARCHAR로 직접 저장.

---

## 2. member 도메인

### `users`
> 모든 도메인이 참조하는 기반 테이블. `public_id`가 회원의 유일한 대외 식별자.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | 내부 식별자. **member 내부 전용, 경계 밖 노출 금지** |
| `public_id` | VARCHAR(36) | UNIQUE, NOT NULL | 대외 UUID. 타 도메인은 이 값으로만 회원 참조 |
| `auth_provider_id` | VARCHAR(255) | UNIQUE, NOT NULL | JWT sub. 개발(Authentik)/운영(Cognito) 공통 컬럼 |
| `email` | VARCHAR(255) | UNIQUE, NOT NULL | 이메일 |
| `nickname` | VARCHAR(50) | NOT NULL | 닉네임 |
| `nationality` | VARCHAR(10) | NOT NULL | 국적 코드 (KR, VN, PH 등) |
| `is_verified` | BOOLEAN | NOT NULL, DEFAULT FALSE | 인증 배지 여부 |
| `temperature_grade` | VARCHAR(10) | NOT NULL, DEFAULT 'GREEN' | 이웃 온도 (RED/YELLOW/GREEN/PURPLE/BLUE) |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |
| `deleted_at` | DATETIME | NULL | soft delete |

> 환경별 `auth_provider_id`: 개발 `"authentik|..."`, 운영 `"ap-northeast-2_...|..."`. Spring은 `issuer-uri` 설정만 다르게.

### `user_verifications`
> 신분증 인증. APPROVED 시 `users.is_verified = TRUE`. **member 내부 테이블 → `user_id`는 BIGINT FK 유지.**

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `user_id` | BIGINT | FK → users.id, NOT NULL | member 내부 참조 → BIGINT FK |
| `document_type` | VARCHAR(30) | NOT NULL | 신분증 유형 (ALIEN_REGISTRATION/PASSPORT/NATIONAL_ID) ※ API에선 `identity_document_type` |
| `document_number` | VARCHAR(100) | NOT NULL | **AES-256 암호화 저장** |
| `s3_key` | VARCHAR(500) | NOT NULL | 신분증 이미지 S3 경로 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | PENDING/APPROVED/REJECTED |
| `reviewed_at` | DATETIME | NULL | |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |

---

## 3. wallet 도메인

### `banks`
> 은행 마스터. 국내/해외/시뮬레이션 은행(Beaver Bank, Quokka Bank) 모두 포함.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `code` | VARCHAR(20) | UNIQUE, NOT NULL | 은행 코드 |
| `name` | VARCHAR(100) | NOT NULL | 은행명 |
| `country` | VARCHAR(10) | NOT NULL | 국가 코드 |
| `is_domestic` | BOOLEAN | NOT NULL | 국내/해외 |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT TRUE | 연동 여부 |

### `wallets`
> 사용자당 1개. 잔액은 `wallet_balances`에서 통화별 관리.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `public_id` | VARCHAR(36) | UNIQUE, NOT NULL | 대외 UUID |
| `user_public_id` | VARCHAR(36) | UNIQUE, NOT NULL | **회원 논리 참조 (물리 FK 없음)** |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |

### `wallet_balances`
> 통화별 잔액. **캐시 절대 금지.**

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `wallet_id` | BIGINT | FK → wallets.id, NOT NULL | 스키마 내부 참조 → BIGINT FK |
| `currency_code` | VARCHAR(10) | NOT NULL | KRW/USD/PHP/VND |
| `balance` | DECIMAL(18,4) | NOT NULL, DEFAULT 0 | 잔액 |
| `updated_at` | DATETIME | NOT NULL | |

> `(wallet_id, currency_code)` 복합 UNIQUE 권장.

### `bank_accounts`
> 타행 계좌 + 가상계좌 통합. (충전 출금 대상)

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `public_id` | VARCHAR(36) | UNIQUE, NOT NULL | 대외 UUID |
| `user_public_id` | VARCHAR(36) | NOT NULL | **회원 논리 참조** |
| `bank_id` | BIGINT | FK → banks.id, NOT NULL | 스키마 내부 참조 |
| `account_number` | VARCHAR(100) | NOT NULL | 계좌번호 (암호화 권장) |
| `mock_account_token` | VARCHAR(36) | NULL | **충전용 토큰.** 계좌 인증 시 Mock 은행(또는 실서비스 PG)이 발급한 토큰. 충전(출금) 호출 시 이 값으로 계좌를 지칭한다. 실서비스에서는 PG 빌링키에 해당 |
| `is_virtual` | BOOLEAN | NOT NULL, DEFAULT FALSE | TRUE면 가상계좌(Beaver Bank 발급) |
| `is_primary` | BOOLEAN | NOT NULL, DEFAULT FALSE | 주 계좌 여부 |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT TRUE | |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |

> `mock_account_token`은 계좌 등록(`POST /accounts`) 시 Mock 은행 `verify` 응답의 `account_token`을 저장한다. 충전(`POST /accounts/{id}/charge`) 시 이 토큰으로 Mock 은행 `withdrawal`을 호출한다. 미인증 계좌(`is_active`/토큰 없음)는 충전 불가. 상세 연동: [`remittance/api-spec.md`](./remittance/api-spec.md)의 "Mock 은행 연동" 섹션.

### `transactions`
> 모든 금융 거래 마스터. `type`으로 유형 구분, 유형별 상세 컬럼 보유.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `public_id` | VARCHAR(36) | UNIQUE, NOT NULL | 거래 번호(UUID) |
| `wallet_id` | BIGINT | FK → wallets.id, NOT NULL | 출금 주머니 |
| `type` | VARCHAR(30) | NOT NULL | CHARGE/INTERNAL_TRANSFER/REMITTANCE/EXCHANGE |
| `amount` | DECIMAL(18,4) | NOT NULL | 거래 금액 |
| `currency_code` | VARCHAR(10) | NOT NULL | 거래 통화 |
| `fee` | DECIMAL(18,4) | NOT NULL, DEFAULT 0 | 수수료 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | PENDING/PROCESSING/COMPLETED/FAILED/CANCELLED |
| `idempotency_key` | VARCHAR(100) | UNIQUE, NOT NULL | 멱등성 키 (+ Redis 분산 락) |
| `receiver_wallet_id` | BIGINT | FK → wallets.id, NULL | INTERNAL_TRANSFER 수취 주머니 |
| `bank_account_id` | BIGINT | FK → bank_accounts.id, NULL | REMITTANCE 수취 계좌 |
| `receiver_name` | VARCHAR(100) | NULL | REMITTANCE 수취인명 |
| `receive_amount` | DECIMAL(18,4) | NULL | 수취 금액 (환율 적용 후) |
| `receive_currency_code` | VARCHAR(10) | NULL | 수취/환전 통화 |
| `exchange_rate` | DECIMAL(18,8) | NULL | 적용 환율 |
| `to_amount` | DECIMAL(18,4) | NULL | EXCHANGE 환전 후 금액 |
| `memo` | VARCHAR(255) | NULL | |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |

**유형별 사용 컬럼**

| type | 주요 컬럼 |
| --- | --- |
| CHARGE | amount, currency_code, fee, bank_account_id |
| INTERNAL_TRANSFER | amount, currency_code, receiver_wallet_id, memo |
| REMITTANCE | amount, currency_code, fee, bank_account_id, receiver_name, receive_amount, receive_currency_code, exchange_rate, memo |
| EXCHANGE | amount, currency_code, receive_currency_code, exchange_rate, to_amount |

### `transaction_audit_logs`
> 거래 감사 로그 + 상태 이력. **append-only (INSERT만).**
> `@Transactional` 안에서 순서: 잔액 조회 → 검증 → 잔액 업데이트 → 이 로그 INSERT → 커밋.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `transaction_id` | BIGINT | FK → transactions.id, NOT NULL | 스키마 내부 참조 |
| `user_public_id` | VARCHAR(36) | NOT NULL | **회원 논리 참조** |
| `action` | VARCHAR(50) | NOT NULL | CHARGE/TRANSFER/REMITTANCE/EXCHANGE/CANCEL 등 |
| `amount` | DECIMAL(18,4) | NOT NULL | |
| `currency_code` | VARCHAR(10) | NOT NULL | FK 없이 직접 저장(당시 기록 보존) |
| `before_balance` | DECIMAL(18,4) | NOT NULL | 거래 전 잔액 |
| `after_balance` | DECIMAL(18,4) | NOT NULL | 거래 후 잔액 |
| `status` | VARCHAR(20) | NOT NULL | 거래 상태 |
| `reason` | VARCHAR(255) | NULL | 실패 시 에러 메시지 |
| `ip_address` | VARCHAR(45) | NULL | 요청 IP(IPv6 포함) |
| `created_at` | DATETIME | NOT NULL | |

---

## 4. document 도메인

> **MySQL이 (분석) 메타 + 분석 내용을 모두 보관.** **분석 결과 저장에는 DynamoDB 미사용.** (후속 챗봇 대화기록은 별도 워크로드로 계정 B DynamoDB `chat_sessions`에 저장 — [`document-analysis/ai-chatbot-mcp.md`](./document-analysis/ai-chatbot-mcp.md). 이 테이블은 계정 B 소관이라 본 스키마 문서 범위 밖.)
> 분석 처리 흐름은 [`document-analysis/ai-pipeline.md`](./document-analysis/ai-pipeline.md).
> **결과 페이로드 ↔ DB 컬럼 매핑 SSOT:** [`document-analysis/result-json-schema-agreement.md`](./document-analysis/result-json-schema-agreement.md) (현재 **v1.1**).
> v1.1 DDL 변경점(2026-05-29): `document_results`에 `analysis_document_type` 신규, `ocr_confidence` `DECIMAL(5,4)` → `DECIMAL(3,2)`, `s3_masked_key`(VARCHAR(500)) → `masked_file_url`(VARCHAR(512)) 리네임 + 사이즈, `translated_lang` 신규. `document_submissions.status`는 `ANALYZING/COMPLETED/FAILED` 그대로 유지(PARTIAL은 결과 품질 수준 컬럼인 `results.processing_status`에만 존재).

### `document_submissions`
> 업로드 ~ 분석 전 메타.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `public_id` | VARCHAR(36) | UNIQUE, NOT NULL | 문서 식별자. 상태/결과 조회 키 |
| `user_public_id` | VARCHAR(36) | NOT NULL | **회원 논리 참조** |
| `analysis_document_type` | VARCHAR(30) | NOT NULL | LABOR_CONTRACT/PAYSLIP/EMPLOYMENT_CONTRACT |
| `file_name` | VARCHAR(255) | NOT NULL | 원본 파일명 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'ANALYZING' | ANALYZING/COMPLETED/FAILED |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |

### `document_results`
> 분석 완료 결과 메타 **+ 분석 내용(직접 저장)**. submission과 1:1.
> 컬럼 ↔ 결과 페이로드 매핑 SSOT: [`document-analysis/result-json-schema-agreement.md`](./document-analysis/result-json-schema-agreement.md) §5 (현재 v1.1).

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `submission_id` | BIGINT | FK → document_submissions.id, UNIQUE, NOT NULL | 스키마 내부 참조 (1:1) |
| `analysis_document_type` | VARCHAR(30) | NOT NULL | LABOR_CONTRACT/PAYSLIP/EMPLOYMENT_CONTRACT. v1.1 신규. submissions 동일 컬럼과 일관(Lambda B 페이로드 1:1) |
| `processing_status` | VARCHAR(20) | NOT NULL | COMPLETED/FAILED/PARTIAL. submissions.status(진행 상태)와는 별개 — 결과 품질 수준 |
| `overall_risk_level` | VARCHAR(10) | NULL | LOW/MEDIUM/HIGH. 위험 없음/분석 실패 시 NULL |
| `ocr_confidence` | DECIMAL(3,2) | NULL | OCR 신뢰도, 범위 [0.00, 1.00] 고정. v1.1 — DECIMAL(5,4)에서 변경 |
| `wage_summary` | JSON | NULL | 급여 요약 (통화, 월급, 시급, 공제 목록) |
| `risk_items` | JSON | NULL | 위험 항목 배열 (risk_level, clause, description) |
| `translated_text` | TEXT | NULL | 번역 전문 |
| `translated_lang` | VARCHAR(8) | NULL | 번역 결과 언어 코드 (ISO 639-1, 데모 `"ko"` 고정). v1.1 신규 |
| `masked_file_url` | VARCHAR(512) | NULL | 마스킹본 S3 풀 URL(`s3://bucket/key`). 환경별 버킷명이 달라 풀 URL 통째 저장 — 키만 잘라 저장 금지. v1.1 — `s3_masked_key`(VARCHAR(500))에서 컬럼명·사이즈 변경 |
| `failed_reason` | VARCHAR(255) | NULL | 실패 사유 (FAILED/PARTIAL일 때) |
| `completed_at` | DATETIME | NULL | 분석 완료 시각 |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |

> `wage_summary`/`risk_items`는 JSON 컬럼으로 분석 내용을 직접 저장(이전 DynamoDB 역할 흡수).

---

## 5. community 도메인

### `posts`
> 게시글 + 번역 캐시. category VARCHAR. soft delete.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `public_id` | VARCHAR(36) | UNIQUE, NOT NULL | |
| `user_public_id` | VARCHAR(36) | NOT NULL | **회원 논리 참조** |
| `category` | VARCHAR(30) | NOT NULL | LIFE_INFO/JOB/VISA/COUNTRY/RESIDENCE/QUESTION |
| `language` | VARCHAR(10) | NOT NULL | 작성 언어 코드 |
| `title` | VARCHAR(255) | NOT NULL | |
| `content` | TEXT | NOT NULL | |
| `translated_title` | VARCHAR(255) | NULL | 번역 캐시 |
| `translated_content` | TEXT | NULL | 번역 캐시 |
| `translated_language` | VARCHAR(10) | NULL | 번역 언어 코드 |
| `view_count` | INT | NOT NULL, DEFAULT 0 | (Redis 카운터 → 배치 동기화) |
| `like_count` | INT | NOT NULL, DEFAULT 0 | likes 집계 캐시 |
| `comment_count` | INT | NOT NULL, DEFAULT 0 | comments 집계 캐시 |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |
| `deleted_at` | DATETIME | NULL | soft delete |

### `comments`
> 대댓글은 `parent_id`. soft delete.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `post_id` | BIGINT | FK → posts.id, NOT NULL | 스키마 내부 참조 |
| `user_public_id` | VARCHAR(36) | NOT NULL | **회원 논리 참조** |
| `parent_id` | BIGINT | FK → comments.id, NULL | NULL이면 최상위, 값 있으면 대댓글 |
| `content` | TEXT | NOT NULL | |
| `like_count` | INT | NOT NULL, DEFAULT 0 | |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |
| `deleted_at` | DATETIME | NULL | |

### `likes`
> 게시글/댓글 좋아요 통합. 중복 방지 복합 UNIQUE.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `user_public_id` | VARCHAR(36) | NOT NULL | **회원 논리 참조** |
| `target_type` | VARCHAR(10) | NOT NULL | POST/COMMENT |
| `target_id` | BIGINT | NOT NULL | 대상 ID |
| `created_at` | DATETIME | NOT NULL | |

> `(user_public_id, target_type, target_id)` 복합 UNIQUE.

### `user_reviews`
> 이웃 온도 평가. 집계는 `users.temperature_grade`에 반영.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `reviewer_public_id` | VARCHAR(36) | NOT NULL | **평가자 논리 참조** |
| `reviewee_public_id` | VARCHAR(36) | NOT NULL | **피평가자 논리 참조** |
| `score` | INT | NOT NULL | 1~5 |
| `comment` | VARCHAR(500) | NULL | |
| `created_at` | DATETIME | NOT NULL | |

> `(reviewer_public_id, reviewee_public_id)` 복합 UNIQUE.

---

## 6. 공통 컬럼 규약

- `created_at` / `updated_at` 모든 테이블 공통 (DATETIME, NOT NULL)
- soft delete 대상: `users`, `posts`, `comments` (`deleted_at` NULL이면 활성)
- 외부 노출 식별자: `public_id` (UUID, VARCHAR(36))

---

## 7. Redis 키 설계

| 용도 | 키 패턴 | 명령 예시 | TTL |
| --- | --- | --- | --- |
| 송금 분산 락 (wallet 단위, 두 개 MultiLock) | `lock:wallet:{walletId}` | Redisson MultiLock(ID 오름차순, waitTime=3s, leaseTime=5s) | 5초 |
| 계좌 등록 직렬화 락 (user 단위, 단일 키) | `lock:account-register:{userPublicId}` | Redisson Lock(waitTime=3s, leaseTime=5s). 획득 실패 시 503(fail-closed) | 5초(lease) |
| 멱등성 키 (송금·충전 공용) | `idempotency:{key}` | `SET ... <result> EX 86400` | 24시간 |
| 계좌 인증(verify) rate-limit (IP 단위) | `ratelimit:account-verify:{clientIp}` | `INCR` + 첫 증가 시 `EXPIRE 60`. 초과 시 ACCOUNT4005(429), Redis 장애 시 fail-open | 윈도(기본 60초) |
| 토큰 블랙리스트 | `blacklist:{token}` | `SET ... 1 EX <남은만료>` | 토큰 만료까지 |
| 로그인 실패 카운터 | `login:fail:user:{userPublicId}` | `INCR` + `EXPIRE 300` | 5분 |
| 게시글 조회수 | `view:post:{postPublicId}` | `INCR` (배치로 DB 동기화) | — |
| 환율 캐시 | `rate:{from}-{to}` | `SET ... <rate> EX 60` | 60초 |
| 세션 캐시 | `session:{id}` | TTL 30분 | 30분 |

> ⚠️ 잔액(balance)은 Redis에 캐싱하지 않는다.
>
> - `lock:account-register`·`ratelimit:account-verify` 윈도/임계값은 `wallet.account.verify-rate-limit.{window-seconds,limit}`(기본 60s/10회)로 외부 설정한다(wallet-service).
> - 충전 멱등성은 `idempotency:{key}`(Layer 1 캐시) + `transactions.idempotency_key` UNIQUE(Layer 2·3)로 보장한다. 충전엔 분산 락을 두지 않는다(단일 wallet + 비관적 락 FOR UPDATE + key UNIQUE로 충분).

---

## 8. Entity 작성 시 주의 (Claude Code 체크리스트)

- [ ] member 도메인 밖 Entity에서 회원 참조는 `userPublicId` String 필드 (FK 매핑 X)
- [ ] 금액은 `BigDecimal` (`DECIMAL(18,4)`)
- [ ] `public_id`는 생성 시 UUID 발급, API 응답에 `id` 노출 금지
- [ ] 거래 작성 시 `idempotency_key` UNIQUE + Redis 락
- [ ] 감사 로그는 INSERT만 (Entity에 update 메서드 두지 말 것)
- [ ] soft delete 대상은 `deleted_at`으로 필터 (`@Where` 또는 조건 명시)
