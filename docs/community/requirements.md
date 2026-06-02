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
| `JOB` | 직업 추천 |
| `VISA` | 비자 |
| `COUNTRY` | 국가별 |
| `RESIDENCE` | 거주 |
| `QUESTION` | 질문 |

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

- 외국어 게시글을 사용자 언어로 번역 보기.
- 번역 결과는 `posts.translated_*` 컬럼에 캐싱(반복 번역 비용 절감).

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
