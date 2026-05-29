# 결과 JSON 스키마 합의 (v1.0)

> **분석 Lambda B(이유진) → SQS → Consumer(심규보, document-service)** 사이를 잇는 결과 JSON의 합의 정본.
> 한쪽이 바꾸면 다른 쪽이 깨지는 인터페이스이므로, **변경 시 반드시 공동 합의**한다(아래 §7).
> 정합성 출처: `CLAUDE.md` §5, `docs/conventions.md` §0/§12, `docs/document-analysis/ai-pipeline.md`, `AI-WORK-SPLIT.md` §3-②.

- **합의일**: 2026-05-28
- **합의자**: 이유진(분석 파이프라인) / 심규보(챗봇·Consumer)
- **schema_version**: `1.0` (확정)
- **개정 이력**: 2026-05-28 — 초안 작성 + 유진 검토 의견 5건 반영(§3 enum 연동 규칙, ocr_confidence DECIMAL(3,2), masked URL 풀 저장, source envelope 분리 등)

---

## 1. 배경 — 왜 이 합의가 필요한가

`AI-WORK-SPLIT.md` §3-②에서 짚은 "공유 3곳 중 하나"다. 유진 Lambda B가 뱉는 결과 JSON과 규보 SQS Consumer가 INSERT하는 `document_results` 컬럼이 **1:1로 맞아야** 한다.

이 문서가 SSOT다. Lambda B 코드와 Consumer 코드 모두 이 스키마를 기준으로 작성한다.

> ⚠️ **`source` 필드 위치 명시**: `source`(`development` / `production`)는 인프라 계열 분기용이라 **결과 JSON 본문에 포함하지 않는다**. SQS 메시지 attribute 또는 envelope JSON 레벨에서만 다룬다 — Consumer는 envelope에서 읽고 본문 스키마에는 노출되지 않는다.

---

## 2. 최종 스키마 (v1.0)

```json
{
  "schema_version": "1.0",
  "document_public_id": "550e8400-e29b-41d4-a716-446655440000",
  "document_type": "EMPLOYMENT_CONTRACT",
  "processing_status": "COMPLETED",
  "overall_risk_level": "HIGH",
  "ocr_confidence": 0.92,
  "wage_summary": {
    "currency_code": "KRW",
    "monthly_wage": "2000000",
    "hourly_wage": "9620",
    "deductions": [
      { "name": "national_pension", "amount": "90000" }
    ]
  },
  "risk_items": [
    {
      "risk_level": "HIGH",
      "clause": "제8조",
      "description": "최저임금 미달 — 시급 9,620원 기준 미충족"
    }
  ],
  "translated_text": "...",
  "translated_lang": "ko",
  "masked_file_url": "s3://gb-document-masked-prod/2026-05-28/abc.png",
  "failed_reason": null,
  "completed_at": "2026-05-28T09:00:00Z"
}
```

---

## 3. 필드 명세

| 필드 | 타입 | 필수 | 비고 |
| --- | --- | --- | --- |
| `schema_version` | string | O | 현재 `"1.0"` 고정. 변경 시 §7 절차. |
| `document_public_id` | string(UUID) | O | **correlation key**. Consumer가 update할 row 식별. SQS envelope에도 동일 값 넣어 이중 안전. |
| `document_type` | enum | O | `EMPLOYMENT_CONTRACT` / `LEASE_CONTRACT` / `OTHER`. 데모는 `EMPLOYMENT_CONTRACT`만 사용. |
| `processing_status` | enum | O | `COMPLETED` / `FAILED` / `PROCESSING`. |
| `overall_risk_level` | enum | O | `HIGH` / `MEDIUM` / `LOW` / `NONE`. `risk_items`와 연동 규칙 있음 — §3-2 참조. |
| `ocr_confidence` | number | O | 표시용 float, **범위 [0.00, 1.00]로 고정**(백분율 형태 금지). conventions §0 예외. DB는 `DECIMAL(3,2)`. 양쪽 모두 출력/입력 시 범위 검증. |
| `wage_summary` | object | O | 아래 §3-1. 임금 정보 없으면 빈 객체 `{}`가 아니라 모든 하위 필드 `null`. |
| `risk_items` | array | O | 위험 항목 없으면 빈 배열 `[]` (null 아님). 아래 §3-2. |
| `translated_text` | string | O | 번역 결과 전문. 빈 문자열 가능. |
| `translated_lang` | string | O | 번역 결과 언어 코드 (ISO 639-1). 데모 `"ko"` 고정. |
| `masked_file_url` | string | O | **풀 URL** `s3://bucket/key` 형식 그대로. 환경별 버킷명이 다르므로 키만 잘라 저장하면 환경 간 원본 복원이 불가능 — 풀 URL 통째 저장. 파일 자체는 Lambda A가 마스킹 + 업로드 + URL 생성, Lambda B는 패스스루. |
| `failed_reason` | string \| null | O | `processing_status="FAILED"`일 때만 사유 string, 그 외 `null`. |
| `completed_at` | string | O | ISO 8601 UTC `Z` (`"2026-05-28T09:00:00Z"`). |

### 3-1. `wage_summary` 하위 구조

| 필드 | 타입 | 비고 |
| --- | --- | --- |
| `currency_code` | string | ISO 4217 (`"KRW"`). |
| `monthly_wage` | string(decimal) \| null | conventions §0 — 금액은 string 십진수. 월급 정보 없으면 `null`. |
| `hourly_wage` | string(decimal) \| null | 시급 정보 없으면 `null`. |
| `deductions` | array | 공제 항목. 없으면 `[]`. |
| `deductions[].name` | string | enum 권장 (`national_pension`, `health_insurance`, `income_tax`, ...) 데모는 자유 string. |
| `deductions[].amount` | string(decimal) | 금액 string. |

### 3-2. `risk_items` 하위 구조 + `overall_risk_level` 연동 규칙

| 필드 | 타입 | 비고 |
| --- | --- | --- |
| `risk_level` | enum | `HIGH` / `MEDIUM` / `LOW`. **`NONE` 없음** — "위험 항목" 자체이므로 의미상 NONE은 존재하지 않는다. "위험 없음" 상태는 `risk_items = []`로 표현한다. |
| `clause` | string | 조항 위치(예: `"제8조"`, `"6.2항"`). 위치 추적 안 되면 `"-"`. |
| `description` | string | 사용자 노출용 한국어 설명. ~100자 이내 권장. |

**`overall_risk_level` ↔ `risk_items[]` 연동 규칙** (Lambda B 프롬프트에 강제, Consumer는 검증만):

- `risk_items.size() == 0` → `overall_risk_level == "NONE"`
- `risk_items.size() > 0` → `overall_risk_level == max(risk_items[].risk_level)` (HIGH > MEDIUM > LOW)

> 데모 단계에선 1:1 일관성을 강제한다. 운영 단계에서 "items엔 안 잡혔지만 전체 위험 평가는 존재" 같은 케이스가 필요해지면 그때 규칙을 재검토(§7 절차).

---

## 4. nullable 규약

같은 의미를 여러 표현으로 보내지 않는다 — **하나로 통일**:

| 의미 | 표현 |
| --- | --- |
| 값 없음 (단일 필드) | `null` |
| 값 없음 (배열) | `[]` |
| 값 없음 (문자열) | `""` ← 금지. `null` 사용. |
| 금액 0원 | `"0"` (string) |
| 금액 정보 없음 | `null` |

특히 `monthly_wage` / `hourly_wage`: **"0"과 `null`은 다른 의미**다. 0원으로 명시된 계약은 `"0"`, 정보 자체가 없으면 `null`.

---

## 5. MySQL `document_results` 컬럼 매핑

Consumer(`document-service`)가 SQS에서 위 JSON을 받으면 다음 대로 INSERT/UPDATE한다.

| JSON 필드 | DB 컬럼 | 비고 |
| --- | --- | --- |
| `document_public_id` | (correlation — `document_submissions`에서 row 식별) | 직접 컬럼 매핑 아님 |
| `document_type` | `document_results.document_type` | enum 그대로 |
| `processing_status` | `document_submissions.status` | enum 그대로 |
| `overall_risk_level` | `document_results.overall_risk_level` | |
| `ocr_confidence` | `document_results.ocr_confidence` | **`DECIMAL(3,2)`** — 범위 [0.00, 1.00] 고정 |
| `wage_summary` | `document_results.wage_summary_json` | JSON 컬럼 그대로 저장 |
| `risk_items` | `document_results.risk_items_json` | JSON 컬럼 그대로 저장 |
| `translated_text` | `document_results.translated_text` | TEXT |
| `translated_lang` | `document_results.translated_lang` | VARCHAR(8) |
| `masked_file_url` | `document_results.masked_file_url` | **풀 URL 그대로 저장** (VARCHAR(512)). 컬럼명을 `s3_masked_key`에서 `masked_file_url`로 변경 — JSON 필드와 1:1 매칭 + 환경별 버킷 구분 보존 |
| `failed_reason` | `document_results.failed_reason` | nullable VARCHAR |
| `completed_at` | `document_results.completed_at` | DATETIME(UTC) |

> ⚠️ DDL은 `docs/database.md`에 반영 후 Flyway/Liquibase로 migration. v1.0 시점에 위 컬럼들이 모두 존재한다고 가정.
> v1.0 변경점: `ocr_confidence` 컬럼은 `DECIMAL(3,2)`, `s3_masked_key` → `masked_file_url`(VARCHAR(512))로 컬럼명 변경.

---

## 6. analysis_summary 포맷 — 챗봇 첫 턴 주입용

**규보 책임 영역**이지만 유진은 입력값을 알아야 하므로 여기서 합의.

- ChatController가 첫 대화 시점에 위 JSON을 MySQL에서 1회 읽어 **단일 한국어 문자열**로 압축
- 길이 200자 이내 권장
- 고정 순서 4요소:

```
위험도 {overall_risk_level}. {top 1~2개 risk_items.description}.
임금: 월 {monthly_wage}원 / 시급 {hourly_wage}원.
문서유형: {document_type 한국어}.
```

**실제 예시**:
> "위험도 HIGH. 최저임금 미달(시급 9,620원 기준 미충족), 주 50시간 초과근무 조항 존재. 임금: 월 2,000,000원 / 시급 9,620원. 문서유형: 근로계약서."

`document_type` 한국어 매핑: `EMPLOYMENT_CONTRACT` → "근로계약서", `LEASE_CONTRACT` → "임대차계약", `OTHER` → "기타 문서".

---

## 7. 변경 절차

이 스키마는 한쪽이 일방적으로 바꾸면 안 된다.

1. 변경 제안자가 이 문서에 PR로 변경안 작성 + 다른 한쪽 멘션
2. 양측 합의 후 머지
3. `schema_version` 올림 (예: `1.1`, breaking change면 `2.0`)
4. Lambda B와 Consumer 양쪽이 새 버전 지원되도록 동시 배포 (호환 기간 짧게)

---

## 8. 의도적으로 뺀 것 (데모 단계 제약)

영상 시연 범위 밖이라 v1.0에서는 다루지 않음. 운영 단계에서 추가 검토:

- `processing_status="FAILED"` 케이스 상세 처리 — 데모는 항상 `COMPLETED` 가정
- 멀티 페이지 PDF 결과 (현재는 단일 이미지 가정)
- 다국어 번역 (`translated_lang`은 `"ko"` 고정)
- 통화 다양화 (`currency_code`는 `"KRW"` 고정)
- 공제 항목 enum 표준화 (`deductions[].name`)
- `risk_items[]`의 위치 정보 확장 (페이지 번호 등)

---

*GlobalBridge | document-analysis 결과 JSON 스키마 합의 v1.0*
