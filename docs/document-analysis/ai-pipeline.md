# AI 분석 파이프라인 (AWS 계정 B)

> 이 문서는 서류 분석이 **계정 B에서 어떻게 처리되는지**를 설명한다.
> 백엔드(계정 A) 개발자는 [`api-spec.md`](./api-spec.md)의 API만 구현하면 되고, 이 파이프라인은 "결과가 어떻게 만들어져 SQS로 돌아오는가"의 맥락 이해용이다.
> **저장 정책(확정):** 분석 결과는 SQS → 계정 A → **MySQL `document_results` 직접 저장.** DynamoDB 미사용.

---

## 1. 계정 분리 개요

| 계정 | 역할 | 핵심 리소스 |
| --- | --- | --- |
| 계정 A | 운영 서비스(백엔드) | EKS(Spring), Aurora MySQL, SQS(수신) |
| 계정 B | AI 분석 전용(격리) | S3, Lambda A/B, Bedrock(Claude), Aurora PostgreSQL+pgvector |

계정 B VPC: `sb-ai-vpc` (예: `10.110.0.0/16`). 분석 워크로드를 운영 서비스에서 물리적으로 격리해 개인정보·비용·장애를 독립시킨다.

---

## 2. 전체 파이프라인

```
[사용자 브라우저]
   │ (1) PUT 업로드 (Pre-signed URL, 백엔드 미경유)
   ▼
[계정 B · S3 업로드 버킷]
   │ (2) S3 ObjectCreated 이벤트
   ▼
[Lambda A — OCR + PII 마스킹]   (Bedrock Claude VLM, 타임아웃 5분)
   │  - 문서 이미지/PDF에서 텍스트 추출(OCR)
   │  - 개인정보(PII) 식별 → 마스킹
   │  - 마스킹본 S3 저장
   ▼
[Lambda B — 분석 + 번역]        (Bedrock Tool Use 루프 + pgvector RAG, 타임아웃 10분)
   │  - 마스킹 텍스트로 법령 RAG 검색(Aurora PostgreSQL + pgvector)
   │  - 위험 조항 분석 + 급여 요약 + 모국어 번역
   │  - 결과 JSON 생성
   ▼
[계정 B → 계정 A · SQS (크로스 계정)]   (3) 결과 비동기 전달
   ▼
[계정 A · SqsConsumer]
   │  - document_submissions UPDATE (status=COMPLETED/FAILED)
   │  - document_results INSERT (분석 내용 직접 저장)
   │  - 계정 B S3 원본 삭제 트리거
   ▼
[사용자] 폴링으로 완료 감지 → 결과 조회
```

> **왜 SQS인가:** 분석은 수 분이 걸려 API Gateway 29초 타임아웃을 넘긴다. 동기 호출이 아니라 비동기 큐로 결과를 받아 계정 A가 DB에 기록한다.

---

## 3. Lambda A — OCR + PII 마스킹

- **입력:** S3 업로드 원본 (이미지/PDF)
- **모델:** Bedrock Claude (Vision, VLM) — 이미지에서 직접 텍스트·구조 추출
- **처리:**
  1. 문서에서 텍스트와 레이아웃 추출 (OCR)
  2. 개인정보(이름, 주민/외국인등록번호, 주소, 연락처 등) 식별 → 마스킹
  3. 마스킹본을 S3에 저장 (조회용)
  4. 마스킹된 텍스트만 다음 단계로 전달, **원본 텍스트는 메모리에서 소멸**
- **타임아웃:** 5분

---

## 4. Lambda B — 법령 RAG 분석 + 번역

- **입력:** 마스킹된 텍스트 + `analysis_document_type`
- **모델:** Bedrock Claude + **Tool Use(MCP 패턴) 루프**
- **RAG:** Aurora PostgreSQL + **pgvector**에 적재된 노동/근로 관련 법령 임베딩을 검색
- **처리:**
  1. 마스킹 텍스트의 조항을 분해
  2. 관련 법령을 pgvector로 검색(예: 최저임금, 근로시간, 위약금 규정)
  3. Tool Use 루프로 조항↔법령 비교 → 위험 항목·등급 산출
  4. 급여 요약(월급/시급/공제) 추출
  5. 사용자 모국어로 번역문 생성
  6. 결과 JSON 조립
- **타임아웃:** 10분

**결과 JSON(요지)** — 계정 A의 `document_results` 컬럼에 매핑:
```
processing_status, overall_risk_level, ocr_confidence,
wage_summary{currency_code, monthly_wage, hourly_wage, deductions[]},
risk_items[]{risk_level, clause, description},
translated_text, masked_file_url, failed_reason, completed_at
```

---

## 5. 개인정보 3-Layer 보호

| Layer | 조치 | 효과 |
| --- | --- | --- |
| 1. 수집 최소화 | 사용자가 **백엔드 미경유**로 S3에 직접 업로드(Pre-signed URL) | 운영 서버가 원본 개인정보를 보관하지 않음 |
| 2. 처리 중 마스킹 | Lambda 내부에서 PII 마스킹 후에만 LLM 전달, 원본 텍스트 메모리 소멸 | LLM/로그에 원본 PII 미노출 |
| 3. 사후 삭제 | 처리 완료 후 S3 원본 삭제(실패해도 7일 수명주기 자동 삭제) | 원본 잔존 최소화 |

근거: 개인정보보호법 제16조(최소 수집).

---

## 6. 비용 메모

- 1건당 약 **$0.15** 수준(OCR + 분석 + 번역 합산, Bedrock 토큰 기준 추정).
- 격리된 계정 B에서 처리하므로 비용·사용량 추적이 운영 서비스와 분리된다.

---

## 7. 계정 A 개발자가 실제로 구현할 것

이 파이프라인 자체는 계정 B(AI 담당) 소관이고, **백엔드(계정 A)** 가 구현할 접점은 다음뿐이다.

1. `POST /api/v1/documents` — `document_submissions` INSERT + 계정 B S3 Pre-signed URL 발급 호출.
2. **SQS Consumer** — 계정 B가 보낸 결과 메시지를 수신해
   - `document_submissions.status` 업데이트(COMPLETED/FAILED)
   - `document_results` INSERT (JSON 컬럼에 분석 내용 저장)
   - 계정 B S3 원본 삭제 트리거
3. 조회 API들(`/status`, `/result`, 목록) — MySQL에서 조회해 응답.

> 결과 저장은 반드시 **MySQL `document_results`** 로. (이전 설계의 DynamoDB/온프렘 직접 INSERT 경로는 폐기. 채택 경로는 "SQS → 계정 A → MySQL"뿐이다.)
