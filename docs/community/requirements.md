# 커뮤니티 — 요구사항 (Requirements)

> 도메인 prefix: `/community`
> 사용 뷰: 7. 커뮤니티
> 정본 명세: [`api-spec.md`](./api-spec.md) · 공통 규칙: [`../conventions.md`](../conventions.md)

---

## 1. 범위

외국인 노동자가 생활정보·비자·거주 등을 공유하는 게시판. 게시글·댓글·좋아요·번역, 인증 배지를 제공한다.

---

## 2. 카테고리 (`category`)

| 값 | 의미 |
| --- | --- |
| `LIFE_INFO` | 생활정보 |
| `JOB` | 취업 |
| `VISA` | 비자 |
| `COUNTRY` | 국가별 |
| `RESIDENCE` | 거주 |
| `QUESTION` | 질문 |
| `FREE` | 자유게시판 |

---

## 3. 유저 스토리

- 사용자는 카테고리별로 게시글 **목록을 보고 검색**할 수 있다(키워드, 정렬: 최신/인기/정확도).
- 사용자는 **게시글을 작성·수정·삭제**할 수 있다.
- 사용자는 게시글에 **댓글·대댓글**을 달 수 있다.
- 사용자는 게시글/댓글에 **좋아요**를 누르고, **관심글 목록**을 볼 수 있다.
- 사용자는 외국어 게시글을 **번역 보기** 할 수 있다.
- 게시글 카드/상세에 작성자의 **인증 배지**가 표시된다.
- 사용자는 **주요 QnA/FAQ**를 볼 수 있다.

---

## 4. 인증 배지

- 신분증 인증 완료(`members.is_verified = TRUE`) 회원은 닉네임 옆에 배지 표시.
- 응답 필드: `author_is_verified`(boolean).

---

## 6. 번역

- 외국어 게시글·댓글을 사용자 언어로 **번역 보기**(lazy, on-demand).
- 번역 엔진은 **계정 B의 Bedrock Claude Haiku**(문서 분석 파이프라인과 같은 모델 라인업 공유).
  AWS Translate는 쓰지 않는다.
- 호출은 **사용자가 "번역 보기" 버튼을 누른 시점**에만 발생한다 — 작성 시 자동 번역 안 함.
  (작성자에게 autoTranslate 토글을 제공하지 않는다 — 본문 작성과 번역을 분리해 비용·실패를 격리.)
- 대상 언어는 **프론트가 `?language=` 쿼리로 명시**한다(예: 사용자가 vi 모드면 `?language=vi`).
  백엔드는 본인 식별을 위해 JWT만 쓰고, locale 조회로 member-service를 추가 호출하지 않는다.
- 지원 언어는 **화이트리스트 4개**: `ko`, `en`, `vi`, `fil`. 그 외 값은 `COMMUNITY4003`(UNSUPPORTED_LANGUAGE).
- 본문 길이 상한: 게시글 5000자 / 댓글 5000자 초과 시 `COMMUNITY4004`(CONTENT_TOO_LONG)로 거절.
  (작성 상한은 게시글 10000자 / 댓글 2000자지만, Bedrock 호출 비용·지연을 막기 위해 번역 측에 별도 캡을 둔다.)
- **번역 결과는 별도 캐시 테이블**(`post_translations`, `comment_translations`)에
  `(parent_id, language)` 복합 PK로 저장 — 다국어를 동시에 보관해 사용자별 언어 차이를 캐시 hit으로 흡수한다.
  (구버전의 `posts.translated_title/content/language` 3컬럼 단일 캐시는 제거 — 한 글에 한 언어만 보관해
  사용자 언어가 바뀌면 매번 캐시 미스가 났다.)
- **캐시 무효화**: 게시글·댓글 본문(또는 제목) 수정 시 해당 parent_id의 모든 언어 번역을 삭제(`deleteByPostId`/`deleteByCommentId`).
  카테고리만 바뀐 PATCH 등 본문 무관 수정은 무효화 대상 아님.
- **같은 언어 요청 처리**(`post.language == target_lang`): Bedrock 호출 없이 원문을 그대로 응답에 담아 반환
  (캐시 INSERT 없음).
- 응답 필드:
  - 게시글: `data: { translated_title, translated_content, translated_language }`
  - 댓글: `data: { translated_content, translated_language }`
- 비용 추정: Haiku 기준 글 1건(~1500자) 번역 당 약 $0.0005 (입력 600토큰 + 출력 1000토큰).
  자세한 운영 노트·계약·모델 ID 정책은 [`translation.md`](./translation.md) 참고.

---

## 7. 용어 규칙 (중요)

- 커뮤니티에서 회원을 가리키는 URL/필드는 **`member`** 로 통일한다. `user` 금지.
  - URL: `/community/members/{memberId}/...`
  - 필드: `member_public_id` (※ DB 컬럼은 `user_public_id`지만, API 표면 용어는 member)
- 식별자는 모두 `public_id`(UUID). 내부 `id`/`comment_id`/`parent_id` 노출 금지.
- enum은 SCREAMING_SNAKE_CASE.

---

## 8. 관련 데이터

- `posts`, `comments`, `likes`
- 회원 참조는 `user_public_id`(UUID, 물리 FK 없음). 상세: [`../database.md`](../database.md) §5
