# 공통 규칙 (Conventions) — 전역 SSOT

> **Claude Code는 모든 기능 개발에서 이 문서의 규칙을 지킨다.**
> 각 기능 폴더의 `api-spec.md`는 이 규칙을 전제로 작성되어 있다.
> PR 리뷰도 이 문서 기준으로 한다.

---

## 0. 금액·환율·수치 타입 규칙 (§0) — 치명적

- **금액·환율은 JSON `number`(float)로 전송 절대 금지.** 전송은 `string`(십진수), 서버 계산은 `Decimal`(`BigDecimal`).
  - 예: `"amount": "1200000.0000"`, `"exchange_rate": "18.0250"`
- **예외:** 금액·환율이 아닌 **표시용 수치**(계산에 쓰지 않는 값)는 `number` 허용.
  - 등락률 `change_rate`, OCR 신뢰도 `ocr_confidence` 등
- DB 금액 컬럼은 모두 `DECIMAL(18,4)`. FLOAT/DOUBLE 금지.

**이유:** float 부동소수점 오차가 실제 잔액 계산에 영향을 주면 안 된다. 표시용 비율 수치는 오차가 비즈니스에 영향 없고, string이면 프론트가 매번 파싱해야 해 비효율적이다.

---

## 1. URL 기본 구조 (§1)

```
/api/v1/{도메인}/{리소스}/{식별자}/{액션}
```

- 모든 API는 `/api/v1` 로 시작 (버전 URL 명시)
- 단어 구분은 **하이픈(-)**. 언더스코어(_) 금지
  - ✅ `/api/v1/wallet-balances`  ❌ `/api/v1/wallet_balances`
- 소문자만 사용 (❌ `/api/v1/Members`)

---

## 2. 도메인 prefix (§2)

| prefix | 담당 영역 |
| --- | --- |
| `/auth` | 인증/인가 (로그인, 회원가입, 토큰) |
| `/members` | 회원 정보, 프로필, 설정 |
| `/wallets` | 전자지갑, 잔액, 거래내역 |
| `/transfers` | 송금 (앱 내 / 타행) |
| `/exchanges` | 환전 |
| `/accounts` | 계좌 등록/관리 (충전) |
| `/documents` | AI 서류 분석 |
| `/community` | 게시글, 댓글 |

---

## 3. HTTP 메서드 (§3)

| 메서드 | 용도 |
| --- | --- |
| GET | 조회 (서버 상태 변경 없음). **GET에 body 금지** |
| POST | 생성 또는 복잡한 실행 |
| PATCH | 부분 수정 |
| DELETE | 삭제 |

> **PUT 사용 안 함.** 부분 수정은 PATCH로 통일.

---

## 4. HTTP 상태 코드 (§4)

| 상황 | 코드 |
| --- | --- |
| 조회 성공 | 200 |
| 생성 성공 | 201 |
| 수정/삭제 성공 | 200 |
| 잘못된 요청 (유효성 실패) | 400 |
| 인증 없음 (토큰 없음/만료) | 401 |
| 권한 없음 (본인 것 아님) | 403 |
| 리소스 없음 | 404 |
| 중복 (리소스 이미 존재) | 409 |
| 처리 불가 (의미상 처리 불능) | 422 |
| Rate limit 초과 | 429 |
| 서버 에러 | 500 |
| 일시적 처리 불가 | 503 |

> 생성(POST로 리소스 신규 생성)은 **201**. 송금 실행·환전 실행·충전 실행·게시글 작성 등이 해당.

---

## 5. 명명·식별자·시각 규칙 (§5)

- **리소스명은 복수 명사**: `/members`, `/wallets`, `/transfers`
- **식별자는 `public_id`(UUID)**. 내부 순번 `id` 직접 노출 금지.
  - ✅ `/api/v1/transfers/a1b2c3d4-...`  ❌ `/api/v1/transfers/1042`
- **회원 참조도 `user_public_id`(UUID)** — member 도메인 밖에서 회원을 가리킬 때. (본인 식별은 토큰 claim `public_id`로 추출 — 아래 §14 참고. document-service만 헤더 임시 수신 잔존)
- 응답 필드에서 식별자는 `public_id`로 명명. 벤더 접두사(`tx-`, `ex_`, `qt_` 등) 붙이지 않는다.
- **통화 필드는 `_code` 접미사**: `currency_code`, `from_currency_code`, `to_currency_code`, `receive_currency_code`, `fee_currency_code`
- **시각은 ISO 8601 UTC `Z`**: `"2026-05-25T12:00:00Z"`
- 액션이 필요하면 동사를 마지막에: `/transfers/{id}/execute`, `/members/check-email`
  - ❌ URL에 동사 앞세우기: `/getWallet`, `/cancelTransfer`
- **JSON 필드는 snake_case.** 단, DTO 자바 필드는 **camelCase로 두고** 전역 설정으로 변환한다. 필드마다 `@JsonProperty`를 붙이지 않는다.
  - `application.yaml`: `spring.jackson.property-naming-strategy: SNAKE_CASE`
  - 예: 자바 `walletPublicId` → JSON `wallet_public_id` (자동 변환)
  - 이 문서·각 `api-spec.md`의 응답 표는 **JSON 표면(snake_case)** 기준이다. 실제 DTO 필드명은 camelCase로 작성하면 된다.

---

## 6. Query Parameter (§5 보강)

- 필터/검색: `?category=VISA&keyword=취업`
- 정렬: `?sort=created_at&order=desc`
- 페이지네이션: `?page=0&size=20`  → **`page`는 0부터** (Spring Pageable 기본값)
- 조회 조건: `?currency=KRW`

---

## 7. 응답 형식 (§7)

### 성공
```json
{
  "success": true,
  "data": { },
  "message": "요청이 성공적으로 처리되었습니다."
}
```

### 실패
```json
{
  "success": false,
  "code": "MEMBER4001",
  "message": "존재하지 않는 회원입니다."
}
```

### 페이지네이션 응답
- 배열 키는 **도메인 복수명**을 사용한다. 예: `posts`, `accounts`, `transfers`, `exchanges`
- (메타 표현 `{domain}` 같은 placeholder를 실제 필드명으로 착각하지 말 것)

---

## 8. 멱등성 (§12-2-2) — 중요

- 생성/실행 계열 API(송금 실행, 환전 실행, 충전 실행 등)는 **`Idempotency-Key` 헤더**(UUID 권장)를 받는다.
- **동일 키 재요청 시 에러가 아니다.** 서버는 첫 번째 실행 결과(2xx)와 **동일한 응답 본문을 그대로 재반환**한다.
- 구현: Redis에 `idempotency:{key}` 저장(TTL 24h) + 거래 테이블 `idempotency_key` UNIQUE + Redis 분산 락.
- ❌ 멱등성 키 중복에 `409`/`COMMON4003` 같은 에러를 쓰지 않는다. (`COMMON4003`은 deprecated)
- 비즈니스 중복(이미 존재하는 리소스 재생성)만 `409 COMMON4091`(RESOURCE_ALREADY_EXISTS).

---

## 9. 에러 코드 SSOT (§12)

에러 코드는 **한 번 부여되면 재배치·재사용 금지**. 새 케이스가 필요하면 해당 도메인 표에 새 번호로 등록 후 사용한다. 시맨틱 문자열 코드(`INVALID_TOKEN` 등) 대신 **숫자형 코드**(`COMMON4011` 등)를 쓴다.

### 공통 (COMMON)
| code | HTTP | 의미 |
| --- | --- | --- |
| `COMMON4001` | 400 | 요청 값이 올바르지 않습니다. |
| `COMMON4002` | 400 | 필수 입력 항목이 누락되었습니다. |
| `COMMON4011` | 401 | 인증 정보가 유효하지 않습니다. (토큰 검증 실패는 `AUTH4011`로 통일 — 아래 AUTH 표. 본 코드는 호환 유지) |
| `COMMON4031` | 403 | 접근 권한이 없습니다. |
| `COMMON4041` | 404 | 존재하지 않는 리소스입니다. (도메인 코드가 없을 때 공통 사용) |
| `COMMON4091` | 409 | 이미 존재하는 리소스입니다. (멱등성 키 중복 시에는 에러 없이 첫 응답 재반환) |
| `COMMON4221` | 422 | 처리할 수 없는 요청입니다. |
| `COMMON4291` | 429 | 요청 횟수를 초과했습니다. 잠시 후 다시 시도해주세요. |
| `COMMON5000` | 500 | 서버 오류가 발생했습니다. (미처리 예외 전부 — GlobalExceptionHandler 기본값) |
| `COMMON5031` | 503 | 일시적으로 처리할 수 없습니다. |

### 인증 (AUTH)
| code | HTTP | 의미 |
| --- | --- | --- |
| `AUTH4001` | 401 | 이메일 또는 비밀번호가 올바르지 않습니다. (방식 A 잔재 — 로그인은 IdP 소관, 현재 백엔드 미사용) |
| `AUTH4002` | 401 | (예약) 액세스 토큰 무효 — 시드 점유. 신규 사용 금지 |
| `AUTH4003` | 401 | (예약) 액세스 토큰 만료 — 시드 점유. 신규 사용 금지 |
| `AUTH4004` | 401 | 유효하지 않은 리프레시 토큰입니다. (구 "이메일 인증 미완료(403)" 의미는 가입 인증 이메일 제거로 폐기) — 토큰 재발급은 IdP 소관, 백엔드 미throw |
| `AUTH4005` | 401 | 만료된 리프레시 토큰입니다. 다시 로그인해 주세요. (토큰 재발급=IdP 소관 — 백엔드 미throw) |
| `AUTH4006` | 401 | Google 인증에 실패했습니다. (소셜 로그인 전용 — 방식 B Authentik 소셜 연동 시 불필요, 재정의 보류) |
| `AUTH4007` | 401 | (예약) AUTH4004 분리용으로 점유했으나 분리 계획 폐기 — 신규 사용 금지 |
| `AUTH4011` | 401 | 인증이 필요합니다. (토큰 누락·위조·만료 등 요청 인증 실패 통합 코드 — Resource Server `RestAuthenticationEntryPoint`) |

> **AUTH4005 중복 해소(확정):** 본래 `AUTH4005`가 "Google 인증 실패"와 "리프레시 토큰 만료" 양쪽에 쓰였다. 리프레시 토큰 계열(`AUTH4004` 무효 / `AUTH4005` 만료)이 시드 번호(`AUTH4001~4003`) 다음 번호를 의도적으로 먼저 점유했으므로 **고정**하고, 나중에 끌어다 쓴 **Google 인증 실패를 신규 `AUTH4006`으로 분리**한다. (한 번 확정한 번호는 재배치 금지)
>
> ⚠️ `AUTH4004`는 본래 "이메일 인증 미완료(로그인, 403)"와 "유효하지 않은 리프레시 토큰(재발급, 401)" 두 의미가 겹쳐 있었으나, **가입 인증 이메일 기능 제거로 "이메일 인증 미완료(403)" 의미는 폐기**됐다. 따라서 `AUTH4004`는 **"유효하지 않은 리프레시 토큰(401)" 단일 코드로 확정**한다(번호 재배치·삭제 금지, CLAUDE §6). 분리용으로 점유했던 `AUTH4007`은 분리 계획 폐기로 더 이상 필요 없어 **(예약) 미사용**으로 남긴다. 현재 인증 미구현 단계라 두 코드 모두 사용처는 없다.

### 회원 (MEMBER)
| code | HTTP | 의미 |
| --- | --- | --- |
| `MEMBER4001` | 404 | 존재하지 않는 회원입니다. |
| `MEMBER4002` | 409 | 이미 사용 중인 이메일입니다. |
| `MEMBER4003` | 409 | 이미 사용 중인 닉네임입니다. |
| `MEMBER4004` | 400 | 유효하지 않거나 만료된 재설정 토큰입니다. |

### 지갑 (WALLET)
| code | HTTP | 의미 |
| --- | --- | --- |
| `WALLET4001` | 404 | 존재하지 않는 지갑입니다. |
| `WALLET4002` | 422 | 지갑 잔액이 부족합니다. (요청 형식은 정상이나 잔액 부족으로 처리 불가 — 전 도메인 공용) |

### 송금 (TRANSFER)
| code | HTTP | 의미 |
| --- | --- | --- |
| `TRANSFER4001` | 404 | 존재하지 않는 송금 내역입니다. (미존재 / 본인 아님 / 미지원 유형 모두 동일 매핑 — 정보 누설 방지) |
| `TRANSFER4002` | 400 | 지원하지 않는 통화입니다. |
| `TRANSFER4003` | 400 | 지원하지 않는 송금 유형입니다. |
| `TRANSFER4004` | 400 | 자기 자신에게 송금할 수 없습니다. |
| `TRANSFER4005` | 400 | 지원하지 않는 통화 조합입니다. (다통화 송금 — 1단계 미지원, 후속 구현 예정) |
| `TRANSFER4006` | 429 | 송금 요청 횟수를 초과했습니다. (user 단위 rate-limit — 기본 60초 윈도 / 30회) |
| `TRANSFER4007` | 400 | 송금 PIN이 일치하지 않습니다. |
| `TRANSFER4008` | 429 | 송금 PIN 입력 횟수를 초과했습니다. 잠시 후 다시 시도해주세요. (5회 연속 실패 시 10분 잠금) |
| `TRANSFER4009` | 400 | 송금 PIN이 설정되지 않았습니다. |

### 환전 (EXCHANGE)
| code | HTTP | 의미 |
| --- | --- | --- |
| `EXCHANGE4001` | 404 | 존재하지 않는 환전 내역입니다. |
| `EXCHANGE4002` | 400 | 환율 견적이 만료되었습니다. |

### 계좌 (ACCOUNT)
| code | HTTP | 의미 |
| --- | --- | --- |
| `ACCOUNT4001` | 404 | 존재하지 않는 계좌입니다. |
| `ACCOUNT4002` | 400 | 계좌 인증에 실패했습니다. |
| `ACCOUNT4003` | 400 | 연동 계좌의 잔액이 부족합니다. |
| `ACCOUNT4004` | 409 | 이미 등록된 계좌입니다. |
| `ACCOUNT4005` | 429 | 계좌 인증 요청 횟수를 초과했습니다. |
| `ACCOUNT4006` | 403 | 인증되지 않은 계좌입니다. |
| `ACCOUNT4007` | 422 | 충전 한도를 초과했습니다. |

### 문서 (DOCUMENT)
| code | HTTP | 의미 |
| --- | --- | --- |
| `DOCUMENT4001` | 404 | 존재하지 않는 문서입니다. |

> 위 표에 없는 케이스를 만나면 임의 코드를 만들지 말고, 도메인 표에 새 번호로 등록한 뒤 사용한다. 분석 미완료 상태 조회 등은 우선 `COMMON4221`로 처리.

---

## 10. Enum 표준 (§14) — SCREAMING_SNAKE_CASE

모든 enum은 대문자 스네이크. 소문자/하이픈 금지.

| 필드 | 값 |
| --- | --- |
| `transfer_type` | `INTERNAL_TRANSFER` / `REMITTANCE` |
| `exchange_type` | `EXCHANGE` / `RE_EXCHANGE` |
| `currency_code` | `KRW` / `USD` / `PHP` / `VND` |
| `status` (거래) | `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` / `CANCELLED` |
| `analysis_document_type` (서류분석) | `LABOR_CONTRACT` / `PAYSLIP` / `EMPLOYMENT_CONTRACT` |
| `identity_document_type` (신분증) | `ALIEN_REGISTRATION` / `PASSPORT` / `NATIONAL_ID` |
| `processing_status` (분석) | `COMPLETED` / `FAILED` / `PARTIAL` |
| `overall_risk_level` / `risk_level` | `LOW` / `MEDIUM` / `HIGH` |
| `category` (커뮤니티) | `LIFE_INFO` / `JOB` / `VISA` / `COUNTRY` / `RESIDENCE` / `QUESTION` |

> ⚠️ **`document_type` 단일 필드명 금지.** 도메인별로 분리:
> 신분증 인증 = `identity_document_type`, AI 서류 분석 = `analysis_document_type`.

---

## 11. 자주 하는 실수 금지 목록 (§8)

- ❌ 동사를 URL에 넣기 → `GET /api/v1/wallets/me`
- ❌ 내부 id 노출 → `public_id` 사용
- ❌ 언더스코어 URL → 하이픈
- ❌ 대문자 URL → 소문자
- ❌ GET에 body → Query Parameter / Path Variable
- ❌ 금액을 number로 전송 → string 십진수
- ❌ 멱등성 키 중복을 에러 처리 → 2xx 재반환

---

## 12. 패키지 구조 (실제 구조 기준)

**루트 패키지는 `com.gb`** 다. 프로젝트는 Gradle 멀티 모듈(`gb-backend` 루트)로, `common` 모듈과 `services` 모듈군으로 나뉜다. 의존 방향은 단방향: **서비스 → common-exception → common-response**. (상세 연동 규칙은 [`common-module-integration.md`](./common-module-integration.md))

```
gb-backend/
├── common/
│   ├── common-response     # ApiResponse(성공), ErrorResponse(실패), SuccessStatus
│   └── common-exception    # ErrorCode, CommonErrorCode, BusinessException, GlobalExceptionHandler
└── services/
    ├── member-service
    ├── wallet-service
    ├── community-service
    └── document-service
```

각 서비스 내부 패키지 (도메인별 레이어드, 루트 `com.gb.{service}`):
```
com.gb.{service}            # 예: com.gb.wallet
├── controller    # REST 컨트롤러 (요청/응답 DTO 매핑)
├── service       # 비즈니스 로직 (@Transactional)
├── repository    # JPA Repository
├── domain        # Entity
├── dto           # Request / Response DTO
│   ├── request
│   └── response
└── global
    └── exception
        └── code  # 서비스별 ErrorCode enum (com.gb.common.exception.ErrorCode 구현)
```

공통 모듈 (`com.gb.common`):
```
com.gb.common
├── response      # ApiResponse<T> (success/data/message 래퍼), ErrorResponse
└── exception
    ├── ErrorCode            # 인터페이스 (getHttpStatus/getCode/getMessage)
    ├── CommonErrorCode      # 공통 에러 코드 enum
    ├── BusinessException    # 비즈니스 예외
    └── handler              # GlobalExceptionHandler (com.gb.common.exception.handler)
```

> **메인 클래스 스캔 범위 필수:** `GlobalExceptionHandler`가 `com.gb.common.exception.handler`에 있어 서비스 기본 스캔 범위 밖이다. 각 서비스 메인 클래스는 `@SpringBootApplication(scanBasePackages = "com.gb")`로 범위를 넓혀야 전역 예외 처리기가 빈으로 등록된다. (안 하면 BusinessException을 던져도 기본 Spring 에러 응답이 나간다.) 상세: [`common-module-integration.md`](./common-module-integration.md)

> **서비스별 ErrorCode**: 각 서비스는 `com.gb.common.exception.ErrorCode` 인터페이스를 구현하는 도메인 enum을 만든다. 코드 형식 `{DOMAIN}{4자리}`, 서버 오류는 도메인 코드 신설 없이 `COMMON5000` 사용. 번호는 반드시 §9(아래) / 명세 §12-4 표를 SSOT로 등록 후 사용.

### 금융 트랜잭션 작성 규칙 (필수)
잔액이 바뀌는 작업은 `@Transactional` 안에서 **반드시 아래 순서**를 지킨다.
```
잔액 조회 → 검증 → wallet_balances 업데이트 → transaction_audit_logs INSERT(before/after_balance) → 커밋
```
- `transaction_audit_logs`는 append-only (UPDATE/DELETE 금지)
- 중복 거래 방지: `idempotency_key` UNIQUE + Redis 분산 락(`SET lock:user:{id} 1 NX EX 5`)

---

## 13. 응답 DTO 작성 시 체크리스트

코드 작성 전 스스로 점검:

- [ ] 금액/환율 필드를 `String`으로 직렬화했는가 (`BigDecimal` → string)
- [ ] 식별자를 `public_id`(UUID)로 노출했는가 (내부 `id` 누출 없음)
- [ ] 회원 참조를 `user_public_id`로 했는가 (도메인 밖일 때)
- [ ] 시각을 UTC `Z` ISO 8601로 직렬화했는가
- [ ] enum 값이 SCREAMING_SNAKE_CASE인가
- [ ] 에러 코드가 §9 표에 있는 숫자형 코드인가
- [ ] 성공 응답을 `ApiResponse<T>` 래퍼로 감쌌는가
- [ ] DTO 필드는 camelCase로 두고 Jackson 전역 변환에 맡겼는가 (`@JsonProperty` 남발 금지)
- [ ] 본인 식별자가 필요한 API는 `@CurrentUserPublicId`(토큰 claim `public_id`)로 받았는가 (§14). (document-service 한정 헤더 임시처리 잔존)

---

## 14. 인증 (OAuth2 Resource Server — 방식 B) ★ Claude Code 주의

**인증은 OAuth2 Resource Server(검표원)로 구현됐다.** 외부 IdP(개발=Authentik, 운영/스테이징=Cognito)가 토큰을 발급하고, 각 서비스는 `common-security` 기반으로 토큰을 **검증만** 한다. `SecurityConfig`에서 `oauth2ResourceServer(jwt)` + 공개경로(`/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/**`)만 permitAll, 나머지는 `authenticated()`. `issuer-uri`는 환경변수 `AUTH_ISSUER_URI`로 주입한다(env yml; 커밋되는 `application.yaml`엔 넣지 않음).

본인 식별자(`userPublicId`)는 토큰 custom claim **`public_id`**(UUID)에서 추출한다. 토큰 `sub`↔`public_id` 매핑은 회원가입 시 IdP attribute(`attributes.public_id`)로 해결됨. 컨트롤러는 서비스별 `global/security`의 ArgumentResolver로 받는다.

```java
@CurrentUserPublicId String userPublicId   // = jwt.getClaimAsString("public_id")
```

- claim이 없으면 조용히 통과시키지 말고 `AUTH4011`로 fail-fast. 값을 쓰지 않는 엔드포인트(목록·마스터 조회 등)는 파라미터를 생략하고 `authenticated()`로만 보호한다.
- 인증 실패(JWT 누락/무효) 에러 코드는 **`AUTH4011`**(common-security `RestAuthenticationEntryPoint`가 처리), 권한 없음은 `COMMON4031`을 재사용한다(도메인 인증 코드 신설 금지).
- 각 `api-spec.md`의 "Auth ✅" 표기는 인증이 필요한 엔드포인트라는 의미다.

> **적용 현황:** member · wallet · community = 적용 완료. **document-service만 아직 헤더 임시처리**(`@RequestHeader("X-User-Public-Id")` + TODO)이며, 동일 패턴으로 후속 전환 예정. document 컨트롤러를 만질 때만 아래 임시 형태가 남아있다:
>
> ```java
> // TODO: 인증 전환 후 JWT claim(public_id) 추출(@CurrentUserPublicId)로 교체.
> //       현재는 헤더(X-User-Public-Id)로 임시 수신.
> @RequestHeader("X-User-Public-Id") String userPublicId
> ```
