# 데이터베이스 설계 (Database)

> **DB:** MySQL 8.0 (Aurora MySQL = 운영/스테이징 · 온프렘 MySQL = 개발, 공통 스키마)
> **총 테이블 수:** 15개
> **AI 분석 결과:** MySQL `document_results`에 **직접 저장** — **DynamoDB 미사용**
> Claude Code는 Entity/Repository를 만들 때 이 스키마와 참조 규칙을 그대로 따른다.

---

## 0. 가장 중요한 두 가지 원칙

### (1) MSA 경계 참조 = `user_public_id` (UUID, 물리 FK 없음)

- **member 도메인(`members`, `user_verifications`) 내부**에서는 `members.id`(BIGINT FK)를 쓴다.
- **member 도메인 밖**(wallet/document/community)에서 회원을 가리킬 때는 **`user_public_id`(VARCHAR(36), 물리 FK 없음, 논리 참조)** 만 쓴다.
- `members.id`(BIGINT 순번)는 **member 도메인 경계를 절대 벗어나지 않는다.** → 순번 노출/추측 차단 + 향후 물리 DB 분리 대비.

> 현재는 단일 Aurora 안에 스키마만 분리한 상태다. 이 규칙의 실효는 "지금 장애 격리"가 아니라 "미래에 wallet/community/document를 별도 물리 DB로 승급할 때 무비용 대비"다.

대상(밖에서 `user_public_id`로 참조): `wallets`, `bank_accounts`, `transaction_audit_logs`, `document_submissions`, `posts`, `comments`, `likes`.

### (2) 금융 무결성

- `wallet_balances.balance`는 **절대 캐시 금지** — 항상 DB 직접 조회
- 금액 컬럼은 모두 `DECIMAL` (FLOAT/DOUBLE 금지)
- `transactions.idempotency_key` UNIQUE + Redis 분산 락으로 중복 거래 방지
- `transaction_audit_logs`는 **append-only** (INSERT만, UPDATE/DELETE 금지)
- 외부 노출 ID는 `public_id`(UUID)

### (3) 민감정보 컬럼 암호화 (PII)

- 신분증 번호 등 **고민감 PII는 평문 저장 금지.** 애플리케이션 레이어에서 AES-256-GCM으로 자동 암복호한 뒤 DB에는 ciphertext(Base64)만 적재한다 — JPA `AttributeConverter`(`EncryptedStringConverter`)가 영속/조회 시점에 투명 변환.
- 컬럼 길이는 **암호화 오버헤드 흡수치**로 잡는다(평문 100자 기준 `VARCHAR(255)`). 산정표·키 관리·적용 컬럼 목록은 [`conventions.md` §15](./conventions.md#15-민감정보-컬럼-암호화-pii-★-claude-code-주의) 참고.
- 운영 키는 환경변수 `GB_CRYPTO_KEY`(Base64 32B)로 주입. 운영 전환 시 AWS KMS Envelope Encryption으로 교체 예정(별도 이슈).

---

## 1. 테이블 목록 (도메인별)

| # | 도메인 | 테이블 | 핵심 역할 |
| --- | --- | --- | --- |
| 1 | member | `members` | 회원 기본 정보 |
| 2 | member | `user_verifications` | 신분증 인증 → 인증 배지 근거 (엔티티 미구현·계획) |
| 3 | wallet | `banks` | 은행 마스터 (Beaver/Quokka Bank 포함) |
| 4 | wallet | `wallets` | 사용자 주머니 메타 |
| 5 | wallet | `wallet_balances` | 통화별 잔액 (캐시 금지) |
| 6 | wallet | `bank_accounts` | 타행 계좌 + 가상계좌 통합 |
| 7 | wallet | `transactions` | 모든 금융 거래 마스터 |
| 8 | wallet | `transaction_audit_logs` | 거래 감사 로그 + 상태 이력 (append-only) |
| 9 | wallet | `remittance_attempts` | REMITTANCE 외부 호출 시도 흔적 (운영 reconcile 입력, append-only) |
| 10 | wallet | `charge_attempts` | CHARGE 외부 호출 시도 흔적 (운영 reconcile 입력, append-only) |
| 11 | wallet | `scheduled_transfers` | 정기 송금 설정 (매주/매월 자동 실행 대상) |
| 12 | document | `document_submissions` | 문서 업로드 ~ 분석 전 메타 |
| 13 | document | `document_results` | 분석 완료 결과 메타 **+ 분석 내용** |
| 14 | community | `posts` | 게시글 + 번역 캐시 |
| 15 | community | `comments` | 댓글 + 대댓글 |
| 16 | community | `likes` | 게시글/댓글 좋아요 통합 |

> **통화 마스터 테이블 없음** — 지원 통화 4개(KRW/USD/PHP/VND) 고정. `currency_code`를 VARCHAR로 직접 저장.

---

## 2. member 도메인

### `members`
> 모든 도메인이 참조하는 기반 테이블. `public_id`가 회원의 유일한 대외 식별자.
> 엔티티 `@Table(name = "members")`가 SSOT다(과거 표기 `users`에서 정정). `created_at`/`updated_at`은
> `BaseEntity`(JPA Auditing), `deleted_at`은 엔티티가 직접 채운다(탈퇴 soft delete).

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | 내부 식별자. **member 내부 전용, 경계 밖 노출 금지** |
| `public_id` | VARCHAR(36) | UNIQUE, NOT NULL | 대외 UUID. 타 도메인은 이 값으로만 회원 참조 |
| `auth_provider_id` | VARCHAR(255) | UNIQUE, NOT NULL | JWT sub. 개발(Authentik)/운영(Cognito) 공통 컬럼 |
| `email` | VARCHAR(255) | UNIQUE, NOT NULL | 이메일 |
| `name` | VARCHAR(100) | NOT NULL | 이름 |
| `nickname` | VARCHAR(50) | NOT NULL | 닉네임 |
| `nationality` | VARCHAR(10) | NOT NULL | 국적 코드 (KR, VN, PH 등) |
| `language` | VARCHAR(10) | NOT NULL | 주 사용 언어 (BCP 47 소문자, 예: "vi") |
| `is_verified` | BOOLEAN | NOT NULL, DEFAULT FALSE | 인증 배지 여부. `user_verifications` APPROVED 시 true로 반영(엔티티 `Member.isVerified` 반영 완료). |
| `bio` | VARCHAR(200) | NULL | 자기소개(한 줄, 마이페이지 입력, 선택값) |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |
| `deleted_at` | DATETIME | NULL | soft delete |

> 환경별 `auth_provider_id`: 개발 `"authentik|..."`, 운영 `"ap-northeast-2_...|..."`. Spring은 `issuer-uri` 설정만 다르게.

### `user_verifications`
> 신분증 인증. APPROVED 시 `members.is_verified = TRUE`. **member 내부 테이블 → `user_id`는 BIGINT FK 유지.**
> 엔티티 반영 완료(`UserVerification`, @ManyToOne Member). 데모 구현은 번호 형식(정규식) 검증 통과 시 즉시 APPROVED + 배지 부여(관리자 검토 단계 생략).

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `user_id` | BIGINT | FK → members.id, NOT NULL | member 내부 참조 → BIGINT FK |
| `document_type` | VARCHAR(30) | NOT NULL | 신분증 유형 (ALIEN_REGISTRATION/PASSPORT/NATIONAL_ID) ※ API에선 `identity_document_type` |
| `document_number` | VARCHAR(255) | NOT NULL | **AES-256-GCM 암호화 저장** (`EncryptedStringConverter`가 영속 시점에 자동 변환 — `Base64(IV \|\| ciphertext \|\| tag)`. 평문 100자 + GCM 28B + Base64 오버헤드 흡수). 운영 키는 환경변수 `GB_CRYPTO_KEY`로 주입. 운영 전환 시 KMS Envelope Encryption으로 교체 예정(별도 이슈) |
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
| `status` | VARCHAR(20) | NOT NULL | 지갑 상태(WalletStatus: ACTIVE / SUSPENDED / CLOSED). 생성 시 ACTIVE |
| `transfer_pin_hash` | VARCHAR(72) | NULL | 송금 PIN(숫자 6자리) BCrypt 해시. null = 미설정 |
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
| `account_number` | VARCHAR(100) | NOT NULL | **AES-256 암호화 저장 예정** (현재 데모 평문 저장 + 응답 마스킹(`AccountNumberMasker`), 공용 D-3 암복호 유틸 도입 후 교체 — 서비스 `// TODO(D-3)`. 단 WACC-06 부분 유니크/dedup이 `account_number` 평문 동등성에 의존하므로, 암호화 시 결정적 암호 또는 별도 HMAC blind-index 컬럼 필요 — `document_number`와 함께 sequencing) |
| `mock_account_token` | VARCHAR(36) | NULL | **충전용 토큰.** 계좌 인증 시 Mock 은행(또는 실서비스 PG)이 발급한 토큰. 충전(출금) 호출 시 이 값으로 계좌를 지칭한다. 실서비스에서는 PG 빌링키에 해당 |
| `holder_name` | VARCHAR(100) | NULL | **외부 계좌 예금주명.** 계좌 등록 시 은행 `inquiry` 권위 값으로 저장한다(WACC-05 — 클라이언트 입력 불신, 송금 확인증 receiver_name 위조 방지). REMITTANCE 송금 시 `Transaction.receiverName`에 snapshot 복사. 컬럼 추가 전 등록된 기존 계좌는 null. |
| `is_virtual` | BOOLEAN | NOT NULL, DEFAULT FALSE | TRUE면 가상계좌(Beaver Bank 발급) |
| `is_primary` | BOOLEAN | NOT NULL, DEFAULT FALSE | 주 계좌 여부 |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT TRUE | |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |

> `mock_account_token`은 계좌 등록(`POST /accounts`) 시 Mock 은행 `verify` 응답의 `account_token`을 저장한다. 충전(`POST /accounts/{id}/charge`) 시 이 토큰으로 Mock 은행 `withdrawal`을 호출한다. 미인증 계좌(`is_active`/토큰 없음)는 충전 불가. 상세 연동: [`remittance/api-spec.md`](./remittance/api-spec.md)의 "Mock 은행 연동" 섹션.
>
> **중복 등록 부분 UNIQUE (WACC-06, prod 수동 DDL):** 활성 계좌의 `(user_public_id, bank_id, account_number)` 중복을 DB 레벨에서 막는다. soft-delete(비활성 행 잔존) 재등록을 허용해야 하므로 **활성 행에만** 적용하는 부분 유니크가 필요한데, MySQL은 부분 유니크 인덱스를 직접 지원하지 않아 **생성 컬럼**으로 우회한다(활성일 때만 키가 채워지고 비활성이면 NULL → NULL은 유니크에서 다중 허용):
> ```sql
> ALTER TABLE bank_accounts
>   ADD COLUMN active_acct_key VARCHAR(160)
>     GENERATED ALWAYS AS (IF(is_active, CONCAT(user_public_id,':',bank_id,':',account_number), NULL)) STORED,
>   ADD CONSTRAINT uk_bank_accounts_active_acct UNIQUE (active_acct_key);
> ```
> > 길이: 생성 키 최대 = `user_public_id`(36) + `:`(1) + `bank_id`(BIGINT 최대 19자리) + `:`(1) + `account_number`(VARCHAR 100) = **157자**. 이전 `VARCHAR(120)`은 이보다 짧아 긴 키가 잘려 유니크 판정이 어긋날 수 있어 **160**으로 둔다(여유 포함).
> 분산락(`lock:account-register:{user}`)이 1차 직렬화이고, 본 제약은 lease 만료/split-brain로 락이 뚫린 동시 등록의 **최종 안전망**이다(위반 시 `BankAccountServiceImpl`이 `DataIntegrityViolationException`→ACCOUNT4004 매핑). **dev는 `ddl-auto=update`/H2가 이 생성 컬럼을 자동 생성하지 않으므로(JPA 미매핑) stage/prod에 위 DDL을 수동 적용**한다. 적용 전 기존 중복 활성 행은 사전 정리 필요.

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

### `remittance_attempts`
> REMITTANCE 외부 호출 시도 흔적. **append-only (INSERT만).**
> 외부 Mock 은행 payout 호출 *직전* `REQUIRES_NEW`로 별도 커밋한다 — 메인 트랜잭션이 rollback돼도 흔적은 살아남아 timeout-but-success 시 운영 reconcile 입력 자료가 된다.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `idempotency_key` | VARCHAR(100) | UNIQUE, NOT NULL | 시도 1회당 1행. 같은 키 재시도/동시 race는 UNIQUE로 1행 유지 |
| `user_public_id` | VARCHAR(36) | NOT NULL | **회원 논리 참조 (물리 FK 없음)**. reconcile 시 사용자별 조회용 인덱스 보유 |
| `bank_account_id` | BIGINT | NOT NULL | 검증된 외부 계좌 id (스키마 내부 참조 — `transactions.bank_account_id`와 동일 raw 컬럼 패턴) |
| `amount` | DECIMAL(18,4) | NOT NULL | 시도 차감액(amount + fee). "이만큼 보내려고 시도함" 의미 |
| `currency_code` | VARCHAR(10) | NOT NULL | KRW/USD/PHP/VND. FK 없이 직접 저장 |
| `attempted_at` | DATETIME | NOT NULL | 외부 호출 직전 기록 시각 |

> **공통 컬럼 미적용**: `created_at`/`updated_at` 없음(§6 예외) — append-only라 `updated_at`이 무의미하고, `created_at`은 `attempted_at`과 의미가 사실상 동일해 중복 컬럼이 된다. `BaseEntity` 미상속.
> **soft delete 없음**: 흔적이 사라지면 reconcile 입력이 사라지므로 영구 보존.
> **transaction_audit_logs와의 분리**: audit log는 `transaction_id` NOT NULL이라 본 `transactions` INSERT 전엔 행을 만들 수 없고, "거래 1:1 흔적" 의미를 흐린다 → 별도 테이블로 분리해 충전·1단계 INTERNAL_TRANSFER엔 영향 없게.
> **현 사이클(2단계 c1) 범위**: 테이블/엔티티/Repository/Writer 인프라만 도입. TransferServiceImpl 통합은 c2에서. reconcile 배치는 미구현 — 향후 운영 도입 시 본 테이블을 입력으로 사용한다.

### `charge_attempts`
> CHARGE 외부 호출 시도 흔적. **append-only (INSERT만).** `remittance_attempts`와 **동일 구조**이며, 충전(`withdrawal`)을 위한 별도 테이블이다(WACC-01).
> 외부 Mock 은행 `withdrawal` 호출 *직전* `REQUIRES_NEW`로 별도 커밋한다 — 메인 트랜잭션이 rollback돼도 흔적은 살아남아 timeout-but-success(또는 외부 성공 후 비재시도성 로컬 실패) 시 운영 reconcile 입력 자료가 된다.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `idempotency_key` | VARCHAR(100) | UNIQUE, NOT NULL | 시도 1회당 1행. 같은 키 재시도/동시 race는 UNIQUE로 1행 유지 |
| `user_public_id` | VARCHAR(36) | NOT NULL | **회원 논리 참조 (물리 FK 없음)**. reconcile 시 사용자별 조회용 인덱스 보유 |
| `bank_account_id` | BIGINT | NOT NULL | 출금(차감)을 시도한 외부 계좌 id (`transactions.bank_account_id`와 동일 raw 컬럼 패턴) |
| `amount` | DECIMAL(18,4) | NOT NULL | 시도 출금액(외부 계좌에서 빼려는 충전 금액). "이만큼 출금 시도함" 의미 |
| `currency_code` | VARCHAR(10) | NOT NULL | KRW(충전은 KRW 고정). FK 없이 직접 저장 |
| `attempted_at` | DATETIME | NOT NULL | 외부 호출 직전 기록 시각 |

> **왜 remittance_attempts와 분리하나(WACC-01)**: 충전은 외부계좌 `withdrawal`(차감), 송금은 `payout`(증액)으로 외부 계좌 기준 돈 방향이 정반대다. 고아 흔적(외부 성공인데 메인 tx 롤백) 발생 시 reconcile 교정 방향도 정반대(충전=환불, 송금=클로백)이므로 유형을 섞지 않고 별도 테이블로 둔다.
> **공통 컬럼/`soft delete` 미적용**: `remittance_attempts`와 동일(append-only — `updated_at` 무의미, `created_at`은 `attempted_at`과 의미 중복, `BaseEntity` 미상속, 흔적 영구 보존).
> **마이그레이션**: dev는 `ddl-auto=update`가 엔티티에서 신규 테이블을 자동 생성한다. stage/prod 등 수동 스키마 환경은 위 DDL 표를 기준으로 생성한다.

### `scheduled_transfers`
> 정기 송금 설정. 매주/매월 자동 실행되는 송금의 메타. 실행 자체는 별도 스케줄러(KST 매일 새벽 1시)가 `status=ACTIVE` & `next_run_date <= today` 행을 가져와 `TransferService.execute`를 호출하고 `next_run_date`를 갱신한다.

| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AI | |
| `public_id` | VARCHAR(36) | UNIQUE, NOT NULL | 대외 UUID |
| `user_public_id` | VARCHAR(36) | NOT NULL | 송신자 회원 논리 참조 |
| `transfer_type` | VARCHAR(30) | NOT NULL | `INTERNAL_TRANSFER` / `REMITTANCE` |
| `receiver_public_id` | VARCHAR(36) | NULL | INTERNAL_TRANSFER일 때 수신자 회원. REMITTANCE면 null |
| `bank_account_id` | BIGINT | NULL | REMITTANCE일 때 수신 계좌 내부 id (FK는 객체 매핑 안 함, Transaction.bankAccountId 패턴 동일). INTERNAL이면 null |
| `receiver_name` | VARCHAR(100) | NULL | **설정 시점 snapshot** — INTERNAL은 MemberClient.name (fail-open), REMITTANCE는 bankAccount.holderName. 실행 회차마다 transactions.receiver_name으로 복사 |
| `amount` | DECIMAL(18,4) | NOT NULL | 회차당 송금액 |
| `currency_code` | VARCHAR(10) | NOT NULL | 출금 통화 |
| `receive_currency_code` | VARCHAR(10) | NOT NULL | 수취 통화 (1·2단계 same-currency 강제) |
| `frequency` | VARCHAR(20) | NOT NULL | `WEEKLY` / `MONTHLY` |
| `schedule_day` | INT | NOT NULL | 실행 기준일 (MONTHLY=1~31, WEEKLY=1~7 ISO 요일) |
| `next_run_date` | DATE | NOT NULL | 다음 실행 예정일 (KST 기준 LocalDate) |
| `last_run_at` | DATETIME | NULL | 마지막 실행 시각. 최초 실행 전 null |
| `status` | VARCHAR(20) | NOT NULL | `ACTIVE` / `PAUSED` / `CANCELLED` |
| `memo` | VARCHAR(255) | NULL | 사용자 메모 |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | |

> **인덱스**:
> - `idx_scheduled_transfers_user (user_public_id)` — 사용자 내역 조회
> - `idx_scheduled_transfers_status_next (status, next_run_date)` — 스케줄러가 ACTIVE & 도래 행 조회용 복합 인덱스
>
> **중복 허용**: 같은 사용자가 같은 (bank_account, frequency, schedule_day) 조합으로 여러 정기 송금 설정을 둘 수 있다 (의도된 다중 설정).
>
> **현 사이클 범위**: 설정 API(`POST /scheduled`)만. 내역 조회·취소·재개는 다음 사이클, 자동 실행 스케줄러는 그 다음.

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
| `s3_key` | VARCHAR(512) | NULL | 제출 시 발급한 원본 S3 키(`original/{날짜}/{public_id}/{파일명}`). retry가 재사용(날짜로 재조립하면 제출일과 다른 날 retry 시 키가 어긋남). 컬럼 도입 이전 행은 NULL — created_at 날짜로 복원 |
| `created_at` | DATETIME | NOT NULL | |
| `updated_at` | DATETIME | NOT NULL | ANALYZING 고아 건 정리 스케줄러(StaleSubmissionSweeper)의 판정 기준 — 임계(기본 30분) 초과 시 FAILED 처리 |

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
| `public_id` | VARCHAR(36) | UNIQUE, NOT NULL | 대외 식별자(UUID) — 댓글 목록/작성/삭제 응답·URL은 public_id 사용(posts와 동일 규칙) |
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

---

## 6. 공통 컬럼 규약

- `created_at` / `updated_at` 모든 테이블 공통 (DATETIME, NOT NULL)
- soft delete 대상: `members`, `posts`, `comments` (`deleted_at` NULL이면 활성)
- 외부 노출 식별자: `public_id` (UUID, VARCHAR(36))

---

## 7. Redis 키 설계

| 용도 | 키 패턴 | 명령 예시 | TTL |
| --- | --- | --- | --- |
| 송금 분산 락 (wallet 단위, 두 개 MultiLock) | `lock:wallet:{walletId}` | Redisson MultiLock(ID 오름차순, waitTime=3s, leaseTime=5s) | 5초 |
| 계좌 등록 직렬화 락 (user 단위, 단일 키) | `lock:account-register:{userPublicId}` | Redisson Lock(waitTime=3s, leaseTime=5s). 획득 실패 시 503(fail-closed) | 5초(lease) |
| 정기송금 스케줄러 단일 실행 락 | `scheduler:scheduled-transfer` | Redisson Lock(tryLock, waitTime=3s, leaseTime=5s). 멀티 파드 중 1개만 실행 → 이중 송금 방지 | 5초(lease) |
| 멱등성 키 (송금 REMITTANCE) | `idempotency:remittance:{key}:{userPublicId}:{bankAccountPublicId}` | `SET ... <result> EX 86400`. 키를 (요청자, 계좌)로 스코프 → 교차 사용자/계좌는 캐시 미스 → DB(rebuildFromPrior)가 ACCOUNT4001로 차단 | 24시간 |
| 멱등성 키 (송금 INTERNAL_TRANSFER) | `idempotency:internal_transfer:{key}:{userPublicId}:{receiverPublicId}` | `SET ... <result> EX 86400`. 키를 (요청자, 수신자)로 스코프 → 교차 응답 노출 차단(rebuildFromPrior → WALLET4001) | 24시간 |
| 멱등성 키 (충전, 요청자·계좌 스코프) | `idempotency:charge:{key}:{userPublicId}:{accountPublicId}` | `SET ... <result> EX 86400`. 키를 (요청자, 계좌)로 스코프 → 교차 사용자/계좌는 캐시 미스 → DB(rebuildFromPrior)가 ACCOUNT4001로 차단 | 24시간 |
| 멱등성 키 (환전 EXCHANGE, 요청자 스코프) | `idempotency:exchange:{key}:{userPublicId}` | `SET ... <result> EX 86400`. 키를 (요청자)로 스코프 → 교차 사용자는 캐시 미스 → DB(rebuildFromPrior)가 차단 | 24시간 |
| 계좌 인증(verify) rate-limit (user 단위, WACC-02) | `ratelimit:account-verify:{userPublicId}` | `INCR` + 첫 증가 시 `EXPIRE 60`. 초과 시 ACCOUNT4005(429), Redis 장애 시 fail-open. 위조 가능한 IP(XFF) 대신 위조불가 userPublicId로 키잉 | 윈도(기본 60초) |
| 예금주 조회(holder) rate-limit (user 단위, WACC-03) | `ratelimit:account-holder:{userPublicId}` | `INCR` + 첫 증가 시 `EXPIRE 60`. 초과 시 COMMON4291(429), Redis 장애 시 fail-open. PII(예금주명) 조회 폭주 차단 | 윈도(기본 60초) |
| 송금 rate-limit (user 단위) | `ratelimit:transfer:{userPublicId}` | `INCR` + 첫 증가 시 `EXPIRE 60`. 초과 시 TRANSFER4006(429), Redis 장애 시 fail-open | 윈도(기본 60초, 30회) |
| PIN 검증 rate-limit (user 단위, wallet-pin-redis-1) | `ratelimit:pin-verify:{userPublicId}` | `INCR` + 첫 증가 시 `EXPIRE 60`. 무차별 대입 버스트(isLocked→대조→record TOCTOU)를 윈도당 limit으로 캡. 초과 시 COMMON4291(429), Redis 장애 시 fail-open | 윈도(기본 60초, 10회) |
| 송금 PIN 실패 카운터 (user 단위) | `pin:fail:{userPublicId}` | `INCR`(첫 실패 시 `EXPIRE 600`). 5회 도달 시 잠금 키 설정 후 카운트 삭제 | 10분(윈도) |
| 송금 PIN 잠금 (user 단위) | `pin:lock:{userPublicId}` | `SET locked EX 600`(5회 연속 실패 시). 존재하면 PIN 검증 TRANSFER4008(429) | 10분 |
| 송금 PIN 검증 마커 (user 단위, TX-PIN) | `pin:verified:{userPublicId}` | `SET verified EX 180`(pin-verify 성공 시). 송금 실행·정기송금 설정이 `GETDEL`로 **원자 소비(단일사용)** — 없으면 TRANSFER4010(428). 1회 검증=1회 인가. Redis 장애 시 **fail-closed**(송금 차단). PIN 재설정 시 무효화 | 180초 |
| 토큰 블랙리스트 | `blacklist:{token}` | `SET ... 1 EX <남은만료>` | 토큰 만료까지 |
| 로그인 실패 카운터 | `login:fail:user:{userPublicId}` | `INCR` + `EXPIRE 300` | 5분 |
| 비밀번호 재설정 토큰 (member) | `pwreset:{token}` | `SET <email> EX 1800`. 검증 시 조회해 없으면 만료/무효(MEMBER4004), 사용 후 삭제(재사용 방지) | 30분 |
| 게시글 조회수 | `view:post:{postPublicId}` | `INCR` (배치로 DB 동기화) | — |
| 환율 캐시 | `rate:{from}-{to}` | `SET ... <rate> EX 60` | 60초 |
| 세션 캐시 | `session:{id}` | TTL 30분 | 30분 |

> ⚠️ 잔액(balance)은 Redis에 캐싱하지 않는다.
>
> - `lock:account-register`·`ratelimit:account-verify` 윈도/임계값은 `wallet.account.verify-rate-limit.{window-seconds,limit}`(기본 60s/10회)로 외부 설정한다(wallet-service).
> - 충전 멱등성은 `idempotency:charge:{key}:{user}:{account}`(Layer 1 캐시, 요청자·계좌 스코프) + `transactions.idempotency_key` UNIQUE(Layer 2·3, 전역)로 보장한다. 캐시는 동일 (요청자, 계좌, key)의 정상 재요청만 가속하고, 교차 요청은 캐시 미스 → DB의 보안 검증(rebuildFromPrior)이 ACCOUNT4001로 처리한다. 충전엔 분산 락을 두지 않는다(단일 wallet + 비관적 락 FOR UPDATE + key UNIQUE로 충분).
> - 송금 멱등성도 충전과 동일하게 (도메인·요청자·스코프) 스코프된 캐시(Layer 1) + `transactions.idempotency_key` UNIQUE(Layer 2·3) 패턴을 따른다. 스코프 ID는 REMITTANCE면 `bank_account.public_id`, INTERNAL_TRANSFER면 수신자 `user_public_id`. 캐시·DB 모두 `rebuildFromPrior`로 (소유자/유형/스코프) 일치 검증해 cross-user 응답 노출을 차단한다. 락 경합(PessimisticLockingFailureException)은 최대 3회 재시도, 소진 시 COMMON5031.
> - 송금 rate-limit은 user 단위(외부 자금 이동 폭주 차단). `wallet.transfer.rate-limit.{window-seconds,limit}`(기본 60s/30회)로 외부 설정한다.

---

## 8. Entity 작성 시 주의 (Claude Code 체크리스트)

- [ ] member 도메인 밖 Entity에서 회원 참조는 `userPublicId` String 필드 (FK 매핑 X)
- [ ] 금액은 `BigDecimal` (`DECIMAL(18,4)`)
- [ ] `public_id`는 생성 시 UUID 발급, API 응답에 `id` 노출 금지
- [ ] 거래 작성 시 `idempotency_key` UNIQUE + Redis 락
- [ ] 감사 로그는 INSERT만 (Entity에 update 메서드 두지 말 것)
- [ ] soft delete 대상은 `deleted_at`으로 필터 (`@Where` 또는 조건 명시)
