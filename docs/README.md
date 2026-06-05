# Global Bridge — 개발 문서 (docs)

> 이 폴더는 **Claude Code가 개발 중 참조하는 단일 진실 공급원(SSOT)** 입니다.
> 코드를 작성하거나 수정하기 전에 관련 문서를 먼저 읽고, 문서와 코드가 충돌하면 **문서를 우선**합니다.
> (단, 더 최신 결정이 채팅/이슈로 합의되면 그 결정을 문서에 반영한 뒤 진행)
>
> 프로젝트 루트의 **`CLAUDE.md`** 가 최상위 개발 지침이며, 이 `docs/`는 그 지침의 세부(기능별 명세·DB·규칙)를 담습니다. 두 문서는 정렬되어 있습니다: 패키지 루트 `com.gb`, JSON snake_case(Jackson 전역 변환), OAuth2 Resource Server 인증(토큰 claim `public_id`로 본인 식별; document-service만 헤더 임시처리 잔존) 등. 충돌이 느껴지면 **CLAUDE.md(실제 코드 기준)** 를 우선합니다.

---

## 이 프로젝트가 무엇인가

**Global Bridge**는 한국에 거주하는 외국인 노동자를 위한 **금융 · AI 서류 분석 · 커뮤니티 통합 플랫폼**입니다.
세 가지 핵심 기능을 하나의 앱에서 제공합니다.

1. **송금 · 환전 (주머니)** — 다중 통화 전자지갑(주머니)에 충전하고, 앱 사용자 간 송금 / 타행 송금 / 실시간 환전
2. **AI 서류 분석** — 근로계약서 · 급여명세서 등을 업로드하면 AI가 위험 조항을 분석하고 모국어로 번역
3. **커뮤니티** — 생활정보 · 비자 · 거주 등 카테고리별 게시판, 인증 배지

자세한 내용은 [`project-overview.md`](./project-overview.md) 참고.

---

## 읽는 순서 (Claude Code 권장)

기능 개발을 시작하기 전, **항상 아래 루트 문서 4개를 먼저 읽으세요.** 어느 기능을 개발하든 공통으로 적용되는 규칙입니다.

| 순서 | 문서 | 무엇을 담고 있나 | 언제 보나 |
| --- | --- | --- | --- |
| 1 | [`project-overview.md`](./project-overview.md) | 서비스 개요, 도메인 용어, 사용자 시나리오 | 처음 한 번 |
| 2 | [`architecture.md`](./architecture.md) | MSA 구조, 인프라(EKS/VPC), AWS 계정 분리, 환경(개발/스테이징/운영) | 처음 한 번 |
| 3 | [`tech-stack.md`](./tech-stack.md) | Spring MVC / JPA / Redis / MySQL 선택 근거와 버전 | 처음 한 번 |
| 4 | [`conventions.md`](./conventions.md) | **API 설계 규칙, 응답 형식, 에러 코드, 금액·식별자·시각 규칙, 패키지 구조** | **매번 (가장 중요)** |
| 5 | [`database.md`](./database.md) | 14개 테이블 스키마 + MSA 경계 참조 규칙 + Redis 키 설계 | 데이터 만질 때마다 |
| 6 | [`common-module-integration.md`](./common-module-integration.md) | common 모듈(`common-response`/`common-exception`) 연동, 패키지·스캔 범위, ErrorCode 구현 | 새 서비스 세팅 시 |

그 다음, 개발할 기능의 폴더를 엽니다.

---

## 기능별 문서

각 기능 폴더는 `requirements.md`(무엇을) → `flow.md`(어떻게 흐르나) → `api-spec.md`(정확한 계약) 순서로 읽으면 됩니다.

| 기능 | 폴더 | 담당 도메인 prefix |
| --- | --- | --- |
| 인증 · 회원 · 마이페이지 | [`auth/`](./auth/) | `/auth`, `/members` |
| 송금 · 환전 · 충전 (주머니) | [`remittance/`](./remittance/) | `/wallets`, `/transfers`, `/exchanges`, `/accounts` |
| AI 서류 분석 | [`document-analysis/`](./document-analysis/) | `/documents` |
| 커뮤니티 | [`community/`](./community/) | `/community` |

추가로 AI 서류 분석 폴더에는 AWS 계정 B에서 도는 분석 파이프라인 상세가 별도로 있습니다: [`document-analysis/ai-pipeline.md`](./document-analysis/ai-pipeline.md)
그리고 분석 결과를 본 사용자의 **후속 질문 챗봇 + MCP** 통합 설계: [`document-analysis/ai-chatbot-mcp.md`](./document-analysis/ai-chatbot-mcp.md)

---

## 명세 정본(正本) 우선순위 — 충돌 시 이 순서로 판단

여러 자료가 어긋날 때 Claude Code는 아래 우선순위를 따릅니다.

1. **각 기능 폴더의 `api-spec.md`** — Notion 개별 상세 명세를 옮긴 것. **최우선 정본.**
2. **`conventions.md`의 전역 규칙(§0, §4, §5, §12 등)** — 위와 충돌 없을 때 보강 기준.
3. (참고용) Notion `API 엔드포인트 예시` 문서 — **구버전이라 신뢰도 낮음.** 경로가 다르면 1번을 따른다.

> 예) Google 로그인은 `/api/v1/auth/login/google`(정본)이며, 구버전 문서의 `/auth/google`는 무시한다.
> 예) 송금 실행은 2단계가 아니라 `POST /api/v1/transfers` **1단계 즉시 실행**이 정본이다.

---

## 핵심 결정 사항 (확정)

Claude Code가 반드시 지켜야 하는 프로젝트 차원의 결정입니다.

- **충전/출금 구현 = 수준 2 (Mock API).** 실제 PG/은행 연동 대신 동일 인터페이스의 외부 Mock 은행 서버(Beaver/Quokka Bank)로 처리한다. 본체는 `BankClient` 인터페이스로 호출하고 실서비스 전환 시 URL/구현체만 교체. 충전은 `withdrawal`(외부계좌 차감), 현금화는 `payout`(외부계좌 증액). 계좌 인증 시 받은 `account_token`을 `bank_accounts.mock_account_token`에 저장해 충전 때 사용. 상세 연동·에러 매핑: [`remittance/api-spec.md`](./remittance/api-spec.md) §13.
- **AI 분석 결과 저장 = MySQL `document_results`에 직접 저장. (분석 결과 한정) DynamoDB 미사용.** 결과는 **요청 출처(`source` 필드)에 따라 한 경로로만** 저장된다 — 운영기 요청(source="production")은 SQS→계정 A Aurora MySQL, 개발기 요청(source="development")은 Lambda B→EC2(HAProxy)→WireGuard→온프렘 개발기 MySQL 직접 INSERT. **양쪽 동시 저장이 아니라 요청한 환경으로만 결과가 돌아간다.** (개발기 온프렘 MySQL / 운영·스테이징 Aurora MySQL 공통 스키마)
- **후속 질문 챗봇 = 신규 추가 (기존 분석 흐름 무변경, "추가만").** 결과 화면 하단에 채팅 영역 1개 + `POST /api/v1/documents/{id}/chat` 1개만 추가한다. 대화기록은 분석 결과와 별개 워크로드라 **계정 B DynamoDB(`chat_sessions`, TTL 90일) + Redis 캐시(30분)** 를 신규 도입한다 — 위 "분석 결과 DynamoDB 미사용" 원칙과 저장 대상이 달라 충돌하지 않는다. 챗봇은 동기 + SSE 스트리밍, 권한 검증은 백엔드(Spring 2차 인가), 신규 에러코드 없음(기존 `COMMON4011/4031`, `DOCUMENT4001` 재사용). 상세: [`document-analysis/ai-chatbot-mcp.md`](./document-analysis/ai-chatbot-mcp.md).
- **법령 RAG = Bedrock Knowledge Bases로 통일 (백엔드 저장소 = Amazon S3 Vectors, 계정 B).** 분석 파이프라인과 챗봇 **양쪽 모두** 법령 검색을 KB `retrieve`로 호출한다(검색 코드 일원화). KB가 검색을 오케스트레이션하고 벡터는 S3 Vectors에 저장된다 — S3 Vectors는 빠지지 않고 KB 아래에 깔린다. AI VPC는 퍼블릭 + 프라이빗(=관리 서브넷) 2티어이며 DB 서브넷이 없다. 상세: [`document-analysis/ai-pipeline.md`](./document-analysis/ai-pipeline.md), [`document-analysis/ai-chatbot-mcp.md`](./document-analysis/ai-chatbot-mcp.md).
- **회원 식별자 보안 원칙** — `members.id`(BIGINT 순번)는 member 도메인 경계를 벗어나지 않는다. 도메인 밖에는 `user_public_id`(UUID)만 노출/전파한다.
- **금액·환율은 JSON `string` 십진수로 전송**한다. `number`(float) 금지. (표시용 수치 — 등락률·OCR 신뢰도 등 — 만 예외적으로 number 허용)
- **인증은 OAuth2 Resource Server(방식 B)로 구현됨.** 본인 식별은 토큰 claim `public_id`를 `@CurrentUserPublicId`로 추출한다. (member·wallet·community 적용 완료, document-service만 `@RequestHeader("X-User-Public-Id")` 헤더 임시처리 잔존 — conventions §14)
- **민감정보 컬럼 암호화 = `EncryptedStringConverter`(AES-256-GCM).** 신분증 번호 등 PII는 JPA `AttributeConverter`로 영속 시점에 자동 암복호 → DB에는 ciphertext(Base64)만 적재. 키는 환경변수 `GB_CRYPTO_KEY`(Base64 32B). 운영 전환 시 AWS KMS Envelope Encryption으로 교체 예정(별도 이슈). 상세: [`conventions.md` §15](./conventions.md#15-민감정보-컬럼-암호화-pii-★-claude-code-주의).
- **패키지 루트 `com.gb`**, 멀티모듈(`common` + `services`). JSON 필드는 snake_case이되 **DTO는 camelCase + Jackson 전역 변환**(`property-naming-strategy: SNAKE_CASE`).

---

## 폴더 트리

```
docs/
├── README.md                       ← 이 문서 (인덱스)
├── project-overview.md             ← 서비스 개요 · 도메인 용어 · 사용자 시나리오
├── architecture.md                 ← MSA · 인프라 · 환경 구성
├── tech-stack.md                   ← 기술 스택 선택 근거
├── conventions.md                  ← API/코딩 공통 규칙 (전역 SSOT)
├── database.md                     ← DB 스키마 + Redis 키 설계
├── common-module-integration.md    ← common 모듈 연동 규칙 (실제 코드 기준 정본)
│
├── auth/                           ← 인증 · 회원 · 마이페이지
│   ├── requirements.md
│   ├── flow.md
│   ├── api-spec.md
│   └── images/
├── remittance/                     ← 송금 · 환전 · 충전
│   ├── requirements.md
│   ├── flow.md
│   ├── api-spec.md
│   └── images/
├── document-analysis/              ← AI 서류 분석
│   ├── requirements.md
│   ├── flow.md
│   ├── api-spec.md
│   ├── ai-pipeline.md              ← AWS 계정 B 분석 파이프라인 상세
│   ├── ai-chatbot-mcp.md           ← 후속 질문 챗봇 + MCP 통합 설계 (신규)
│   └── images/
└── community/                      ← 커뮤니티
    ├── requirements.md
    ├── flow.md
    ├── api-spec.md
    └── images/
```

> `images/` 폴더는 비어 있습니다. 플로우 다이어그램·화면 목업을 추가할 자리입니다.
> `common-module-integration.md`는 실제 코드(`gb-backend`)의 공통 모듈 연동 규칙 정본이다. 패키지 루트는 `com.gb`이며, conventions.md의 패키지 구조·에러 코드 표도 이 문서에 맞춰 정렬돼 있다.
