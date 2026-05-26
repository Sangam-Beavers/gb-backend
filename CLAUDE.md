# CLAUDE.md — gb-backend 백엔드 개발 지침

> 이 문서는 **Claude Code가 읽는 프로젝트 규칙**이자, **팀원이 참고하는 백엔드 개발 가이드**다.
> 새 코드를 작성하거나 리뷰할 때 이 규칙을 따른다.
> 살아있는 예시는 **wallet-service의 전자지갑 잔액 조회**(`GET /api/v1/wallets/me/balances`)다.
> 새 기능을 만들 때 이 레퍼런스의 계층 구조·네이밍·변환 방식을 그대로 따라가면 된다.

---

## 1. 프로젝트 개요

- 외국인 근로자 플랫폼 백엔드. **MSA + Gradle 멀티모듈 모노레포**.
- Java 17, Spring Boot 3.5.x, Gradle(Groovy), MySQL 8.0, Redis 7.x, Kafka.
- 서비스 분리: `member` / `wallet` / `document` / `community`.
  기능을 과하게 잘게 쪼개지 않고 도메인 단위로 묶는다(Chatty API 지양).
- 인증은 Authentik(개발)/Cognito(운영) 기반 OIDC. **현재 인증은 미구현 상태**이며,
  인증이 필요한 곳은 임시 처리 + `// TODO` 주석으로 표시한다(아래 9번 참고).

---

## 2. 모듈 구조와 의존 방향

```
common/
  common-response/    ApiResponse, ErrorResponse, SuccessStatus
  common-exception/   ErrorCode(interface), CommonErrorCode, BusinessException, GlobalExceptionHandler
  common-security/    (인증 확정 후 작성 — 현재 보류)
services/
  wallet-service/  member-service/  document-service/  community-service/
```

- **의존 방향(단방향)**: `서비스 → common-exception → common-response`.
  common은 어떤 서비스에도 의존하지 않는다.
- **메커니즘은 common, 구체 내용은 서비스**:
    - 응답 포맷·예외 처리 메커니즘 → common
    - 서비스별 에러 코드(`WalletErrorCode` 등)·`SecurityConfig`·도메인 enum → 각 서비스
- common 연동 상세는 `docs/common-module-integration.md` 참고.
- **각 서비스 메인 클래스는 `@SpringBootApplication(scanBasePackages = "com.gb")`** 로 둔다.
  (common의 `GlobalExceptionHandler`가 `com.gb.common...`에 있어 스캔 범위를 넓혀야 빈 등록됨)

---

## 3. 패키지 구조 (모든 서비스 동일)

```
com.gb.{서비스}/
├── {Service}Application.java        // scanBasePackages = "com.gb"
├── domain/
│   └── {도메인}/
│       ├── controller/
│       ├── service/
│       │   └── impl/
│       ├── repository/
│       ├── entity/
│       └── dto/
│           ├── request/
│           └── response/
└── global/
    ├── config/        // SecurityConfig, RedisConfig, SwaggerConfig, JpaConfig
    ├── exception/
    │   └── code/      // {서비스}ErrorCode.java (ErrorCode 구현)
    ├── redis/
    └── common/
        └── enums/     // 서비스 공통 enum, BaseEntity 등
```

- 계층형(controller/, service/ 를 최상위)이 아니라 **도메인형**으로 묶는다.

---

## 4. 계층별 작성 규칙

레퍼런스(잔액 조회)의 흐름이 표준이다:
`Controller → Service(interface+impl) → Repository → Entity / DTO → ApiResponse`

### Entity
- `@Entity`. **`@Setter` 금지**(불변성). `@Getter` + `@NoArgsConstructor(access = PROTECTED)` + `@Builder`.
- 생성자/빌더에서 필수값 검증(null 등)을 둘 수 있다. 단 검증 실패 예외는 6번 규칙을 따른다.
- 공통 시각 필드(`createdAt`, `updatedAt`)는 **`BaseEntity`(@MappedSuperclass + JPA Auditing)** 로 분리하고 상속한다.
- 금액·잔액·환율은 **반드시 `BigDecimal`** (DB는 `DECIMAL(18,4)`). `double`/`float` 금지.

### Repository
- `JpaRepository<T, Long>` 상속. 메서드 네이밍 규칙으로 충분하면 `@Query` 쓰지 않는다.
- 단건 조회는 `Optional`, 목록은 `List` 반환.

### DTO
- **Entity → DTO 변환은 `Response.from(entity)` 정적 메서드**.
- **Request → Entity 변환은 `Entity.of(request)` 정적 메서드**.
- 응답/요청 DTO는 `dto/response`, `dto/request`로 분리.

### Service
- **interface + impl 구조**. impl은 `@Service`, 조회는 `@Transactional(readOnly = true)`.
- 비즈니스 로직은 Service에. 예외는 `BusinessException`으로만 던진다(6번).

### Controller
- `@RestController`. **로직 없이** Service 호출 + 응답 래핑만.
- 응답은 **반드시 `ApiResponse`로 감싼다**. 엔티티/DTO 직접 반환 금지.

---

## 5. API · 응답 규약 (명세 SSOT 준수)

> API 설계의 단일 진실 공급원(SSOT)은 **팀 API 명세서**다. 아래는 핵심 요약이며, 충돌 시 명세서가 우선.

- **Base URL** `/api/v1`. URL 단어 구분은 하이픈(`-`), JSON 필드는 **snake_case**.
- snake_case 변환은 전역 설정으로 처리한다:
  `application.yaml` 의 `spring.jackson.property-naming-strategy: SNAKE_CASE`.
  → DTO 필드는 camelCase로 두고 `@JsonProperty`를 붙이지 않는다.
- **식별자**: 외부 노출은 `public_id`(UUID). 내부 `id`(BIGINT)는 응답/URL에 노출 금지.
- **금액·환율**: 응답에서 **string**으로 전송(`"1530000.0000"`). 계산은 `BigDecimal`.
  표시 전용 수치(등락률 등)만 number 허용.
- **시각**: ISO 8601 **UTC `Z`** 문자열(`"2026-05-26T04:15:30Z"`).
- **enum 값**: `SCREAMING_SNAKE_CASE`.
- **HTTP 메서드**: GET/POST/PATCH/DELETE. **PUT 미사용**. 생성 201, 그 외 200.

### 공통 응답 Envelope
```json
// 성공 (code 없음, data는 null이어도 노출)
{ "success": true, "data": { ... }, "message": "요청이 성공적으로 처리되었습니다." }

// 실패 (data 키 없음)
{ "success": false, "code": "WALLET4001", "message": "존재하지 않는 지갑입니다." }
```

---

## 6. 예외 처리

- 비즈니스 예외는 **`BusinessException` + `ErrorCode`로만** 던진다.
  `throw new IllegalArgumentException(...)` 등 임의 예외를 컨트롤러/서비스 흐름에서 쓰지 않는다.
- 서비스별 에러 코드는 `{서비스}ErrorCode`(enum)가 common의 **`ErrorCode` 인터페이스를 구현**한다.
- **에러 코드는 명세 §12를 SSOT로** 등록한다. 번호를 임의로 추측하지 말 것.
    - 형식: `{DOMAIN}{4자리}` (예: `WALLET4001`, `WALLET4002`).
    - 같은 의미면 코드 하나로 통일. 번호는 한번 부여하면 재사용·재배치 금지.
    - 서버 측 잘못은 도메인 코드 신설 대신 `COMMON5000` 사용.
    - 인증 실패(JWT 누락/무효)는 `COMMON4011`, 권한 없음은 `COMMON4031` 재사용.
- 던져진 예외는 common의 `GlobalExceptionHandler`가 위 실패 Envelope로 변환한다.
- 입력값 검증은 `@Valid` + Bean Validation. 검증 실패는 `COMMON4001`로 처리됨.

---

## 7. MSA 서비스 간 참조 규칙

- **서비스 경계를 넘는 회원 참조는 `user_public_id`(UUID)** 로 한다. **물리 FK 금지**.
    - 다른 서비스(member)의 `User` 엔티티를 직접 매핑(`@ManyToOne`)하지 않는다.
    - 회원은 `String userPublicId` 값으로만 보유. 회원 상세가 필요하면 서비스 간 통신으로.
- **같은 서비스(스키마) 내부 참조는 기존대로 `id`(BIGINT) FK** 를 사용한다.
    - 예: `wallet_balances.wallet_id → wallets.id` 는 정상 FK.
- 즉 "경계 넘으면 public_id, 내부는 BIGINT FK".

---

## 8. DB / 환경

- MySQL은 K8s에 파드로 띄우고 **스키마를 서비스별로 분리**(논리적 분리).
- 잔액(`wallet_balances`)은 **절대 캐시하지 않고 항상 DB에서 직접 조회**한다.
- 로컬에서 코드 실행 시 DB/Redis 연결이 필요하다. 미구성 상태에서는
  `compileJava`로 컴파일까지만 검증하고, 실제 기동·호출 검증은 DB 연결 후 수행한다.

---

## 9. 인증 (현재 미구현 — 임시 처리)

- 인증(Authentik/OIDC)은 아직 구현 전이다. `common-security`는 보류.
- 인증된 사용자 식별자(`userPublicId`)가 필요한 곳은 **임시로 헤더 수신** + TODO 표시:
  ```java
  // TODO: 인증 구현 후 JWT(sub/claim)에서 userPublicId 추출로 교체.
  //       현재는 인증 미구현으로 헤더(X-User-Public-Id)로 임시 수신.
  @RequestHeader("X-User-Public-Id") String userPublicId
  ```
- 인증 확정 시 이 부분만 토큰 추출로 교체한다.

---

## 10. 커밋 · 브랜치

- 커밋 컨벤션: `Feature` / `Fix` / `Refactor` / `Docs` / `Chore` / `Test` /
  `Build` / `Ci` / `Style` / `Plus`(의존성 추가) / `Minus`(의존성 제거) 등.
  예: `Feature: 전자지갑 잔액 조회 기능 구현`
- 브랜치: `feature/{기능}` → `develop` PR(승인 1명 + CodeRabbit 리뷰) → 머지.
  `main`/`develop`은 force push 금지.
- `.claude/` 등 로컬 설정 폴더는 커밋하지 않는다(.gitignore 처리).

---

## 11. Claude Code 작업 시 유의

- 새 기능은 **계층을 끊어서**(엔티티 → repository → DTO → service → controller) 작성하고,
  각 단계마다 `./gradlew :services:{서비스}:compileJava`로 컴파일 검증한다.
- 파일 **삭제는 임의로 하지 말 것**. 삭제 후보는 목록으로 보고하고 사람이 확인 후 삭제한다.
- 에러 코드·응답 필드는 추측하지 말고 **명세서를 확인**해서 1:1로 맞춘다.
- common 모듈 변경은 4개 서비스 전체에 영향을 주므로 신중히. 동작을 바꾸는 변경은
  팀 합의가 필요하다(안전장치 추가 정도는 가능).

---

> **참고 문서**
> - `docs/common-module-integration.md` — common 모듈 연동 상세
> - 팀 API 명세서 — API/에러코드/enum의 SSOT
> - 레퍼런스 코드 — `wallet-service`의 잔액 조회(`/api/v1/wallets/me/balances`) 전 계층