# AI 서류 분석 — 요구사항 (Requirements)

> 도메인 prefix: `/documents`
> 사용 뷰: 6. 서류 분석
> 정본 명세: [`api-spec.md`](./api-spec.md) · 파이프라인: [`ai-pipeline.md`](./ai-pipeline.md) · 공통 규칙: [`../conventions.md`](../conventions.md)

---

## 1. 범위

외국인 노동자가 이해하기 어려운 한국어 문서를 업로드하면, AI가 위험 조항을 분석하고 모국어로 번역해 설명한다.

- 대상 문서(`analysis_document_type`): `LABOR_CONTRACT`(근로계약서) / `PAYSLIP`(급여명세서) / `EMPLOYMENT_CONTRACT`(고용계약서)
- 처리: 사용자가 S3에 직접 업로드(Pre-signed URL) → OCR(VLM) → PII 마스킹 → 법령 RAG 분석 → 번역
- 결과: 위험 등급, 위험 항목, 급여 요약, 번역 전문, 면책 문구

---

## 2. 유저 스토리

- 사용자는 문서 유형을 고르고 사진/파일을 업로드해 분석을 요청할 수 있다.
- 분석은 시간이 걸리므로(수 분), 사용자는 **진행 상태를 폴링**으로 확인한다.
- 분석이 끝나면 **위험 조항·급여 요약·번역 전문**을 본다.
- 결과 화면 상단에는 **면책 문구 + 변호사 상담 안내**가 먼저 보이고, 그 다음 AI 분석 내용이 보인다.
- 분석이 실패하면 **재요청**할 수 있다.
- 첫 화면에 **최근 분석 3건**이 보이고, 추가(최대 10건)는 **월 구독(15,000원)** 으로 열람한다.

---

## 3. 결과 구성

- `overall_risk_level`: LOW / MEDIUM / HIGH
- `risk_items[]`: 위험 항목 (risk_level, clause(조항), description(사유))
- `wage_summary`: 통화, 월급, 시급, 공제 항목
- `translated_text`: 번역 전문
- `ocr_confidence`: OCR 신뢰도(0~1, 표시용 number)
- `masked_file_url`: 마스킹본 Pre-signed URL

**면책 문구 (결과에 항상 동반)**
> AI 분석 결과는 법적으로 완벽하지 않을 수 있습니다. 중요한 판단이나 자세한 상담은 반드시 변호사와 함께하세요.

---

## 4. 개인정보 보호 (필수)

원본 문서는 민감 개인정보다. 3-Layer로 보호한다(상세 [`ai-pipeline.md`](./ai-pipeline.md)).

1. 사용자가 **백엔드를 거치지 않고** S3에 직접 업로드(Pre-signed URL) → 개인정보 처리 경로 최소화
2. Lambda 내부에서 **PII 마스킹** 후에만 LLM에 전달, 원본 텍스트는 메모리에서 소멸
3. 처리 완료 후 **S3 원본 파일 삭제** (실패 시 7일 수명주기로 자동 삭제)

근거: 개인정보보호법 제16조 최소 수집 원칙.

---

## 5. 저장 정책 (확정)

- AI 분석 결과는 **MySQL `document_results`에 직접 저장.** **DynamoDB 미사용.**
- `wage_summary`/`risk_items`는 JSON 컬럼, `translated_text`는 TEXT.
- 메타(`document_submissions`)와 결과(`document_results`)는 `submission_id`로 1:1 연결.
- 분석 작업은 AWS 계정 B에서 격리 실행. 결과는 **요청 출처(`source` 필드)에 따라 한 경로로만** 저장된다(동시 저장 아님).
  - **운영기 요청 (source="production"):** SQS(크로스 계정) → 계정 A → Aurora MySQL `document_results`.
  - **개발기 요청 (source="development"):** Lambda B → EC2(HAProxy) → WireGuard → 온프렘 개발기 MySQL 직접 INSERT.
  - 즉 **개발기에서 보낸 분석은 개발기로, 운영기에서 보낸 분석은 운영기로** 결과가 돌아간다. (환경 격리 + 개인정보)
- 법령 RAG 벡터 검색은 **Amazon S3 Vectors**(서버리스). 상세: [`ai-pipeline.md`](./ai-pipeline.md).

---

## 6. 법적 고려 (서비스 성격)

- 법률 자문이 아니라 **법률 정보 제공** 서비스다. 특정 법령 조항과 계약서 내용을 기계적으로 비교·안내한다.
- 모든 결과에 면책 문구와 전문가 확인 권장을 포함한다.
- 법령 원문은 공공저작물(공공누리 1유형)로 출처 표시 하에 활용 가능.

---

## 7. 관련 데이터

- `document_submissions`, `document_results` (회원 참조는 `user_public_id`). 상세: [`../database.md`](../database.md) §4
