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
| 허용되지 않은 HTTP 메서드 | 405 |
| 응답 콘텐츠 협상 실패 (Accept 불일치) | 406 |
| 중복 (리소스 이미 존재) | 409 |
| 지원하지 않는 요청 본문 형식 (Content-Type) | 415 |
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
  - **저장(DATETIME 컬럼)도 UTC 기준으로 캡처한다** — JPA Auditing은 각 서비스 JpaConfig의 `utcDateTimeProvider`, 비감사 캡처는 `LocalDateTime.now(ZoneOffset.UTC)`로 통일(4서비스 공통, 10D member-core-4·community-3). 직렬화의 "저장값 = UTC" 간주가 JVM 기본존과 무관하게 항상 참이 되게 한다. 예외: 정기송금 영업일(`next_run_date` 등 LocalDate)은 사용자 체감 실행일 정책으로 의도적 KST(`NextRunDateCalculator.ZONE_KST`).
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
| `COMMON4041` | 404 | 존재하지 않는 리소스입니다. (도메인 코드가 없을 때 공통 사용. 매핑되지 않은 경로 호출도 GlobalExceptionHandler가 이 코드로 응답) |
| `COMMON4051` | 405 | 허용되지 않은 HTTP 메서드입니다. (GlobalExceptionHandler 전용 분기 — 과거 catch-all 500 오인 제거, 10D common-modules-1) |
| `COMMON4061` | 406 | 응답할 수 없는 Accept 형식입니다. (콘텐츠 협상 실패. Accept가 JSON조차 거부하면 본문 없이 상태만 응답될 수 있음) |
| `COMMON4091` | 409 | 이미 존재하는 리소스입니다. (멱등성 키 중복 시에는 에러 없이 첫 응답 재반환) |
| `COMMON4151` | 415 | 지원하지 않는 요청 본문 형식입니다. (Content-Type 불일치) |
| `COMMON4221` | 422 | 처리할 수 없는 요청입니다. |
| `COMMON4291` | 429 | 요청 횟수를 초과했습니다. 잠시 후 다시 시도해주세요. |
| `COMMON5000` | 500 | 서버 오류가 발생했습니다. (미처리 예외 전부 — GlobalExceptionHandler 기본값) |
| `COMMON5031` | 503 | 일시적으로 처리할 수 없습니다. |

### 인증 (AUTH)
| code | HTTP | 의미 |
| --- | --- | --- |
| `AUTH4001` | 401 | (폐기) 방식 A 로그인 코드(이메일/비밀번호 불일치) — enum 제거됨(로그인은 IdP 소관). 번호 점유·신규 사용 금지 |
| `AUTH4002` | 401 | (예약) 액세스 토큰 무효 — 시드 점유. 신규 사용 금지 |
| `AUTH4003` | 401 | (예약) 액세스 토큰 만료 — 시드 점유. 신규 사용 금지 |
| `AUTH4004` | 401 | 유효하지 않은 리프레시 토큰입니다. (구 "이메일 인증 미완료(403)" 의미는 가입 인증 이메일 제거로 폐기) — 토큰 재발급은 IdP 소관, 백엔드 미throw |
| `AUTH4005` | 401 | 만료된 리프레시 토큰입니다. 다시 로그인해 주세요. (토큰 재발급=IdP 소관 — 백엔드 미throw) |
| `AUTH4006` | 401 | Google 인증에 실패했습니다. (소셜 로그인 전용 — 방식 B Authentik 소셜 연동 시 불필요, 재정의 보류) |
| `AUTH4007` | 401 | (예약) AUTH4004 분리용으로 점유했으나 분리 계획 폐기 — 신규 사용 금지 |
| `AUTH4011` | 401 | 인증이 필요합니다. (토큰 누락·위조·만료 등 요청 인증 실패 통합 코드 — Resource Server `RestAuthenticationEntryPoint`) |

> **AUTH4005 중복 해소(확정):** 본래 `AUTH4005`가 "Google 인증 실패"와 "리프레시 토큰 만료" 양쪽에 쓰였다. 리프레시 토큰 계열(`AUTH4004` 무효 / `AUTH4005` 만료)이 시드 번호(`AUTH4001~4003`) 다음 번호를 의도적으로 먼저 점유했으므로 **고정**하고, 나중에 끌어다 쓴 **Google 인증 실패를 신규 `AUTH4006`으로 분리**한다. (한 번 확정한 번호는 재배치 금지)
>
> ⚠️ `AUTH4004`는 본래 "이메일 인증 미완료(로그인, 403)"와 "유효하지 않은 리프레시 토큰(재발급, 401)" 두 의미가 겹쳐 있었으나, **가입 인증 이메일 기능 제거로 "이메일 인증 미완료(403)" 의미는 폐기**됐다. 따라서 `AUTH4004`는 **"유효하지 않은 리프레시 토큰(401)" 단일 코드로 확정**한다(번호 재배치·삭제 금지, CLAUDE §6). 분리용으로 점유했던 `AUTH4007`은 분리 계획 폐기로 더 이상 필요 없어 **(예약) 미사용**으로 남긴다.

### 회원 (MEMBER)
| code | HTTP | 의미 |
| --- | --- | --- |
| `MEMBER4001` | 404 | 존재하지 않는 회원입니다. |
| `MEMBER4002` | 409 | 이미 사용 중인 이메일입니다. |
| `MEMBER4003` | 409 | 이미 사용 중인 닉네임입니다. |
| `MEMBER4004` | 400 | 유효하지 않거나 만료된 재설정 토큰입니다. |
| `MEMBER4005` | 400 | 지원하지 않는 성별입니다. (gender enum 미정의 값 — 이슈 #203) |
| `MEMBER4006` | 400 | 지원하지 않는 연령대입니다. (age_range enum 미정의 값 — 이슈 #203) |

> 가입/소셜 보완의 **동시 race**(선검사 `existsBy`를 통과한 동시 INSERT가 UNIQUE 위반)는 어느 필드인지 구분 없이
> **`COMMON4091`(409, 이미 존재)** 으로 통일한다(member-5). 도메인 코드(MEMBER4002/4003)는 순차 선검사에서만
> 부여한다 — 드문 race를 필드별로 가르려면 DB별 비이식적 제약명 판별이 필요해, generic 409로 충분히 본다.

### 지갑 (WALLET)
| code | HTTP | 의미 |
| --- | --- | --- |
| `WALLET4001` | 404 | 존재하지 않는 지갑입니다. |
| `WALLET4002` | 422 | 지갑 잔액이 부족합니다. (요청 형식은 정상이나 잔액 부족으로 처리 불가 — 전 도메인 공용) |
| `WALLET4003` | 422 | 비활성 지갑입니다. (지갑 status가 ACTIVE가 아님 — SUSPENDED/CLOSED. 송금 송수신·충전 차단. 요청은 정상이나 지갑 상태로 처리 불가) |

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
| `TRANSFER4008` | 429 | 송금 PIN 입력 횟수를 초과해 일시적으로 잠겨 있습니다. (5회 연속 실패 시 10분 잠금, 24h 누적 15회 시 24h 잠금 — 단기·장기가 코드 공유라 잠금 시간 무관 메시지로 일반화, WSCH-06) |
| `TRANSFER4009` | 400 | 송금 PIN이 설정되지 않았습니다. |
| `TRANSFER4010` | 428 | 송금 전 PIN 검증이 필요합니다. (TX-PIN — `pin-verify` 성공 마커 없이 송금 실행/정기송금 설정 호출. 서버측 단명 마커(`pin:verified:{user}`, 단일사용)로 강제) |

### 환전 (EXCHANGE)
| code | HTTP | 의미 |
| --- | --- | --- |
| `EXCHANGE4001` | 404 | 존재하지 않는 환전 내역입니다. |
| `EXCHANGE4002` | 400 | 환율 견적이 만료되었습니다. |
| `EXCHANGE4003` | 422 | 환전 금액이 너무 작습니다. (신청액이 작아 수령액이 0으로 반올림 — 견적 생성 시 fail-fast) |

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

### 커뮤니티 (COMMUNITY)
| code | HTTP | 의미 |
| --- | --- | --- |
| `COMMUNITY4001` | 404 | 존재하지 않는 게시글입니다. |
| `COMMUNITY4002` | 404 | 존재하지 않는 댓글입니다. |
| `COMMUNITY4003` | 400 | 지원하지 않는 언어입니다. (번역 화이트리스트 외) |
| `COMMUNITY4004` | 400 | 본문이 너무 깁니다. (번역 비용 캡 초과) |
| `COMMUNITY4005` | 403 | 커뮤니티 활동이 제한된 계정입니다. |
| `COMMUNITY4006` | 409 | 이미 신고한 콘텐츠입니다. (중복 신고 — reporter+targetType+targetId 유니크 위반) |
| `COMMUNITY4007` | 400 | 지원하지 않는 신고 사유입니다. (SPAM/ABUSE/FRAUD/SEXUAL/ETC 외 값) |

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
| `category` (커뮤니티) | `LIFE_INFO` / `JOB` / `VISA` / `COUNTRY` / `RESIDENCE` / `QUESTION` / `FREE` |

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

> **환경별 `public_id` claim 노출 경로(인프라 전제 — 백엔드 코드 밖):**
> - **dev(Authentik)**: 가입 시 `attributes.public_id` 저장(백엔드) + Provider **Property Mapping**이 claim으로 노출. subject mode는 UUID 기반으로 설정해야 `authProviderId`↔`sub`가 일치한다(`RealIdpUserClient` 주석 참고).
> - **stage/prod(Cognito)**: 백엔드(`CognitoIdpUserClient`)는 `custom:public_id` **attribute 저장까지만** 책임진다. claim 노출은 User Pool의 **Pre Token Generation Lambda** 소관(`custom:public_id` → `public_id`). **풀 요구사항 체크리스트**: ① 커스텀 attribute `custom:public_id` 정의(미정의 시 `AdminCreateUser` 자체가 실패 → 가입 전부 COMMON5000) ② Pre Token Generation Lambda 연결(미설정 시 토큰에 claim이 없어 **모든 `@CurrentUserPublicId` 엔드포인트가 AUTH4011**) ③ 환경 개통 시 가입→로그인→`GET /api/v1/members/me` 스모크로 claim 노출을 확인한다.

```java
@CurrentUserPublicId String userPublicId   // = jwt.getClaimAsString("public_id")
```

- claim이 없으면 조용히 통과시키지 말고 `AUTH4011`로 fail-fast. 값을 쓰지 않는 엔드포인트(목록·마스터 조회 등)는 파라미터를 생략하고 `authenticated()`로만 보호한다.
- 인증 실패(JWT 누락/무효) 에러 코드는 **`AUTH4011`**(common-security `RestAuthenticationEntryPoint`가 처리), 권한 없음은 `COMMON4031`을 재사용한다(도메인 인증 코드 신설 금지).
- 각 `api-spec.md`의 "Auth ✅" 표기는 인증이 필요한 엔드포인트라는 의미다.

> **적용 현황:** member · wallet · community · document **전 서비스 적용 완료.** 모든 컨트롤러는 `@CurrentUserPublicId String userPublicId` 로 본인 식별자를 받으며, `@RequestHeader("X-User-Public-Id")` 임시 처리는 더 이상 사용하지 않는다.

---

## 15. 민감정보 컬럼 암호화 (PII) ★ Claude Code 주의

신분증 번호·계좌번호 같이 **고민감 PII는 DB에 평문으로 두지 않는다.** 애플리케이션 레이어에서 AES-256-GCM으로 자동 암복호한 뒤 DB에는 ciphertext만 적재한다. RDS 스냅샷·백업·슬로우쿼리 로그 등 디스크에 남는 모든 경로에서 평문 노출을 차단하는 게 목적이다.

### 15-1. 구현 방식 — `EncryptedStringConverter`

JPA `AttributeConverter`로 영속/조회 시점에 투명하게 변환한다. 서비스/리포지터리 코드는 평문 문자열을 다루듯 작성하면 되고, 컨버터가 자동으로 끼어든다.

```java
@Convert(converter = EncryptedStringConverter.class)
@Column(name = "document_number", length = 255, nullable = false)
private String documentNumber;
```

- 위치: `member-service/global/security/crypto/` (현 사용처가 1곳뿐이라 서비스 내부에 둠. 타 도메인으로 확산되면 `common-crypto` 모듈로 승격 — CLAUDE §2 "메커니즘 common, 구체 서비스" 원칙).
- 구성 빈 3종:
  - `CryptoProperties` — `@ConfigurationProperties("gb.crypto")` 레코드, env `GB_CRYPTO_KEY` 바인딩.
  - `AesGcmCryptoService` — AES-256-GCM 암복호. 빈 생성 시 키 길이(32B) 검증 fail-fast.
  - `EncryptedStringConverter` — `AttributeConverter<String, String>`, `@Component` + `@Converter(autoApply = false)`.
- 등록은 `CryptoConfig`의 `@EnableConfigurationProperties(CryptoProperties.class)`로 처리.

### 15-2. 알고리즘 — AES-256-GCM (CBC 아님)

- **AEAD(인증암호화)** 라 변조 시 `AEADBadTagException` 발생 → 손상된 ciphertext로 인한 무성 복호화 사고 차단.
- IV는 호출마다 12B 랜덤(`SecureRandom`), 인증 태그 128bit.
- 컬럼 저장 포맷: `Base64( IV(12B) || ciphertext || tag(16B) )` — 단일 VARCHAR로 깔끔히 처리.
- **결정성 없음** — 같은 평문도 매번 다른 ciphertext가 나온다. 따라서 컬럼에 `equals`·`LIKE` 검색이 불가능하다.
  - 검색·중복확인이 필요해지면 별도 `*_hash` 컬럼(HMAC-SHA256 with peppered key)을 추가하는 방식으로 푼다. 현재 적용 컬럼(`document_number`)은 검색 요구가 없어 hash 컬럼을 두지 않았다.

### 15-3. 컬럼 길이 산정

평문 N자(UTF-8 N B 가정)일 때 GCM 출력 = `12 + N + 16` 바이트. Base64 인코딩 후 `ceil((N+28) / 3) × 4` 자.

| 평문 한계 | 권장 컬럼 길이 (`VARCHAR`) |
| --- | --- |
| ~16자 | 64 |
| ~50자 | 128 |
| ~100자 | **255** ← 현재 `document_number` 기준 |
| ~180자 | 512 |

> 평문 컬럼을 그대로 두면 안 되고, **암호화 적용 시 컬럼 길이를 반드시 확장**한다. 기존 컬럼이 `VARCHAR(100)`이었다면 ciphertext가 잘려 복호화가 깨진다.

### 15-4. 키 관리

- 운영/개발기는 yml에 평문 키를 적지 않는다. **환경변수 `GB_CRYPTO_KEY`로만 주입**한다(application yml은 `${GB_CRYPTO_KEY}` 참조).
- 키 형식: Base64(32B = 256bit). 생성: `openssl rand -base64 32`.
- 테스트 프로파일(`application-test.yml`)은 고정 더미 키(Base64 32B all-zero) 사용 — 비밀 아님, 인메모리 H2에만 적용.
- 키 누락/형식오류/길이오류는 `AesGcmCryptoService` 빈 생성 시점에 `IllegalStateException`으로 fail-fast. 부팅 시 즉시 발견된다.
- **운영 전환 시 AWS KMS Envelope Encryption 도입 예정** — CMK가 Data Key를 발급, Data Key로 컬럼 암호화, 암호화된 Data Key는 별도 컬럼에 보관. CMK 연 1회 로테이션. (별도 이슈에서 다룬다)

### 15-5. 적용 컬럼 표

| 테이블.컬럼 | 컬럼 타입 | 적용 상태 | 비고 |
| --- | --- | --- | --- |
| `user_verifications.document_number` | VARCHAR(255) | ✅ 적용 | 외국인등록번호·여권번호·본국 신분증 번호. PR #141 |
| `bank_accounts.account_number` | VARCHAR(100) | ⏳ 후속 | "암호화 권장"(database.md). 컬럼 길이 확장 + 컨버터 적용 별도 이슈 |

> **새 PII 컬럼을 추가할 때 체크리스트:** ① 엔티티 필드에 `@Convert(converter = EncryptedStringConverter.class)` ② 컬럼 길이를 §15-3 표 기준으로 확장 ③ 응답 DTO에 노출하지 않는지 확인 (또는 마스킹) ④ 로그/`toString`에 새지 않는지 확인 ⑤ 위 §15-5 표에 등록.

### 15-6. 운영 주의

- **응답 DTO에는 PII를 노출하지 않는다.** 노출이 필요하면 마스킹(예: `9901**-*****01`) 응답을 별도 필드로.
- **로그에 평문 PII가 새지 않도록** 엔티티/Request DTO의 `toString`을 점검한다. Lombok `@ToString.Exclude`로 가린다.
- DB에 직접 native SQL을 날려서 raw 컬럼을 봐야 할 때, 값은 ciphertext다. 평문 비교가 필요하면 애플리케이션을 통과시키거나 같은 키로 암호화해 비교해야 한다(결정성 없음에 유의 — IV 랜덤이라 매번 다름).
- 키 교체(rotation)는 단순 yml 교체로 불가능하다. 기존 데이터가 옛 키로 복호화 가능해야 하므로, **dual-key 단계(옛 키 fallback)** 또는 **재암호화 마이그레이션 배치**가 필요하다. KMS 도입 이슈에서 같이 다룬다.
- **`@DataJpaTest` 슬라이스에서는 crypto 빈을 `@Import`해야 한다.** `@Convert` 대상 엔티티가 같은 EntityManagerFactory에 로드되는 한 Hibernate가 컨버터 빈을 요구하기 때문이다. `@DataJpaTest`는 일반 `@Component`를 스캔하지 않으므로 누락 시 `NoSuchBeanDefinitionException`이 난다.
  ```java
  @DataJpaTest
  @ActiveProfiles("test")
  @AutoConfigureTestDatabase(replace = Replace.NONE)
  @Import({CryptoConfig.class, AesGcmCryptoService.class, EncryptedStringConverter.class})
  class XxxRepositoryTest { ... }
  ```
  적용 대상 엔티티가 없는 다른 서비스(wallet·community·document)의 `@DataJpaTest`는 영향 없다.
