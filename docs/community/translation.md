# 커뮤니티 — 동적 번역 (Bedrock Claude Haiku)

> 외국인 노동자가 자기 언어로 게시글/댓글을 읽을 수 있게 하는 lazy 번역 기능.
> SSOT: API 명세는 [`api-spec.md`](./api-spec.md) §3·§7-3, 요구사항은 [`requirements.md`](./requirements.md) §6.
> 이 문서는 **백엔드 ↔ 계정 B 번역 Lambda 계약**, **캐시 정책**, **비용·운영 노트**의 SSOT다.

---

## 1. 한 줄 요약

- 사용자가 게시글/댓글 화면에서 **"번역 보기"** 버튼을 누르면 백엔드가 계정 B 번역 Lambda를 호출(IAM SigV4)해
  Bedrock Claude Haiku로 번역 결과를 받아 `post_translations`/`comment_translations` 캐시에 저장하고 응답한다.
- 캐시 키는 `(parent_id, language)` — 다국어를 동시 보관해 사용자별 언어 차이가 캐시 hit로 흡수된다.
- 본문/제목 수정 시 해당 parent_id의 모든 언어 번역을 명시적으로 삭제한다(`deleteByPostId`/`deleteByCommentId`).

---

## 2. 왜 Bedrock Claude인가 (AWS Translate 아님)

| 기준 | Bedrock Claude (Haiku) | AWS Translate |
| --- | --- | --- |
| 도메인 톤 | 노동/비자/법령 문맥에 맞춰 자연어 처리 가능 | 일반 번역 — 도메인 용어 부정확 |
| 모델 라인업 통일 | 분석 + 챗봇 + 번역 모두 Bedrock Claude (운영·과금 일원화) | 별개 서비스(IAM/모니터링 분리) |
| 단가 | 1500자 ~1500 토큰 ~$0.0005 | $0.000015/문자 ≒ $0.000023/한글자 |
| 지연 | Haiku는 1500자 입력 기준 ~1~2초 | ~0.3~0.5초 |
| 화이트리스트 4언어 지원 | ko/en/vi/fil 모두 OK | 모두 OK |

→ **일관성·도메인 적합성·운영 단순성**이 약간의 지연·단가 대비 우위. 분석·챗봇 Lambda와 같은 Bedrock 환경에 묶인다.

---

## 3. 시스템 흐름

```
[Frontend]
   │  GET /api/v1/community/posts/{id}/translation?language=vi   (Auth: Bearer)
   ▼
[community-service]
   ① Post 활성 조회 (없으면 COMMUNITY4001)
   ② language 화이트리스트 검증 (ko/en/vi/fil 외 → COMMUNITY4003)
   ③ content 길이 검증 (5000자 초과 → COMMUNITY4004)
   ④ post.language == target_lang ? → 원문 그대로 반환 (Bedrock 호출 X, 캐시 INSERT X)
   ⑤ post_translations 캐시 hit ? → 즉시 반환 (Bedrock 호출 X)
   ⑥ TranslationClient.translate(...) — 캐시 미스 시 1회
   │   │  POST {LAMBDA_FUNCTION_URL}        (AWS SigV4 IAM 서명)
   │   ▼
   │  [번역 Lambda (계정 B)]
   │   - Bedrock InvokeModel (anthropic.claude-haiku-...)
   │   - 응답 파싱 → translated_title/content
   │  ◀───── 응답 (translated_*, target_lang, model_id, tokens)
   ⑦ post_translations INSERT
   ⑧ 응답 200 — { translated_title, translated_content, translated_language }
```

---

## 4. Lambda 입출력 계약 (Backend ↔ 계정 B)

**Endpoint**: Lambda Function URL (백엔드는 `TRANSLATION_LAMBDA_URL` 환경변수로 받음).
**인증**: AWS IAM SigV4 (Lambda Function URL `AuthType: AWS_IAM`). 백엔드는 EKS Pod의 IRSA 자격 증명으로 서명.
**Method**: POST · `Content-Type: application/json`
**Timeout**: 백엔드는 30s (Bedrock 단발 호출 대비 여유).

### Request

```json
{
  "kind": "post",
  "public_id": "a1b2c3d4-...",
  "title": "최저임금 미달인가요?",
  "content": "시급이 9000원인데...",
  "source_lang": "ko",
  "target_lang": "vi"
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `kind` | string | O | `"post"` 또는 `"comment"` — Lambda 측 프롬프트 분기용 (제목 유무, 톤 등) |
| `public_id` | string | O | 본 백엔드 식별자 (Lambda 로깅용 메타) |
| `title` | string\|null | △ | `kind="post"`일 때만. `kind="comment"`면 null |
| `content` | string | O | 원문 본문 |
| `source_lang` | string | O | 원문 언어 코드 (현재는 `posts.language` — "ko" 고정) |
| `target_lang` | string | O | 대상 언어 (`ko`/`en`/`vi`/`fil` 중 하나) |

### Response

```json
{
  "translated_title": "Lương dưới mức tối thiểu phải không?",
  "translated_content": "Tiền lương theo giờ là 9000 won, nhưng...",
  "target_lang": "vi",
  "model_id": "anthropic.claude-3-5-haiku-20241022-v1:0",
  "input_tokens": 612,
  "output_tokens": 980
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `translated_title` | string\|null | `kind="post"`일 때만 채움. 댓글이면 null |
| `translated_content` | string | 번역 본문 (NOT NULL — 번역 실패 시 Lambda가 5xx로 응답) |
| `target_lang` | string | 백엔드가 요청한 값을 그대로 echo (검증용) |
| `model_id` | string | 실제 사용된 Bedrock 모델 ID. 운영 로그/비용 분석용 (저장 안 함) |
| `input_tokens` | int | Bedrock usage. 운영 모니터링용 (저장 안 함) |
| `output_tokens` | int | 동일 |

### 에러 매핑

| Lambda 응답 | Backend 처리 |
| --- | --- |
| 2xx + 위 페이로드 | 정상 — DB 캐시 INSERT 후 200 응답 |
| 4xx (요청 형식 오류 등) | `COMMON5000` (백엔드가 페이로드를 잘못 만든 경우 — fail-fast) |
| 5xx / timeout / connection error | `COMMON5000` (Bedrock 장애 — 폴백 없음. 사용자는 재시도 가능) |

> **폴백 없음 정책**: 번역은 표시용이지만 "잘못된 번역"보다 "에러 표시"가 안전하다(법령·계약 관련 글이 많아 오역의 위험이 크다). MemberClient의 fail-open과 다른 정책.

---

## 5. 캐시 정책

### 테이블 스키마 (database.md §5 참조)

- `post_translations`: PK `(post_id, language)` · `translated_title` VARCHAR(255) · `translated_content` TEXT · `translated_at` DATETIME (`@CreatedDate`)
- `comment_translations`: PK `(comment_id, language)` · `translated_content` TEXT · `translated_at` DATETIME

### Hit/Miss

- **Hit**: `findByPostIdAndLanguage` 성공 → Bedrock 호출 없이 즉시 응답.
- **Miss**: Lambda 호출 → 결과 INSERT → 응답.
- **같은 언어 요청**(`post.language == target_lang`): Bedrock 호출도 캐시 INSERT도 하지 않는다 — 원문을 그대로 응답에 담는다. (캐시 hit과는 다른 경로 — 원문은 본문에 이미 있어 별도 행을 만들지 않는다.)

### 무효화

- 게시글 PATCH에서 **title 또는 content가 변경되면** `postTranslationRepository.deleteByPostId(post.id)` 호출 — 그 글의 모든 언어 캐시 삭제.
- 카테고리만 바뀌는 PATCH는 무효화하지 않는다 (본문 동일).
- 댓글 본문 수정 API는 본 사이클 범위 밖이지만, 도입 시 같은 패턴 적용.
- 게시글/댓글 soft delete 시 캐시는 별도 정리하지 않는다 (조회 시 게시글 활성 검증이 먼저 404를 던지므로 캐시 행이 노출되지 않음 — 운영 효율성).

### 캐시 만료

- 별도 TTL 없음. 본문 변경이 곧 무효화 시그널.
- 캐시 행이 무한히 쌓이는 것을 막기 위해 **장기적으로 LRU 또는 90일 TTL 배치 청소 도입을 검토** (#161 후속 이슈).

---

## 6. 환경별 구성

### `community-service` 환경변수

| 변수 | 예시 | 필수 환경 |
| --- | --- | --- |
| `TRANSLATION_LAMBDA_URL` | `https://abc123.lambda-url.ap-northeast-2.on.aws/` | stage·prod (dev/test는 Mock 사용) |
| `TRANSLATION_AWS_REGION` | `ap-northeast-2` | stage·prod (기본값 `ap-northeast-2`) |

`application.yaml`:
```yaml
translation:
  lambda:
    url: ${TRANSLATION_LAMBDA_URL:}
  aws:
    region: ${TRANSLATION_AWS_REGION:ap-northeast-2}
```

### TranslationClient 빈 등록

- `MockTranslationClient` (`@Profile({"dev","test"})`) — Bedrock 호출 없이 `"[VI] 원문"` 형식 프리픽스 반환. 로컬 개발·테스트 전부 이걸로 통과.
- `BedrockTranslationClient` (`@Profile("!dev & !test")`) — AWS SDK SigV4로 Lambda Function URL POST. stage·prod에서만 활성화.

### IAM 권한 (계정 A의 community-service Pod IRSA)

```json
{
  "Effect": "Allow",
  "Action": "lambda:InvokeFunctionUrl",
  "Resource": "arn:aws:lambda:ap-northeast-2:<계정B>:function:gb-translation-*"
}
```

(계정 B 측에서 cross-account resource policy로 계정 A의 IRSA principal을 명시 허용해야 한다.)

---

## 7. 비용 추정

| 항목 | 값 | 비고 |
| --- | --- | --- |
| 게시글 평균 길이 | 1500자 / ~500 토큰 입력 | UI 입력 상한 10000자, 평균은 훨씬 짧음 |
| 출력 토큰 | ~750 토큰 (vi/fil은 한글 대비 ~1.5배) | 한국어→베트남어 기준 |
| Haiku 입력 단가 | $0.0008 / 1K tokens | 2026년 5월 기준 — 변동 가능 |
| Haiku 출력 단가 | $0.004 / 1K tokens | 동상 |
| **건당 비용** | **~$0.0034** ≒ **약 4.5원** | 입력 0.4 + 출력 3 = 3.4밀리달러 |
| 캐시 hit 시 | $0 (DB 조회만) | 다국어 동시 보관이 hit율을 끌어올림 |
| 일 1000회 호출 | ~$3.4 / day | 가정: 캐시 미스 1000건/일 |
| 월 비용 (캐시 미스 30K) | **~$100/월** | 캐시 hit 비율 70% 가정 시 실 호출 30%만 — 그래도 일 비용 안에 들어감 |

> 모델 단가는 Bedrock 공식 페이지를 SSOT로 한다 — 위 수치는 추정.

---

## 8. 운영 노트

### 모델 ID 정책

- 백엔드는 **모델 ID를 알지 못한다**. Lambda가 `BEDROCK_MODEL_ID` 환경변수로 받아 사용.
- 백엔드 응답에 `model_id`/`tokens`를 노출하지 않는다 (보안 — 인프라 내부 정보).
- 모델 업그레이드(Haiku 4 등)는 Lambda 환경변수만 교체 → 백엔드/프론트 무수정.

### autoTranslate 토글을 두지 않는 이유

- 작성자가 자기 글의 자동 번역 여부를 토글하면, "OFF인 글"은 영원히 번역 캐시가 없는 채로 다른 언어 사용자에게 노출된다(요청 시 빈 응답이 자연스럽지 않음).
- "lazy 번역만" 정책은 단순(작성 ↔ 번역 분리)하고 비용도 더 낮다(읽지 않는 글은 0원).
- 만약 미래에 "자동 번역 안 됨" 표시 같은 UX가 필요하면 별도 컬럼/플래그 추가로 처리 가능 — 본 사이클은 미도입.

### 모니터링 포인트

- Lambda 응답 시간 p95 (Bedrock 측 변동 추적)
- 캐시 hit 비율 (`post_translations` INSERT/총 호출 수)
- COMMUNITY4003/4004 비율 (프론트가 잘못된 입력을 막고 있는지 백엔드 입력 가드 확인)
- COMMON5000 (번역 실패) 비율 — Bedrock 장애 알림 트리거

---

## 9. 변경 이력

- 2026-06-08 — 초안. `posts.translated_*` 3컬럼 폐기, `post_translations`/`comment_translations` 신설, Bedrock Claude Haiku로 통일, autoTranslate 토글 제거.
