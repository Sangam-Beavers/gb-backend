# AI 후속 질문 챗봇 + MCP (정본)

> 서류 분석 결과를 본 사용자의 **후속 질문 챗봇** 설계 정본. 최초 분석 파이프라인은 [`ai-pipeline.md`](./ai-pipeline.md) 그대로이며, 이 문서는 **그 위에 "추가만"** 한다.
> 전역 규칙은 [`../conventions.md`](../conventions.md), 인프라는 [`../architecture.md`](../architecture.md)를 따른다. 충돌 시 conventions/architecture가 우선한다.
> **최우선 제약:** 기존 와이어프레임·프론트·API 명세를 최대한 바꾸지 않는다. 챗봇/MCP는 추가만 한다.
> **데모 스케일:** 동시 5명 이하. 프로덕션 확장 항목은 §10.

---

## 1. 한 줄 정의와 확정 결정

분석 결과 화면 하단에서 "이 계약서에 대해 더 물어보세요"로 들어오는 **동기 + SSE 스트리밍 챗봇**이다. 다른 팀 소유 도메인 데이터(환율·커뮤니티)에는 **MCP**로, 우리 도메인 데이터(법령)에는 **KB**로 접근한다.

| # | 항목 | 결정 |
| --- | --- | --- |
| 1 | 최초 분석 / 후속 챗봇 | 분석 = 비동기(3~5분) / 챗봇 = 동기(+ SSE 스트리밍) |
| 2 | 권한 검증 | 백엔드(Spring). 현재는 인증 미구현이라 §5 임시처리 |
| 3 | 분석 요약 전달 | 백엔드가 페이로드로(압축 요약, 첫 턴 1회) |
| 4 | 분석결과 저장 | 계정 A MySQL `document_results` (기존 그대로, 변경 0) |
| 5 | 대화내역 저장 | 계정 B DynamoDB `chat_sessions`(TTL 90일) + Redis 캐시(TTL 30분) |
| 6 | 법령 검색 | **Bedrock Knowledge Bases `retrieve`**(분석 파이프라인과 동일 KB 공유) |
| 7 | 환율 / 커뮤니티 | MCP Server 경유 |
| 8 | MCP Pod 배포 | 개발·스테이징·운영 전 환경, `environment` 라우팅, replicas=2 + PDB |
| 9 | 콜드 스타트 | EventBridge 5분 워밍업 |
| 10 | 멱등성 | 대화 저장 `message_uuid` |
| 11 | 시연 언어 | 한국어(다국어 구조 유지, 값만 ko) |
| 12 | 화면/API | 기존 무변경 + 결과 화면에 채팅 영역 1개 + `POST /chat` 1개 추가 |

---

## 2. MCP를 쓰는 이유

판단 기준은 **데이터 모델을 누가 소유하느냐** 하나다.

```
법령(Knowledge Bases)   → 서류분석 도메인(우리)  → KB 직접 호출
환율(Redis, 송금팀)      → 다른 팀              → MCP
커뮤니티(posts, 커뮤팀)  → 다른 팀              → MCP
```

핵심 가치: 다른 팀이 자기 도메인 데이터 모델을 바꿔도 **챗봇 코드는 변경 없음**(결합도↓). REST 대신 MCP인 이유는 LLM이 "어떤 도구가 필요한가"를 동적으로 판단하는 표준(JSON-RPC 2.0)이기 때문이다 — 키워드 분기를 사람이 박지 않는다.

> 법령을 KB로 두는 이유: 법령은 **우리 서류분석 도메인 소유** 데이터다. 게다가 [`ai-pipeline.md`](./ai-pipeline.md)의 분석 Lambda도 같은 법령을 KB `retrieve`로 검색하므로, 챗봇과 분석이 **동일한 KB 하나를 공유**한다. (KB 백엔드 저장소 = S3 Vectors)

---

## 3. 데이터 아키텍처 (가장 중요)

### 3-1. 무엇을 어디에 두는가

```
분석결과   → 계정 A MySQL document_results   (기존 그대로, 변경 0)
진행중 대화 → 계정 B Redis chat:session       (TTL 30분, 캐시)
대화기록   → 계정 B DynamoDB chat_sessions     (TTL 90일, 영구 기록 + 복구 소스)
```

각 데이터를 **그것을 가장 많이 다루는 주체 옆**에 둔다. 이것이 "기존을 안 건드린다"는 제약의 직접 결과다.

| 데이터 | 저장소 | 읽는 주체 | 쓰는 주체 | 성격 |
| --- | --- | --- | --- | --- |
| 분석결과 | MySQL (A) | 사용자(조회), 백엔드(요약 추출 1회) | SQS Consumer | 관계형, 조회 위주 |
| 진행중 대화 | Redis (B) | 챗봇(매 턴) | 챗봇(매 턴) | 휘발 캐시 |
| 대화기록 | DynamoDB (B) | 챗봇(Redis miss 시) | 챗봇(매 턴) | 비정형 시계열 |

### 3-2. 왜 분석결과는 그대로 MySQL인가 (DynamoDB 복제 불필요)

**챗봇은 분석결과를 계속 읽지 않는다. 첫 턴에 요약 1회면 끝이다.**

```
첫 턴:   백엔드가 MySQL에서 요약 추출 → 페이로드 → messages[0]에 주입
이후 턴: 그 요약이 messages 배열에 묻어서 대화 맥락으로 계속 따라다님 → 분석결과 저장소 재접근 없음
```

분석요약이 messages의 일부가 되므로, **대화내역을 저장/복구하면 분석요약도 함께 저장/복구된다.** 그래서 분석결과를 DynamoDB에 복제할 필요가 없다.

```
MySQL 접근 횟수 = 세션당 1회 (맨 첫 질문 때만, 백엔드가 요약 추출)
30분 안 후속:  Redis 복구 (messages에 요약 포함) → MySQL 안 봄
30분 후 후속:  DynamoDB 복구 (messages에 요약 포함) → MySQL 안 봄
```

> ⚠️ **기존 "분석 결과 DynamoDB 미사용" 원칙과 충돌하지 않는다.** 그 원칙은 *분석 결과* 저장 대상이고, 여기 DynamoDB는 *대화기록*이라는 별도 워크로드다. 분석 결과는 여전히 MySQL `document_results`다.

### 3-3. 왜 대화내역은 DynamoDB(계정 B)인가

챗봇 Lambda(계정 B)가 매 턴 직접 R/W → 같은 계정 B라 **크로스계정 통신 0**, SSE와 잘 맞음. DynamoDB는 서브넷 불필요(AWS API 접근)라 계정 B의 순수 서버리스 구조를 유지한다. 대화내역을 기존 MySQL에 넣으면 챗봇이 매 턴 크로스계정 R/W를 해야 해서 역설적으로 기존 인프라를 더 건드린다.

### 3-4. Redis ↔ DynamoDB 2계층

```
턴 끝 → messages 통째:
  ├─ Redis SETEX chat:session  (TTL 30분 갱신)
  └─ DynamoDB chat_sessions    (TTL 90일, 누적 저장, message_uuid 멱등)

다음 질문:
  Redis hit  → 그대로 사용 (빠름)
  Redis miss(30분↑) → DynamoDB에서 복구 → Redis 재적재 → 계속
```

**TTL 30분은 "대화 제한"이 아니라 "캐시 유효기간"이다.** 30분을 넘겨도 DynamoDB에서 복구되어 대화는 끊김 없이 이어진다.

---

## 4. 화면 / API — 기존 무변경 + 추가만

### 기존 그대로 (변경 0)
와이어프레임(서류분석/유형선택/업로드/분석중 폴링/결과 상세), 마이페이지 "분석 내역" 진입점, 기존 분석 API 6개([`api-spec.md`](./api-spec.md))는 전부 무변경.

### 추가만
- 와이어프레임: **결과 상세 화면 하단에 채팅 영역 1개**. 결과(면책→위험도→위험항목→급여→번역)는 기존 그대로 두고, 그 아래에 "이 계약서에 대해 더 물어보세요" + 입력창. 새 화면/새 진입점 없음.
- API: **`POST /api/v1/documents/{id}/chat` 1개** (§6).

> 챗봇을 별개 화면이 아니라 결과 화면의 연장으로 둔다. 사용자 동선은 "분석내역 → 결과 클릭 → 스크롤 → 후속 질문"으로 기존 그대로. 대화 세션 중심 UI는 §10 확장으로 미룬다.

---

## 5. 권한 검증 (백엔드 책임)

[`../architecture.md`](../architecture.md) §7: WAF → API GW → ALB → Spring Security(2차 인가) → DB. 인가는 Spring 레이어 책임이며 Lambda는 인가 레이어가 아니다.

> ⚠️ **현재 인증 미구현 — 다른 API와 동일하게 임시 처리한다.** [`../conventions.md`](../conventions.md) §14에 따라, 챗봇 컨트롤러도 지금 단계에서는 JWT 추출이 아니라 `@RequestHeader("X-User-Public-Id")` + `// TODO`로 본인 식별자를 받는다. "백엔드 2차 인가로 본인 문서인지 검증"은 **최종 목표**이고, 현재 구현은 헤더로 받은 `userPublicId`와 문서 소유자를 비교하는 형태다.

```java
// 백엔드 ChatController (인증 구현 후 JWT 추출로 교체)
// TODO: 인증 구현 후 JWT(sub)에서 userPublicId 추출로 교체. 현재는 헤더 임시 수신.
var doc = documentRepo.findByPublicId(documentId)
            .orElseThrow(() -> new BusinessException(DocumentErrorCode.DOCUMENT_NOT_FOUND)); // DOCUMENT4001 / 404
if (!doc.getUserPublicId().equals(userPublicId)) {
    throw new BusinessException(CommonErrorCode.FORBIDDEN);                                  // COMMON4031 / 403
}
```

- **에러코드는 기존 재사용만.** 인증 실패 `COMMON4011`, 권한 없음 `COMMON4031`, 문서 없음 `DOCUMENT4001`. **신규 코드 신설 금지**(conventions §12).
- 검증을 통과한 요청만 챗봇 Lambda로 간다. 챗봇은 인가를 다시 하지 않는다.

---

## 6. 챗봇 메시지 API · 요청 흐름

`POST /api/v1/documents/{id}/chat` · Auth ✅(최종 목표) / 현재 헤더 임시처리

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `message` | string | O | 사용자 질문 |
| `session_id` | string | X | 없으면 백엔드가 새 UUID 발급 |
| `user_lang` | string | O | 답변 언어 코드(데모 ko) |

**Response** — SSE 스트림(`text/event-stream`). 토큰 이벤트 + 완료 시 `session_id` 포함 종료 이벤트.
**Error** — 401 COMMON4011 / 403 COMMON4031 / 404 DOCUMENT4001

### 흐름 (SSE, 백엔드 경유)

```
[사용자 앱] 결과 화면 하단에서 후속 질문
   │ POST /api/v1/documents/{id}/chat
   ▼
[CloudFront+WAF → API GW → ALB → 계정 A Spring 백엔드]
   │ ① 권한 검증(§5)  ② 첫 대화면 MySQL에서 분석 요약 추출(§3-2)
   │ ③ 챗봇 Lambda Function URL 호출 (IAM 인증)
   ▼
[챗봇 Lambda (계정 B) — Function URL + Response Streaming]
   │ a. Redis 세션 로드 (miss면 DynamoDB 복구, §3-4) — 첫 대화면 요약을 messages[0]에 주입
   │ b. Bedrock Tool Use 루프:
   │      ├─ search_legal_standard → KB retrieve (우리 도메인)
   │      ├─ get_exchange_rate     → MCP Server 1 (송금 도메인)
   │      └─ search_community_posts→ MCP Server 2 (커뮤니티 도메인)
   │ c. 응답 SSE 토큰 스트리밍
   │ d. Redis 갱신(30분) + DynamoDB 저장(90일, message_uuid)
   ▼ (SSE)
[계정 A 백엔드] SseEmitter로 사용자에게 중계
   ▼
[사용자 앱] 토큰별 점진 표시
```

**왜 백엔드 경유 / 왜 SSE**
- 모든 트래픽이 계정 A 백엔드를 통과 → 인증·권한·로깅·Rate Limit 일관. 챗봇 Lambda는 인터넷 비노출(IAM으로 계정 A만 호출).
- 챗봇은 질문-응답 턴제라 양방향(WebSocket) 불필요. 응답 스트리밍만 필요 → **SSE로 충분**. HTTP 그대로라 CloudFront/API GW/ALB 인프라 변경 없음, **Spring MVC `SseEmitter`와 호환(WebFlux 불필요 — [`../tech-stack.md`](../tech-stack.md) §2와 정합)**.

**백엔드 → 챗봇 Lambda 페이로드**

```json
{
  "message": "베트남 돈으로 얼마야?",
  "session_id": "uuid",
  "user_lang": "ko",
  "document_public_id": "uuid",
  "user_public_id": "uuid",
  "source": "production",
  "environment": "prod",
  "analysis_summary": "위험도 HIGH. 최저임금 미달(월 160만원, 기준 209만원), 주 50시간 초과근무. 문서유형: 근로계약서."
}
```

> ⚠️ **`source`와 `environment`는 역할이 다르다(혼동 금지).**
> - **`source`** = `development` / `production` **2값**. 인프라 계열(온프렘 vs AWS)만 가른다. 분석 파이프라인의 결과 저장 경로 분기에 쓰던 값과 **동일한 의미**다([`ai-pipeline.md`](./ai-pipeline.md) §3). 챗봇 대화기록은 둘 다 계정 B에 저장되므로 챗봇 동작에는 직접 쓰이지 않지만, 메타·로깅 일관성을 위해 함께 싣는다.
> - **`environment`** = `dev` / `stage` / `prod` **3값**. 챗봇 Lambda가 **MCP Server URL을 라우팅**할 때 쓴다(stage 챗봇 → stage MCP Pod). DynamoDB `chat_sessions.environment` 속성(§7)과 같은 값이다.
> - 매핑: `dev→(source=development, environment=dev)`, `stage→(source=production, environment=stage)`, `prod→(source=production, environment=prod)`. 백엔드가 `application-{dev|stage|prod}.yml`에서 두 값을 주입한다.

- `analysis_summary`는 **첫 대화에만** 채운다(전문 아닌 압축 요약). 이어가는 대화면 생략 — 요약은 이미 messages/DynamoDB에 들어있다.

---

## 7. DynamoDB 테이블 설계 (`chat_sessions`)

> 이 테이블은 **계정 B 소관**이라 [`../database.md`](../database.md)(계정 A MySQL 스키마)의 범위 밖이다.

```
PK (HASH):  USER#{user_public_id}
SK (RANGE): DOC#{document_public_id}#TS#{timestamp}

속성: session_id, message_uuid(멱등), role('user'|'assistant'), content,
      tools_used(List), language, environment(prod/stage/dev), created_at,
      ttl (만료 epoch = created_at + 90일)

GSI (문서별 조회): GSI-PK = DOC#{document_public_id} / GSI-SK = TS#{timestamp}
TTL: ttl 속성 활성화 → 90일 후 자동 삭제 (PII 보관기간 관리)
```

- **TTL 90일:** 계약서 대화엔 임금·근무조건 등 PII가 섞인다. 영구보관은 개인정보보호법 부담 + PII 마스킹에 공들인 파이프라인 톤과 불일치. 네이티브 TTL로 보관기간을 명시한다([`ai-pipeline.md`](./ai-pipeline.md) §8 3-Layer 보호와 정합).
- **멱등성:** 같은 `message_uuid`면 덮어쓰기(재시도 중복 방지). conventions §의 멱등성 원칙과 동일 결.
- **환경 분기:** `environment` 속성으로 구분, 테이블은 단일.

---

## 8. 챗봇 Lambda 핵심

### 법령 검색 = KB retrieve (분석 파이프라인과 동일 KB)

```python
import boto3
bedrock_kb = boto3.client("bedrock-agent-runtime", region_name="ap-northeast-2")

def search_legal_standard(query_text):
    res = bedrock_kb.retrieve(
        knowledgeBaseId="<LEGAL_KB_ID>",        # ai-pipeline.md와 동일 KB 공유
        retrievalQuery={"text": query_text},
        retrievalConfiguration={"vectorSearchConfiguration": {"numberOfResults": 5}},
    )
    return "\n".join(r["content"]["text"] for r in res["retrievalResults"])
```

### 세션 로드 (Redis → DynamoDB 복구)

```python
def load_session(session_id, user_public_id, document_public_id, analysis_summary):
    raw = chatbot_redis.get(f"chat:session:{session_id}")
    if raw:                                              # Redis hit
        return json.loads(raw)
    items = chat_table.query(                            # Redis miss → DynamoDB 복구
        KeyConditionExpression="PK = :u AND begins_with(SK, :d)",
        ExpressionAttributeValues={":u": f"USER#{user_public_id}", ":d": f"DOC#{document_public_id}"},
        ScanIndexForward=True).get("Items", [])
    if items:
        return [{"role": it["role"], "content": [{"text": it["content"]}]} for it in items]
    return [                                             # 첫 대화 — 요약 주입(MySQL 재접근 없음)
        {"role": "user", "content": [{"text": f"[분석된 계약서 요약]\n{analysis_summary}\n\n위 계약서에 대해 질문하겠습니다."}]},
        {"role": "assistant", "content": [{"text": "네, 확인했습니다. 궁금한 점 물어보세요."}]}]
```

### Tool Use 루프 + SSE (요지)

```python
for _ in range(5):
    resp = bedrock.converse_stream(
        modelId="<MODEL_ID>",                  # ⚠ 서울은 apac./global. inference profile만 (us. 금지) — R3, Day1 확인
        messages=messages, toolConfig={"tools": tools}, system=[{"text": system}])
    output, reply = await stream_to_client(resp)   # ⚠ end_turn 턴만 사용자에게 흘림(도구 호출 턴은 비노출)
    messages.append(output)
    if output["stopReason"] == "end_turn": break
    if output["stopReason"] == "tool_use":
        messages.append({"role": "user", "content": await execute_tools(output, sessions)})

chatbot_redis.setex(f"chat:session:{session_id}", 1800, json.dumps(messages))   # 30분
save_to_dynamo(session_id, user_public_id, document_public_id, message, reply, environment)  # 90일, message_uuid (DynamoDB environment 속성 = dev/stage/prod)
```

도구 실행은 `asyncio.gather`로 병렬, 예외는 도구별로 격리해 사용자 친화 메시지로 치환한다(한 도구 실패가 전체를 막지 않음).

> 📋 **위 코드는 "요지"이며, 챗봇이 실제로 돌려면 다음을 구현해야 한다(시연 전 필수).**
> - `tools` — Bedrock `toolConfig` 배열. 도구 3개의 `toolSpec`(name/description/inputSchema): `search_legal_standard`(KB), `get_exchange_rate`(MCP1), `search_community_posts`(MCP2). 이름은 §6 흐름도와 동일하게 맞춘다.
> - `execute_tools(output, sessions)` — `output`의 `tool_use` 블록을 읽어 도구명으로 분기 → KB `retrieve` / MCP1 호출 / MCP2 호출, 결과를 `toolResult` 블록으로 반환. MCP URL은 페이로드 `environment`(§6)로 라우팅.
> - `stream_to_client(resp)` — `converse_stream` 이벤트에서 **`end_turn` 턴의 텍스트 토큰만** SSE로 흘리고(R2), `tool_use` 턴은 비노출. `(output, reply)` 반환.
> - `system` — 시스템 프롬프트(역할·답변 언어 = `user_lang`·법령 인용 규칙 등).
> - **시연 최소 경로:** 환율 질문("베트남 돈으로 얼마야?")은 `get_exchange_rate`(MCP1)만 타면 되고, 법령 질문은 `search_legal_standard`(KB)만 타면 된다. 영상용으로는 이 두 도구가 각각 한 번씩 호출되는 시나리오를 먼저 통과시키면 충분하다.

---

## 9. MCP Server — 전 환경 배포

백엔드/프론트는 환경마다 따로 뜨고, AI(챗봇 Lambda)는 계정 B에 1개만 떠서 공유한다. MCP Server는 도메인 옆 어댑터이므로 각 환경에 함께 배포한다.

```
개발기(온프렘 K8s):  MCP1(환율)→온프렘 Redis / MCP2(커뮤니티)→온프렘 posts
스테이징(AWS EKS):   MCP1→stage Redis / MCP2→stage posts
운영기(AWS EKS):     MCP1→prod Redis  / MCP2→prod posts
```

- 챗봇 Lambda는 페이로드 `environment`(dev/stage/prod)로 MCP URL을 라우팅한다. (인프라 계열 `source`가 아니라 환경 3값으로 가른다 — §6 페이로드 주석 참고. dev는 온프렘 EC2 HAProxy 경유, stage/prod는 각 EKS의 MCP Pod.)
- 온프렘 MCP 통신은 기존 EC2 HAProxy에 frontend(8000)만 추가(신규 컴포넌트 0, ai-pipeline의 HAProxy 패턴 재사용).
- Helm: **replicas=2 + PodDisruptionBudget(minAvailable=1)** + podAntiAffinity. Pod 1개가 죽어도 무중단, 도구 1개가 죽어도 나머지는 정상(장애 격리).
- 커뮤니티 MCP는 환경별 DB에 **읽기 전용 계정(`mcp_reader`, SELECT만)** 으로 접근하고, 검색 시 `deleted_at IS NULL`로 삭제글을 제외한다(최소권한).

---

## 10. 의도적으로 뺀 것 (프로덕션 확장)

| 항목 | 데모 | 확장 |
| --- | --- | --- |
| 다국어 값 | ko만(구조 완비) | vi/th/id/tl/en 추가 + 법령 KB 다국어 |
| 대화 세션 중심 UI | 결과 화면에 얹음 | 마이페이지를 대화 세션 목록으로 승급 |
| 분석 전문 참조 | 요약만 주입 | `search_document_detail` 도구(백엔드 경유 MySQL 조회) |
| 대화 압축 | 슬라이딩 윈도우 | Summary Worker |
| Bedrock/MCP 재시도 | 기본 | adaptive retry + Provisioned Throughput |
| Prompt Injection | 신뢰 도구만 | posts 본문 `<post_content>` 래핑 + 프롬프트 강화 |
| 콜드 스타트 | EventBridge 워밍업 | Provisioned Concurrency |

---

## 11. 인프라 변경 범위 요약

**추가 (계정 B):** 챗봇 Lambda(Function URL + Response Streaming) 1 · DynamoDB `chat_sessions` 1(TTL 90일) · 챗봇 전용 Redis 1 · 법령 KB 1(분석과 공유, 백엔드 S3 Vectors) · EventBridge 워밍업 1
**추가 (각 환경):** MCP Server 1(환율)·2(커뮤니티) Pod (replicas=2+PDB) · `mcp_reader` 계정
**추가 (계정 A 백엔드):** `POST /api/v1/documents/{id}/chat`(권한검증 + 요약추출 + Lambda 호출 + SSE 중계) · Function URL 호출 클라이언트(IAM 서명)
**추가 (EC2 HAProxy):** frontend `mcp_community_front`(8000)
**변경 없음:** 기존 와이어프레임/프론트(결과 화면 하단 영역만 추가) · 기존 분석 API 6개 · VPC/서브넷/NAT/ALB/WireGuard · Lambda A/B · MySQL 스키마 · 기존 Redis · 분석 결과 저장 경로(SQS→Consumer→MySQL)

---

## 12. 구현 시 주의 (설계는 맞지만 손이 많이 가는 곳)

> CLI/구현자가 가볍게 보면 막히는 지점. **Day 1에 PoC로 먼저 뚫고 시작할 것.**

- **R1 — Function URL 스트리밍 ↔ Spring `SseEmitter` 중계가 가장 어렵다.** Lambda Function URL의 Response Streaming을 IAM(SigV4)으로 호출하면서 그 스트림을 계정 A Spring이 받아 SSE로 재전송하는 어댑터를 직접 짜야 한다. 다른 게 다 정상이어도 여기가 막히면 데모가 안 된다. **더미 Lambda가 토큰 3개를 흘리면 브라우저까지 도달하는 PoC**를 가장 먼저 통과시킨다.
- **R2 — `converse_stream` + Tool Use 루프의 스트리밍 분기.** 도구를 호출하는 턴(중간 reasoning)은 사용자에게 흘리지 말고, 최종 `end_turn` 턴에서만 토큰을 흘린다.
- **R3 — 모델 ID(서울 리전 주의).** 서울(`ap-northeast-2`)에서 Claude는 foundation model ID 직접 호출이 막혀 있고 **inference profile로만** 호출된다. 접두사를 틀리면 그대로 깨진다 — **`us.` 접두 프로파일을 서울에서 쓰면 "400 invalid model identifier"** 가 난다(실제 보고된 오류). 서울에서 유효한 형태는 **`apac.anthropic.claude-*` 또는 `global.anthropic.claude-*`** inference profile이다. Day 1에 `aws bedrock list-inference-profiles --region ap-northeast-2`로 계정 B에서 **실제로 보이는** ID를 확정한다(모델 액세스가 활성화돼야 목록에 뜸 / 추정 금지). IAM에는 inference-profile ARN뿐 아니라 라우팅 대상 리전들의 `foundation-model` 리소스도 함께 Allow해야 한다. 또한 **Lambda A의 VLM(이미지→텍스트+마스킹)은 Vision 지원 모델**, **챗봇/Lambda B의 Tool Use는 Tool Use 지원 모델**(Sonnet/Opus 계열 안전)이어야 하므로 선택한 프로파일이 두 기능을 지원하는지 확인한다.
- **R4 — 법령 KB 동작 확인.** S3 Vectors는 GA(2025-12, 서울 리전 사용 가능)라 가용성 리스크는 없다. 다만 배포 시 콘솔에서 법령 KB 생성·동기화·`retrieve` 동작을 1회 확인한다(데이터 소스 연결·ingestion 완료 여부).

---

## 13. 멘토 Q&A 치트시트

```
왜 MCP?            다른 팀 도메인 모델이 바뀌어도 챗봇 코드 불변(결합도↓). LLM 동적 도구 선택(JSON-RPC).
법령은 왜 KB?       데이터 모델이 우리 소유(서류분석 도메인). 분석 파이프라인과 같은 KB 공유.
권한 검증 어디?     백엔드(architecture §7). 단 현재 인증 미구현이라 X-User-Public-Id 헤더 임시처리(conventions §14).
분석결과 왜 MySQL?  사용자가 조회하는 관계형 데이터, 기존에 이미 있음. 챗봇은 요약만 필요→백엔드가 페이로드.
챗봇이 분석결과 계속 읽나? 아니. 첫 턴 요약 1회. 이후 messages 맥락에 묻어 따라다님. MySQL 재접근 0.
대화내역 왜 DynamoDB? 챗봇이 매 턴 직접 R/W, 같은 계정B→크로스계정0, SSE와 맞음, 서버리스 구조 유지.
분석결과 DynamoDB 미사용 아니었나? 그건 '분석 결과' 한정. 대화기록은 별도 워크로드라 신규 도입. 분석결과는 여전히 MySQL.
30분 지나면 대화 끊겨? 아니. Redis는 캐시(30분), 기록은 DynamoDB(90일). miss면 복구해 계속.
PII 영구보관?       아니. TTL 90일. 마스킹+보관기간으로 관리.
동기/비동기?        분석=비동기(3~5분), 챗봇=동기+SSE(체감 0.5초). WebFlux 아님, MVC SseEmitter.
Pod 죽으면?         replicas=2+PDB 무중단. 도구1개 죽어도 나머지 정상(장애 격리).
기존 안 건드린다며?  화면은 결과 하단 영역만 추가, API는 /chat 1개만 추가, 기존 6개·스키마·인프라 무변경.
```

---

*GlobalBridge | AI 후속 질문 챗봇 + MCP 정본 | document-analysis*
