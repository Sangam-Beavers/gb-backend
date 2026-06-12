# AI 서류 분석 — 처리 흐름 (Flow)

> 정본 명세: [`api-spec.md`](./api-spec.md) · 계정 B 내부 파이프라인: [`ai-pipeline.md`](./ai-pipeline.md)

---

## 1. 전체 흐름 한눈에

```
[사용자] 문서 유형 선택 + 파일 준비
   │
[계정 A 백엔드] 분석 요청  POST /api/v1/documents
   → document_submissions INSERT (status=ANALYZING)
   → 계정 B S3 Pre-signed URL 발급 (유효 ~10분)
   → 201 { public_id, upload_url, expires_at }
   │
[사용자] upload_url로 파일을 S3에 직접 PUT 업로드 (백엔드 미경유)
   │
[계정 B] S3 이벤트 → Lambda A(OCR+PII 마스킹) → Lambda B(법령 RAG 분석+번역)
   → 결과 JSON 생성
   │
[계정 B → 계정 A] SQS(크로스 계정) 비동기 전달
   │
[계정 A] SqsConsumer
   → document_submissions UPDATE (status=COMPLETED/FAILED)
   → document_results INSERT (분석 내용 직접 저장)
   → S3 원본 삭제
   │
[사용자] 폴링으로 상태 확인 → 완료 시 결과 조회
```

> 분석에 수 분 걸린다(API Gateway 29초 타임아웃 회피 위해 비동기/SQS). 사용자는 폴링으로 완료를 감지.

---

## 2. 사용자 측 단계별 흐름

```
[서류 분석 화면]
최근 분석 내역  GET /api/v1/documents   (최근 3건, 추가는 구독)
   │
[새 분석]
문서 유형 선택 (LABOR_CONTRACT / PAYSLIP / EMPLOYMENT_CONTRACT)
   │
분석 요청  POST /api/v1/documents
   Body: { analysis_document_type, file_name }
   → 201 { public_id, upload_url, expires_at }
   │
파일 직접 업로드 (S3 PUT, upload_url 사용)
   │
[분석 중] 진행 상태 폴링  GET /api/v1/documents/{id}/status
   → { status: ANALYZING }  →  반복
   → { status: COMPLETED } 되면 결과 조회로 이동
   → { status: FAILED } 면 재제출 안내
   │
분석 결과 상세  GET /api/v1/documents/{id}/result
   → 면책 문구 + 위험 항목 + 급여 요약 + 번역 전문 표시
```

### 실패
```
status=FAILED 일 때
   → 사용자에게 실패를 알리고 새로 제출(POST /api/v1/documents)하도록 안내
```

---

## 3. 상태 모델

| status | 의미 | 사용자 화면 |
| --- | --- | --- |
| `ANALYZING` | 분석 중 | "분석 중입니다. 3~5분 소요됩니다." |
| `COMPLETED` | 완료 | 결과 표시 |
| `FAILED` | 실패 | 재시도 안내 |

결과의 `processing_status`는 `COMPLETED` / `FAILED` / `PARTIAL`.

---

## 4. 결과 화면 순서 (UX 규칙)

```
1) 면책 문구 + 변호사 상담 광고/안내   ← 항상 먼저
2) overall_risk_level (전체 위험 등급)
3) risk_items (위험 조항 목록)
4) wage_summary (급여 요약)
5) translated_text (번역 전문)
```

---

## 5. 예외 처리 포인트

- Pre-signed URL 만료 후 업로드 시도 → 업로드 실패 → 새로 제출(POST /documents) 유도.
- 분석 미완료 상태에서 결과 조회 → `COMMON4221`(처리 불가) 또는 status로 안내.
- 권한 없는 문서 조회 → `COMMON4031`(403).
- 존재하지 않는 문서 → `DOCUMENT4001`(404).
- 분석 실패 시 S3 원본 유지(재분석 가능), 7일 후 자동 삭제.
