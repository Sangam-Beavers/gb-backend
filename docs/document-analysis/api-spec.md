# AI 서류 분석 — API 명세 (정본)

> Notion 개별 상세 명세 기반 정본. 전역 규칙은 [`../conventions.md`](../conventions.md).
> 성공 응답은 `{ "success": true, "data": {...}, "message": "..." }` 래퍼. 아래 표 필드는 `data` 내부.
> 서류 유형 필드는 **`analysis_document_type`** (신분증의 `identity_document_type`과 구분).

---

## 엔드포인트 목록

| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 서류 분석 내역 목록 | GET | `/api/v1/documents` | ✅ |
| 분석 요청 (Pre-signed URL 발급) | POST | `/api/v1/documents` | ✅ |
| 분석 진행 상태 조회 (폴링) | GET | `/api/v1/documents/{id}/status` | ✅ |
| 분석 결과 상세 조회 | GET | `/api/v1/documents/{id}/result` | ✅ |
| 문서 분석 결과 단건 조회 (※ 미구현) | GET | `/api/v1/documents/{id}` | ✅ |
| 후속 질문 챗봇 (SSE 스트리밍) | POST | `/api/v1/documents/{id}/chat` | ✅ |
| 후속 질문 챗봇 대화 이력 조회 | GET | `/api/v1/documents/{id}/chat/history` | ✅ |

---

## 1. 분석 요청 (Pre-signed URL 발급)

`POST /api/v1/documents` · Auth ✅

문서 레코드를 생성하고 S3 Pre-signed URL을 발급한다. 클라이언트는 응답받은 URL로 파일을 직접 PUT 업로드한다.

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `analysis_document_type` | string | O | LABOR_CONTRACT / PAYSLIP / EMPLOYMENT_CONTRACT |
| `file_name` | string | O | 원본 파일명(확장자 포함, 예: `contract.pdf`) |

**Response 201** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 문서 UUID. 이후 상태·결과 조회 키 |
| `upload_url` | string | N | S3 Pre-signed URL. 클라이언트가 PUT으로 직접 업로드 |
| `upload_headers` | object(map) | N | **PUT 업로드 시 그대로 함께 보내야 하는 헤더(이름→값).** 서명에 포함되어 있어 누락/변경 시 S3가 403으로 거부하고 메타데이터(source/document_id/result_queue_arn)가 오브젝트에 박히지 않는다. 예: `{"Content-Type":"application/octet-stream","x-amz-meta-source":"production","x-amz-meta-document_id":"…"}` |
| `expires_at` | string | N | URL 만료 시각 (ISO 8601 UTC Z) |

> ⚠️ **업로드 시 `upload_headers`를 반드시 그대로 전송**해야 한다. AWS SDK v2 presigner는 S3 오브젝트
> 메타데이터를 서명 헤더(`X-Amz-SignedHeaders`)에 굽기 때문에, 이 헤더들을 이름·값 그대로 PUT에 실어야
> 서명이 일치한다. 백엔드가 정한 메타데이터 값을 클라이언트가 임의로 바꾸면 안 된다(서명 불일치 → 403).
> `Host`는 HTTP 클라이언트가 자동 설정하므로 `upload_headers`에 포함하지 않는다.
>
> 업로드 예시:
> ```
> PUT {upload_url}
> Content-Type: application/octet-stream
> x-amz-meta-source: production
> x-amz-meta-document_id: 550e8400-…
> x-amz-meta-result_queue_arn: arn:aws:sqs:…   # production 계열에서만 존재
> <binary file body>
> ```

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. |
| 400 | COMMON4002 | 필수 입력 항목이 누락되었습니다. |
| 401 | COMMON4011 | 인증 정보가 유효하지 않습니다. |

---

## 2. 분석 진행 상태 조회 (폴링)

`GET /api/v1/documents/{id}/status` · Auth ✅

**Path Variable**: `id` = 문서 public_id (UUID)

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 문서 UUID |
| `status` | string | N | ANALYZING / COMPLETED / FAILED |
| `estimated_minutes` | integer | Y | 예상 소요(분). 진행 중일 때 안내용 |

**Error**: 401 COMMON4011 / 403 COMMON4031 / 404 DOCUMENT4001

> **ANALYZING 고아 건 정리(스케줄러):** 제출 후 업로드를 안 하거나 분석 결과가 유실되면 그 건은
> 영원히 ANALYZING으로 남으므로, 백엔드 스케줄러(`StaleSubmissionSweeper`, 기본 10분 주기)가
> `updatedAt` 기준 임계(기본 30분, `gb.analysis.stale-timeout-minutes`) 초과 ANALYZING 건을 FAILED로
> 정리한다. FAILED 처리된 건의 복구는 사용자가 §1(`POST /api/v1/documents`)로 새로 제출하는 것뿐이다.
> sweep 후 결과가 늦게 도착해도 Consumer가 status를 덮어쓰므로 무해(최종적으로 결과가 이김).

---

## 3. 분석 결과 상세 조회

`GET /api/v1/documents/{id}/result` · Auth ✅

분석 완료 문서의 상세 결과를 조회한다.

**Path Variable**: `id` = 문서 public_id (UUID)

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `document_public_id` | string | N | 문서 UUID |
| `analysis_document_type` | string | N | LABOR_CONTRACT / PAYSLIP / EMPLOYMENT_CONTRACT |
| `processing_status` | string | N | COMPLETED / FAILED / PARTIAL |
| `overall_risk_level` | string | Y | LOW / MEDIUM / HIGH. 실패 시 null |
| `ocr_confidence` | number | Y | OCR 신뢰도 (0~1, 표시용 number). 실패 시 null |
| `wage_summary` | object | Y | 급여 요약. 없으면 null |
| `wage_summary.currency_code` | string | N | 통화 (ISO 4217) |
| `wage_summary.monthly_wage` | string | Y | 월 급여 (string 십진수) |
| `wage_summary.hourly_wage` | string | Y | 시급 (string 십진수) |
| `wage_summary.deductions` | array | Y | 공제 항목. 없으면 [] |
| `wage_summary.deductions[].name` | string | N | 공제 항목명 |
| `wage_summary.deductions[].amount` | string | N | 공제 금액 (string 십진수) |
| `risk_items` | array | Y | 위험 항목. 없으면 [] |
| `risk_items[].risk_level` | string | N | LOW / MEDIUM / HIGH |
| `risk_items[].clause` | string | N | 해당 조항·문구 |
| `risk_items[].description` | string | N | 위험 사유 |
| `translated_text` | string | Y | 번역 전문. 미생성 시 null |
| `masked_file_url` | string | Y | 마스킹본 Pre-signed URL. 미생성 시 null |
| `failed_reason` | string | Y | 실패 사유 (FAILED/PARTIAL일 때) |
| `completed_at` | string | Y | 완료 시각 (ISO 8601 UTC Z). 미완료 시 null |
| `created_at` | string | N | 결과 생성 시각 |
| `updated_at` | string | N | 결과 갱신 시각 |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 401 | COMMON4011 | 인증 정보가 유효하지 않습니다. |
| 403 | COMMON4031 | 접근 권한이 없습니다. |
| 404 | DOCUMENT4001 | 존재하지 않는 문서입니다. |
| 422 | COMMON4221 | 처리할 수 없는 요청입니다. (분석 미완료 상태 조회 등) |

> 결과 화면에는 면책 문구를 항상 함께 노출한다(요구사항 §3 참고).

---

## 4. 서류 분석 내역 목록

`GET /api/v1/documents?page=&size=&status=` · Auth ✅

**Query**: `status` — 상태 필터(선택, 복수 허용·콤마 구분). `ANALYZING`/`COMPLETED`/`FAILED`.
생략 시 전체. 잘못된 값은 **400 COMMON4001**. (프론트가 실패 내역을 숨길 때
`status=ANALYZING,COMPLETED`로 호출.)

**Response 200** — `data`: `documents`(배열) + 페이지네이션 메타.
각 항목: `public_id`, `analysis_document_type`, `status`, `overall_risk_level`, `created_at` 등 요약.

> 첫 화면 최근 3건 노출, 추가(최대 10건)는 월 구독(15,000원). 구독 게이팅은 비즈니스 정책으로 처리.

---

## 5. 문서 분석 결과 단건 조회

`GET /api/v1/documents/{id}` · Auth ✅ — 마이페이지 진입용 단건 조회. **(※ 미구현 — 코드에 매핑 없음.)** 응답은 §3의 결과 또는 메타 요약(구현 시 통일).

---

## 6. 후속 질문 챗봇 대화 이력 조회

`GET /api/v1/documents/{id}/chat/history?limit=&cursor=` · Auth ✅

재방문 시 결과 화면 채팅 영역에 이전 대화를 복원(시드)한다. 백엔드는 인증 + 문서 소유자 검증 후
챗봇 Lambda(`GET /history`, IAM SigV4, 비스트리밍 JSON)로 릴레이하고, Lambda가 DynamoDB
`chat_sessions`에서 **사용자 노출 턴(`visible=true`)만** 시간 오름차순으로 반환한다.
설계 상세: [`ai-chatbot-mcp.md`](./ai-chatbot-mcp.md) §6-2. (챗봇 전송 API `POST /chat`의 상세도 같은 문서 §6.)

**Path Variable**: `id` = 문서 public_id (UUID)

**Query**
| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `limit` | integer | X | 1회 조회 메시지 수. 기본 50, 최대 100 |
| `cursor` | string | X | 다음 페이지 커서(이전 응답의 `next_cursor` 그대로). 첫 조회 시 생략 |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `messages` | array | N | 대화 메시지(시간 오름차순). 이력 없으면 `[]` |
| `messages[].role` | string | N | `user` / `assistant` |
| `messages[].content` | string | N | 메시지 텍스트 (분석 요약 합성 턴은 미포함) |
| `messages[].created_at` | string | N | 생성 시각 (ISO 8601 UTC Z) |
| `next_cursor` | string | Y | 다음 페이지 커서(base64). 마지막 페이지면 null |

```json
{
  "success": true,
  "data": {
    "messages": [
      { "role": "user", "content": "이 계약서 월급이 최저임금보다 낮은 거 맞아?", "created_at": "2026-06-05T09:12:41Z" },
      { "role": "assistant", "content": "네, 맞습니다. 계약서상 월 급여 160만원은...", "created_at": "2026-06-05T09:12:45Z" }
    ],
    "next_cursor": null
  },
  "message": "요청이 성공적으로 처리되었습니다."
}
```

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 403 | COMMON4031 | 접근 권한이 없습니다. |
| 404 | DOCUMENT4001 | 존재하지 않는 문서입니다. |

> - 이력이 없어도 문서가 존재하고 본인 소유면 **200 + 빈 배열**(404 아님).
> - 에러 코드는 전부 기존 재사용(신설 0). `limit`/`cursor` 형식 오류는 전역 규칙대로 400 COMMON4001.

---

## 참고

계정 B 내부 분석 파이프라인(Lambda/Bedrock/S3 Vectors)은 [`ai-pipeline.md`](./ai-pipeline.md) 참고. 백엔드(계정 A) 관점에서는 위 API만 구현하면 되고, 결과는 SQS Consumer가 `document_results`에 채운다.
