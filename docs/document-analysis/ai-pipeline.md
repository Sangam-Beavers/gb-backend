# AI 분석 파이프라인 (AWS 계정 B)

> 이 문서는 서류 분석이 **계정 B에서 어떻게 처리되는지**를 설명한다. (노션 상세본 "AI 파이프라인 v3.3"의 SSOT 요약)
> 백엔드 개발자는 [`api-spec.md`](./api-spec.md)의 API만 구현하면 되고, 이 파이프라인은 "결과가 어떻게 만들어져 돌아오는가"의 맥락 이해용이다.
> **벡터 DB(확정):** 법령 RAG는 **Amazon S3 Vectors**(서버리스). Aurora PostgreSQL+pgvector에서 전환.
> **저장 정책(확정):** 결과는 요청에 실린 **`source` 필드(`production`/`development`)에 따라 한 경로로만** 저장된다. 동시 저장 아님. DynamoDB 미사용.

---

## 1. 계정 분리 개요

| 계정 | 역할 | 핵심 리소스 |
| --- | --- | --- |
| 계정 A | 운영 서비스(백엔드) | EKS(Spring), Aurora MySQL, SQS(수신) |
| 계정 B | AI 분석 전용(격리) | S3, Lambda A/B, Bedrock(Claude), **S3 Vectors**(법령 RAG), EC2(WireGuard+HAProxy) |

계정 B VPC: `sb-ai-vpc` (`10.110.0.0/16`). 분석 워크로드를 운영 서비스에서 물리적으로 격리해 개인정보·비용·장애를 독립시킨다.

---

## 2. 계정 B VPC 구성 (2티어: 퍼블릭 + 관리)

> Aurora를 제거하고 법령 벡터를 **S3 Vectors**로 옮기면서 **DB 서브넷이 불필요**해졌다.
> AI VPC의 프라이빗 서브넷은 이 프로젝트에서 **관리 서브넷(`sb-mgmt-subnet`)** 이라 부른다(IP 정보 문서와 동일 명칭).

```
계정 B VPC (sb-ai-vpc: 10.110.0.0/16)
│
├── 퍼블릭 서브넷 — 2 AZ
│   ├── sb-public-subnet-a: 10.110.11.0/24
│   └── sb-public-subnet-c: 10.110.13.0/24
│       └── EC2 (WireGuard 클라이언트 + HAProxy TCP 프록시) + EIP
│             └── WireGuard 터널 → 온프렘 개발기 MySQL
│
└── 관리 서브넷 (프라이빗) — 2 AZ
    ├── sb-mgmt-subnet-a: 10.110.41.0/24
    └── sb-mgmt-subnet-c: 10.110.43.0/24
        ├── Lambda A  (텍스트 추출 + PII 마스킹)
        ├── Lambda B  (RAG + MCP 패턴 분석 + 경로 분기)
        ├── Bedrock VPC Endpoint     ← Lambda → Bedrock 내부망
        ├── SQS VPC Endpoint         ← Lambda → 계정 A SQS
        ├── S3 Gateway Endpoint      ← S3 업로드 버킷 + S3 Vectors 접근
        └── ECR VPC Endpoint         ← Lambda 컨테이너 이미지 pull
```

| 서브넷 | 0.0.0.0/0 Target | 비고 |
| --- | --- | --- |
| 퍼블릭 | IGW | WireGuard EC2 인바운드/아웃바운드 |
| 관리(프라이빗) | NAT GW | Lambda 아웃바운드만. 인터넷 인바운드 없음 |

> **DB 서브넷 없음:** Aurora가 사라져 격리할 DB가 없다.
> **점프 서버 불필요:** Lambda는 서버리스, S3 Vectors는 AWS API로 접근하므로 SSM 점프 서버가 필요 없다.

---

## 3. 전체 파이프라인 (source 기반 분기)

```
[운영기 클라이언트]                    [개발기 클라이언트]
   │ POST /documents                      │ POST /documents
   ▼                                       ▼
[계정 A 백엔드 — 운영기]                [온프렘 백엔드 — 개발기]
 Aurora INSERT(ANALYZING)               온프렘 MySQL INSERT(ANALYZING)
 source="production"                    source="development"
   └──────────────┬───────────────────────┘
                  ▼
   (1) Pre-signed URL로 사용자가 S3 직접 업로드 (백엔드 미경유)
       S3 오브젝트 메타데이터: { source, document_id }
                  ▼
[계정 B · S3 업로드 버킷]
                  │ (2) S3 ObjectCreated 이벤트
                  ▼
[Lambda A — 텍스트 추출 + PII 마스킹]   (Bedrock Claude VLM 1회, 타임아웃 5분)
                  │  메타데이터에서 source 읽어 그대로 전달
                  │  Lambda B 비동기 호출(InvocationType="Event") 후 즉시 종료
                  ▼
[Lambda B — 분석 + 번역]                (Bedrock Tool Use 루프 + S3 Vectors RAG, 타임아웃 10분)
                  │  분석 완료 → source 값으로 전송 경로 결정 (단일 경로)
                  │
      ┌───────────┴───────────┐
      │ source="production"   │ source="development"
      ▼                       ▼
[경로 1: SQS → 계정 A]   [경로 2: EC2 HAProxy → WireGuard → 온프렘]
계정 A SqsConsumer        온프렘 개발기 MySQL 직접 INSERT
Aurora MySQL 저장         (개발기 document_submissions/results 갱신)
S3 원본 삭제              S3 원본 삭제
      ▼                       ▼
[운영기 사용자 조회]      [개발기 사용자 조회]
```

> **요청한 환경으로만 결과가 돌아간다.** 동시 전송 아님. `source` 값이 경로를 결정한다.
> **source 태그 부여:** 요청 시작점인 백엔드가 `POST /documents` 처리 시 `source`(production/development)를 결정해 **S3 오브젝트 메타데이터**에 심고, Lambda A→B가 이를 읽어 분기한다.
> **왜 SQS인가(production):** 분석은 수 분이 걸려 API Gateway 29초 타임아웃을 넘긴다. 비동기 큐로 결과를 받아 계정 A가 DB에 기록한다.

---

## 4. Lambda A — 텍스트 추출 + PII 마스킹 (단일 호출)

- **입력:** S3 업로드 원본(이미지/PDF) + 오브젝트 메타데이터(`source`, `document_id`)
- **모델:** Bedrock Claude (Vision, VLM) — **1회 호출로 텍스트 추출 + PII 마스킹 동시 처리** (정규식 단계 없음)
- **마스킹 대상:** 이름·주민등록번호·외국인등록번호·전화번호·주소·계좌번호 → `[항목-마스킹]` 형식. 표 구조·조항 번호는 보존.
- **처리:** 마스킹된 텍스트만 Lambda B로 전달, **원본 이미지는 메모리에서 즉시 소멸**. Lambda B 비동기 호출(`{ s3_key, masked_text, document_id, user_lang, source }`) 후 즉시 종료.
- **타임아웃:** 5분

---

## 5. Lambda B — 법령 RAG 분석 + 번역 + 경로 분기

- **입력:** 마스킹 텍스트 + `analysis_document_type` + `user_lang` + `source`
- **모델:** Bedrock Claude + **Tool Use(MCP 패턴) 루프**
- **RAG:** **Amazon S3 Vectors**에 적재된 노동/근로 관련 법령 임베딩을 유사도 검색
- **처리:** 조항 분해 → Tool 호출(`get_legal_standard`) 시 S3 Vectors 검색 → 조항↔법령 비교로 위험 항목·등급 산출 → 급여 요약 → 모국어 번역 → 결과 JSON 조립 → **`source`에 따라 한 경로로 전송**
- **타임아웃:** 10분

Tool 내부 S3 Vectors 검색 (RAG 구현):
```python
import boto3, json
bedrock = boto3.client("bedrock-runtime", region_name="ap-northeast-2")
s3v = boto3.client("s3vectors", region_name="ap-northeast-2")

def get_legal_standard(query_text):
    emb = json.loads(bedrock.invoke_model(
        modelId="amazon.titan-embed-text-v2:0",
        body=json.dumps({"inputText": query_text}),
    )["body"].read())["embedding"]
    res = s3v.query_vectors(
        vectorBucketName="globalbridge-legal-vectors",
        indexName="legal-embeddings",
        queryVector={"float32": emb},
        topK=5, returnMetadata=True, returnDistance=True,
    )
    return [v["metadata"]["content"] for v in res["vectors"]]
```

**결과 JSON(요지)** — `document_results` 컬럼에 매핑:
```
processing_status, overall_risk_level, ocr_confidence,
wage_summary{currency_code, monthly_wage, hourly_wage, deductions[]},
risk_items[]{risk_level, clause, description},
translated_text, masked_file_url, failed_reason, completed_at
```

> **MCP 패턴 vs RAG:** MCP(Bedrock Tool Use)가 "언제 검색할지" 결정하고, RAG(S3 Vectors)가 "어떻게 검색하는지" 처리한다. Tool 내부 구현이 pgvector 쿼리에서 `s3vectors.query_vectors` 호출로 바뀐 것이며 MCP 루프 구조는 동일하다.

**법령 임베딩 사전 적재(배포 시 1회):** 공공누리 1유형 법령(근로기준법·최저임금법·외국인근로자고용법 등)을 Bedrock Titan Embeddings V2(1024차원)로 임베딩해 S3 Vectors 인덱스(`create_index` → `put_vectors`)에 적재. 법령은 거의 불변(연 1~2회 개정)이라 재적재 빈도가 낮다.

---

## 6. 결과 저장 경로 (source에 따라 택일)

> Lambda B는 `source`를 보고 **둘 중 하나로만** 보낸다. 동시 전송 아님.

### 경로 1 — source="production" → 계정 A Aurora MySQL (SQS 경유)
```
Lambda B → 계정 A SQS(크로스 계정) 발행 → 계정 A SqsConsumer
  COMPLETED: document_submissions UPDATE(COMPLETED) + document_results INSERT + S3 원본 삭제
  FAILED:    document_submissions UPDATE(FAILED) + S3 원본 유지(재분석) → 7일 수명주기 삭제
```

### 경로 2 — source="development" → 온프렘 개발기 MySQL (EC2 WireGuard 프록시)
```
Lambda B → 계정 B EC2 프라이빗 IP:3306 (일반 MySQL 커넥션)
         → EC2 HAProxy TCP 프록시 → WireGuard 터널 → 온프렘 개발기 MySQL:3306 직접 INSERT
INSERT 완료 후 S3 원본 삭제
```
HAProxy 설정:
```
frontend mysql_front
  bind *:3306
  default_backend mysql_back
backend mysql_back
  server onprem_mysql [온프렘_WireGuard_IP]:3306 check
```

> Lambda 코드에서는 DB 호스트만 EC2 프라이빗 IP로 두면 된다. EC2가 WireGuard 터널 너머 온프렘 MySQL로 중계한다. 별도 API 서버 코드 없음.
> 경로 2는 개발기 요청에만 동작하므로 운영기 Aurora MySQL에 개발 데이터가 섞이지 않는다.

---

## 7. S3 Vectors 선택 이유 (이전 Aurora pgvector에서 전환)

```
이전 Aurora pgvector의 문제
  1) Lambda 커넥션 폭발 — 요청마다 새 커넥션, Aurora max_connections 한계 → RDS Proxy 필요
  2) DB 인스턴스 상시 고정비 — 쿼리가 없어도 계속 켜져 있음
  3) AI VPC에 DB 서브넷 + 점프 서버 필요 → 구조 복잡

전환 후 S3 Vectors(서버리스)
  ✓ 커넥션 개념 없음 — query_vectors API 호출, 커넥션 폭발 불가
  ✓ 유휴 컴퓨트 0 — 스토리지 + 쿼리당 과금
  ✓ AI VPC가 Lambda + VPC 엔드포인트만 → DB/관리(점프) 서브넷 불필요
  ✓ 저빈도 쿼리 워크로드에 최적 (법령 임베딩은 거의 불변)
  ✓ Bedrock Knowledge Bases 네이티브 통합 가능
```
성능: cold query sub-second / warm query ~100ms. 분석 자체가 수 분 걸리는 비동기 파이프라인이라 벡터 검색 지연은 병목이 아니다. 고빈도가 필요해지면 OpenSearch 티어링 가능.

---

## 8. 개인정보 3-Layer 보호

| Layer | 조치 | 효과 |
| --- | --- | --- |
| 1. 수집 최소화 | 사용자가 **백엔드 미경유**로 S3 직접 업로드(Pre-signed URL) | 운영 서버가 원본 개인정보를 보관하지 않음 |
| 2. 처리 중 마스킹 | Lambda A에서 Claude VLM 단일 호출로 추출+마스킹 동시, 원본 이미지 메모리 소멸 | LLM/로그에 원본 PII 미노출 |
| 3. 사후 삭제 | 해당 환경 DB 저장 확인 후 S3 원본 삭제(실패 시 7일 수명주기 자동 삭제) | 원본 잔존 최소화 |

근거: 개인정보보호법 제16조(최소 수집).

---

## 9. 실패 시나리오 처리

| 실패 유형 | 발생 위치 | 처리 | 사용자 경험 |
| --- | --- | --- | --- |
| VLM 추출 실패 | Lambda A | Lambda B 미호출, 해당 환경 DB FAILED | 선명한 사진으로 재시도 |
| Lambda B 타임아웃 | Lambda B | DLQ → Cleanup Lambda → FAILED (source로 환경 분기) | 시간 초과 안내 |
| Bedrock 오류 | Lambda B | try/except → 해당 경로로 FAILED 전송 | 일시적 오류 안내 |
| S3 Vectors 검색 실패 | Lambda B Tool | Claude 자체 지식으로 계속 or FAILED | 법령 조회 실패 안내 |
| EC2 WireGuard 프록시 장애 | 개발기 경로(dev) | 개발기 요청 실패 (운영기 무관) | 개발팀 직접 확인 |
| SQS 발행 실패 | 운영기 경로(prod) | document_submissions 직접 업데이트 fallback | 운영팀 알림 |
| source 누락 | Lambda A/B | 기본값 처리 또는 FAILED | 요청 재시도 안내 |

---

## 10. 비용 메모

- 1건당 약 **$0.15** (Claude VLM+분석+번역 ~$0.14, Lambda ~$0.008, S3 Vectors 쿼리 ~$0.0001 미만).
- **S3 Vectors 전환 효과:** Aurora 상시 고정비 제거(월 수십 달러 → 0), 스토리지+쿼리당 과금으로 전환.
- 격리된 계정 B에서 처리하므로 비용·사용량 추적이 운영 서비스와 분리된다. AWS Budgets 월 $50 초과 알림 권장.

---

## 11. 백엔드 개발자가 실제로 구현할 것

이 파이프라인 자체는 계정 B(AI 담당) 소관이고, **백엔드(공통 코드, 환경별 동일)** 가 구현할 접점은 다음뿐이다.

1. `POST /api/v1/documents` — `document_submissions` INSERT + 계정 B S3 Pre-signed URL 발급. **이때 현재 환경의 `source`(production/development)를 결정해 S3 오브젝트 메타데이터에 심는다.** Lambda가 이 값으로 결과 경로를 분기한다.
2. **결과 수신부 (환경에 따라 본인 것만 동작)**
   - **운영기:** SQS Consumer — 계정 B가 SQS로 보낸 결과 수신 → `document_submissions.status` 업데이트 + `document_results` INSERT + S3 원본 삭제 트리거.
   - **개발기:** Consumer 코드 불필요 — 계정 B Lambda B가 온프렘 MySQL에 직접 INSERT. 개발기 백엔드는 조회만 한다.
3. 조회 API(`/status`, `/result`, 목록) — 각 환경 MySQL에서 조회. (코드 동일, DB만 환경별)

> Spring 코드는 환경에 따라 바뀌지 않는다. `source` 값(AI 요청에 싣는 출처 태그)과 결과 수신 방식의 차이는 **Spring 프로필(`application-{dev|stage|prod}.yml`)** 설정으로 흡수한다.
> 결과는 **요청한 환경으로만** 저장된다 — production은 Aurora MySQL, development는 온프렘 MySQL. 양쪽 동시 저장 아님.
> 상세 설계·발표 Q&A는 노션 "AI 파이프라인 v3.3" 참고.
