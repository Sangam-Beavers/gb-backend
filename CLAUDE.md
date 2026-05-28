# CLAUDE.md — gb-backend 백엔드 개발 지침

> 이 문서는 **Claude Code가 읽는 프로젝트 규칙**이자, **팀원이 참고하는 백엔드 개발 가이드**다.
> 새 코드를 작성하거나 리뷰할 때 이 규칙을 따른다.
> 살아있는 예시는 **wallet-service의 전자지갑 잔액 조회**(`GET /api/v1/wallets/me/balances`)와
> **최근 송금 앱 사용자 조회**(`GET /api/v1/transfers/recent-recipients/members`)다.
> 새 기능을 만들 때 이 레퍼런스의 계층 구조·네이밍·변환 방식을 그대로 따라가면 된다.

---

## 1. 프로젝트 개요

- 외국인 근로자 플랫폼 백엔드. **MSA + Gradle 멀티모듈 모노레포**.
- Java 17, Spring Boot 3.5.x, Gradle(Groovy), MySQL 8.0, Redis 7.x, Kafka.
- 서비스 분리: `member` / `wallet` / `document` / `community`.
  기능을 과하게 잘게 쪼개지 않고 도메인 단위로 묶는다(Chatty API 지양).
- 인증은 Authentik(개발)/Cognito(운영) 기반 OIDC. **현재 인증은 미구현 상태**이며,
  인증이 필요한 곳은 임시 처리 + `// TODO` 주석으로 표시한다(아래 9번 참고).
- **AI 서류 분석**은 별도 AWS 계정(계정 B)의 Lambda + Bedrock(Claude) + **S3 Vectors**(법령 RAG)에서
  처리된다. 본체(`document-service`)는 분석 요청(Pre-signed URL 발급)과 결과 수신(SQS Consumer)만
  담당하고, 분석 자체는 본체 밖에서 돈다. 상세: `docs/document-analysis/ai-pipeline.md`.
- **충전·현금화**는 외부 Mock 은행 서버(Beaver/Quokka Bank)를 호출해 시뮬레이션한다(구현 수준 2).
  `wallet-service`는 `BankClient` 인터페이스로 호출하고, 실서비스 전환 시 구현체/URL만 교체한다.
  상세: `docs/remittance/api-spec.md` §13.

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
    ├── client/        // 다른 서비스/외부 시스템 호출 인터페이스 + 구현체 (7번 참고)
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
- DB의 `BIGINT FK` 컬럼은 **같은 서비스(스키마) 내부 참조에 한해** JPA `@ManyToOne`(LAZY, 단방향) 객체로
  매핑한다(예: `transactions.wallet_id` → `@ManyToOne Wallet`). ERD의 "BIGINT FK" 표기는 DB 레벨 표현이고
  JPA 코드의 객체 매핑은 관행이며, 둘은 모순이 아니다(다른 레벨의 표현). 단 MSA 경계를 넘는 참조
  (`user_public_id` 등)는 객체 매핑하지 않는다(7번).
- 아직 만들지 않은 엔티티를 가리키는 FK는 임시로 원시 `Long` 컬럼 + `// TODO`로 두고, 해당 엔티티 작성 시
  `@ManyToOne`으로 교체한다(예: `bank_account_id` — BankAccount 미작성 동안 `Long`).

### Repository
- `JpaRepository<T, Long>` 상속. 메서드 네이밍 규칙으로 충분하면 `@Query` 쓰지 않는다.
- 단건 조회는 `Optional`, 목록은 `List` 반환.
- 그룹핑·집계 등 메서드 네이밍으로 표현하기 어려운 쿼리는 `@Query`(JPQL). 한 쿼리로 잡기 까다로운
  보조 데이터(예: 최근 송금 수신자별 `last_currency_code`)는 Service에서 추가 조회로 조합해도 된다.
  여러 건을 채울 땐 건별 반복(N+1) 대신 `IN` 절 batch 1회를 우선한다.

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

### Swagger 문서화 (SpringDoc)
- 각 서비스에 `springdoc-openapi-starter-webmvc-ui`를 둔다. **버전은 2.8.14 사용.**
  - 3.0.x는 Spring Boot 4 전용이라 금지(Boot 3.5와 비호환).
  - 2.8.15~2.8.17은 `/swagger-ui/**/*swagger-initializer.js` 잘못된 경로 패턴을 등록하는 회귀 버그로
    기동 실패한다(`spring.mvc.pathmatch.matching-strategy=ant_path_matcher`로도 해결 안 됨).
    2.8.14가 2.8 계열 마지막 정상 버전. 패치 버전이 나오면 재상향한다.
- 컨트롤러: `@Tag`(컨트롤러) + `@Operation`(summary/description) + `@ApiResponses`(주요 응답).
  - `@ApiResponse`의 `responseCode`에는 **HTTP 상태**("200"/"404")를 넣고, `description`에 비즈니스 코드와
    의미(예: "WALLET4001 - 존재하지 않는 지갑")를 적는다. 커스텀 코드를 responseCode 자리에 넣지 않는다.
- 응답 DTO 필드에는 `@Schema`로 설명/예시(enum은 allowableValues).
- 인증 미구현 동안 `X-User-Public-Id` 헤더는 `@RequestHeader` 선언만으로 자동 문서화된다
  (SwaggerConfig의 글로벌 헤더 customizer는 이미 선언된 경우 중복 추가하지 않는다).
- SecurityConfig에서 `/swagger-ui/**`, `/v3/api-docs/**`는 통과(현재 permitAll이라 자동 통과).
- UI: `http://localhost:{port}/swagger-ui/index.html`. 서비스별로 뜨며, 통합 뷰는 추후 API Gateway에서.

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

### 외부 시스템 연동 (본체 밖 호출)

- **일반 원칙 — `client` 인터페이스 패턴:** 다른 서비스(member 등)나 외부 시스템(은행 등)을 호출할 때는
  `global/client/` 아래에 **인터페이스를 먼저 정의**하고 구현체를 Profile로 분리한다. Service는
  인터페이스에만 의존하므로, Mock ↔ 실제 구현을 갈아끼워도 **Service 코드는 바뀌지 않는다.**
  - 인터페이스 `XxxClient` (예: `MemberClient`, `BankClient`)
  - 응답 DTO: 호출 대상의 응답 형태를 모사한다 (예: `MemberInfo`)
  - 구현체: `MockXxxClient`(`@Profile("dev")`) = 미구현/개발용, `RealXxxClient`(`@Profile("!dev")`) = 실제 호출
  - **MSA 경계 데이터는 DB 직접 참조 금지.** 다른 서비스의 엔티티/테이블을 직접 SELECT하지 않고
    반드시 client(=API 호출)를 통해 받는다. (예: wallet이 member 정보를 wallet_db에서 읽으면 안 된다.
    회원 정보가 wallet 스키마에 중복 저장되는 구조도 금지 — 데이터 일관성이 깨진다.)
  - 없는 데이터 조회 정책: 개발용 Mock은 fallback(예: "Unknown") 반환이 편하나, 운영 구현체는
    조용한 가짜 데이터보다 fail-fast(예외)를 권장한다(금융 데이터 정합성).
  - **현재 예시:** `member-service` 미구현이라 `MemberClient` + `MockMemberClient`(고정 데이터)로 처리.
    member-service 구현 후 `RealMemberClient`(@Profile !dev)만 추가하면 Service 변경 없이 전환된다.
    진짜 호출 전환 시 건별 호출이 N번 발생하므로, 그때 member-service의 batch 조회 API 도입을 검토한다
    (현재 Service에 `// TODO` 표시).
- **Mock 은행(충전/현금화):** `wallet-service`는 외부 Mock 은행 서버를 **`BankClient` 인터페이스**로만 호출한다.
  구현체는 Profile로 분리(`MockBankClient`=dev/stage, `RealBankClient`=prod), base URL은 환경변수
  `BANK_API_BASE_URL`로 분리한다. 충전은 `withdrawal`(외부계좌 차감), 현금화는 `payout`(외부계좌 증액).
  계좌 인증 시 받은 `account_token`을 `bank_accounts.mock_account_token`에 저장해 충전 때 사용한다.
  은행 측 에러(`BANK####`)는 본체 에러(`ACCOUNT####`/`COMMON####`)로 매핑한다(매핑표: `docs/remittance/api-spec.md` §13-4).
  Mock 은행은 본체와 **장부(DB)가 완전 분리** — 본체 MySQL(앱 포인트)과 은행 SQLite(외부 현금)는 서로 직접 만지지 않는다.
- **AI 분석 결과(계정 B → 본체):** `document-service`는 분석 결과를 **SQS Consumer**로 수신해
  `document_submissions`/`document_results`에 저장한다. 분석 요청(`source=production/development`)의
  출처에 따라 결과 경로가 갈리며, 운영기는 SQS, 개발기는 온프렘 직접 INSERT(계정 B 소관)다.
  본체(운영기)는 SQS Consumer만 구현하면 된다. 상세: `docs/document-analysis/ai-pipeline.md`.

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

## 10. 테스트

> 금융 도메인이라 잔액 변경·멱등성 등 핵심 로직은 **반드시 테스트 코드로** 검증한다.
> 수동 INSERT로 운영/개발기 DB를 검증에 쓰지 않는다(데이터를 더럽히고 반복·자동화가 안 된다).

- **실행 기반:** JUnit5(Jupiter). **루트 `build.gradle`에 `useJUnitPlatform()`이 반드시 있어야** 테스트가
  실행된다. 없으면 Gradle이 JUnit4 러너로 동작해 `@Test`(Jupiter)를 못 찾고 "No tests found"로 조용히
  지나가, 테스트가 없는데도 통과처럼 보인다. 루트에 일괄 적용:
  `subprojects { tasks.named('test') { useJUnitPlatform() } }`.
- **테스트 DB:** 개발기 DB 사용 금지. **H2 인메모리 + MySQL 호환 모드**를 쓴다.
  `src/test/resources/application-test.yml`에 `jdbc:h2:mem:testdb;MODE=MySQL`, `ddl-auto: create-drop`,
  `@ActiveProfiles("test")`.
  - H2는 MySQL과 방언 차이가 있을 수 있다(복잡한 GROUP BY/함수 등). 현재 최근 송금 조회의
    GROUP BY + MAX + IN 쿼리는 H2(MySQL 모드)에서 정상 동작 확인됨. 단, **돈을 움직이는 핵심 금융
    로직**(송금 실행·잔액 차감·멱등성 등)은 추후 **Testcontainers(진짜 MySQL)** 전환을 검토한다.
- **Repository 테스트(통합):** `@DataJpaTest` + `@ActiveProfiles("test")`
  + `@AutoConfigureTestDatabase(replace = NONE)`(우리가 지정한 H2를 쓰도록. 안 주면 기본 임베디드로 덮어씀).
    정렬·그룹핑·중복 제거뿐 아니라 **필터링(제외돼야 할 데이터가 안 나오는지)** 도 검증한다
    (예: status=FAILED·다른 type·receiver=null 거래는 결과에서 빠져야 함).
- **Service 테스트(단위):** `@ExtendWith(MockitoExtension.class)` + `@Mock`(Repository/Client) + `@InjectMocks`.
  DB·스프링 컨텍스트 없이 빠르게 조합 로직·예외(`BusinessException`)·early return을 검증한다.
  예외/빈 결과 시 불필요한 의존 호출이 없는지 `verify`로 확인한다.
- **컨텍스트 로딩 테스트:** `@SpringBootTest`는 `@ActiveProfiles("test")` + 외부 의존을 `@MockitoBean`으로
  가린다(예: `MemberClient`). `@MockBean`은 Boot 3.4부터 deprecated이므로 `@MockitoBean`(Boot 3.5 정식)을 쓴다.
- **시각 통제 주의:** `@CreatedDate`(JPA Auditing)는 `@PrePersist`에서 값을 무조건 덮어쓴다. 테스트에서
  `createdAt`을 특정 값으로 고정하려면 **persist 후 native UPDATE**로 설정한다(auditing 활성/비활성 어느
  쪽에서도 안전). reflection으로 박은 값은 auditing이 켜지면 덮어써져 테스트가 환경 따라 깨진다(flaky).
- **검증 라이브러리:** AssertJ(`assertThat`)·Mockito는 `spring-boot-starter-test`에 포함(별도 설치 불필요).
  테스트 메서드명은 한글 가독성을 허용한다.

---

## 11. 커밋 · 브랜치

- 커밋 컨벤션: `Feature` / `Fix` / `Refactor` / `Docs` / `Chore` / `Test` /
  `Build` / `Ci` / `Style` / `Plus`(의존성 추가) / `Minus`(의존성 제거) 등.
  예: `Feature: 전자지갑 잔액 조회 기능 구현`
- 브랜치: `feature/{기능}` → `develop` PR(승인 1명 + CodeRabbit 리뷰) → 머지.
  `main`/`develop`은 force push 금지.
- `.claude/` 등 로컬 설정 폴더는 커밋하지 않는다(.gitignore 처리).

---

## 12. Claude Code 작업 시 유의

- 새 기능은 **계층을 끊어서**(엔티티 → repository → DTO → service → controller) 작성하고,
  각 단계마다 `./gradlew :services:{서비스}:compileJava`로 컴파일 검증한다.
- 파일 **삭제는 임의로 하지 말 것**. 삭제 후보는 목록으로 보고하고 사람이 확인 후 삭제한다.
- 에러 코드·응답 필드는 추측하지 말고 **명세서를 확인**해서 1:1로 맞춘다.
- common 모듈 변경은 4개 서비스 전체에 영향을 주므로 신중히. 동작을 바꾸는 변경은
  팀 합의가 필요하다(안전장치 추가 정도는 가능).
- 라이브러리/프레임워크 버전은 추측하지 말고 확인한다(특히 Spring Boot 3.5 호환성: SpringDoc 2.8.14 등).
  호환성 문제가 의심되면 임의로 버전을 바꾸기 전에 원인(이슈/CHANGELOG)을 조사해 보고한다.

---

> **참고 문서**
> - docs/README.md — docs 전체 인덱스 (기능별 문서 진입점)
> - docs/tech-stack.md — 기술 스택 + 데이터 저장 정책 (벡터 DB = S3 Vectors 등)
> - docs/architecture.md — 시스템/인프라 아키텍처, 계정 A/B 분리, 계정 간 연동
> - docs/conventions.md — API/코딩 공통 규칙, 에러 코드 표, 인증 임시처리(§14)
> - docs/database.md — 테이블 스키마 + Redis 키 설계 (bank_accounts.mock_account_token 포함)
> - docs/{auth,remittance,document-analysis,community}/ — 기능별 requirements·flow·api-spec
    >   (remittance/api-spec.md §13 = Mock 은행 연동, document-analysis/ai-pipeline.md = AI 파이프라인)
> - docs/common-module-integration.md — common 모듈 연동 상세
> - 레퍼런스 코드 — wallet-service의 잔액 조회(/api/v1/wallets/me/balances),
    >   최근 송금 앱 사용자 조회(/api/v1/transfers/recent-recipients/members) 전 계층 + 테스트