# 커뮤니티 — API 명세 (정본)

> Notion 개별 상세 명세 기반 정본. 전역 규칙은 [`../conventions.md`](../conventions.md).
> 성공 응답은 `{ "success": true, "data": {...}, "message": "..." }` 래퍼. 아래 표 필드는 `data` 내부.
> 회원 참조 URL/필드는 **`member`** 용어 사용(`user` 금지). 식별자는 `public_id`(UUID).

---

## 엔드포인트 목록

| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 게시글 목록·검색 | GET | `/api/v1/community/posts?category=&keyword=&sort=&page=&size=` | ✅ |
| 게시글 단건 조회 | GET | `/api/v1/community/posts/{id}` | ✅ |
| 게시글 작성 | POST | `/api/v1/community/posts` | ✅ |
| 게시글 수정 | PATCH | `/api/v1/community/posts/{id}` | ✅ |
| 게시글 삭제 | DELETE | `/api/v1/community/posts/{id}` | ✅ |
| 번역 보기 | GET | `/api/v1/community/posts/{id}/translation?language={}` | ✅ |
| 관심글 목록 | GET | `/api/v1/community/posts/liked?sort=&page=&size=` | ✅ |
| 게시글 좋아요 | POST | `/api/v1/community/posts/{id}/likes` | ✅ |
| 게시글 좋아요 취소 | DELETE | `/api/v1/community/posts/{id}/likes` | ✅ |
| 댓글 목록 조회 | GET | `/api/v1/community/posts/{id}/comments?page=&size=` | ✅ |
| 댓글 작성 | POST | `/api/v1/community/posts/{postId}/comments` | ✅ |
| 댓글 삭제 | DELETE | `/api/v1/community/posts/{postId}/comments/{commentId}` | ✅ |
| 주요 QnA/FAQ | GET | `/api/v1/community/faq` | ✅ |

---

## 1. 게시글 목록·검색

`GET /api/v1/community/posts` · Auth ✅

**Query Parameter**
| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `category` | string | X | LIFE_INFO/JOB/VISA/COUNTRY/RESIDENCE/QUESTION |
| `keyword` | string | X | 제목·본문 검색어(있으면 검색 모드) |
| `sort` | string | X | latest / popular / accuracy(키워드 있을 때만), 기본 latest |
| `page` | integer | X | 0부터, 기본 0 |
| `size` | integer | X | 기본 20 |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `posts` | array | N | 게시글 목록 |
| `posts[].public_id` | string | N | 게시글 UUID |
| `posts[].category` | string | N | 카테고리 |
| `posts[].title` | string | N | 제목 |
| `posts[].content_preview` | string | N | 본문 미리보기 |
| `posts[].author_nickname` | string | N | 작성자 닉네임 |
| `posts[].like_count` | integer | N | 좋아요 수 |
| `posts[].comment_count` | integer | N | 댓글 수 |
| `posts[].created_at` | string | N | 작성 시각(UTC Z) |
| `page` | integer | N | 현재 페이지(0부터) |
| `size` | integer | N | 페이지당 수 |
| `total_elements` | integer | N | 전체 게시글 수 |
| `total_pages` | integer | N | 전체 페이지 수 |

**Error**: 400 COMMON4001 / 401 AUTH4011

---

## 2. 게시글 작성

`POST /api/v1/community/posts` · Auth ✅

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `category` | string | O | LIFE_INFO/JOB/VISA/COUNTRY/RESIDENCE/QUESTION |
| `title` | string | O | 제목 |
| `content` | string | O | 본문 |

**Response 201** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 게시글 UUID |
| `category` | string | N | 카테고리 |
| `title` | string | N | 제목 |
| `content` | string | N | 본문 |
| `author_nickname` | string | N | 작성자 닉네임 |
| `like_count` | integer | N | 좋아요 수(생성 시 0) |
| `comment_count` | integer | N | 댓글 수(생성 시 0) |
| `created_at` | string | N | 작성 시각(UTC Z) |
| `updated_at` | string | N | 수정 시각(UTC Z) |

**Error**: 400 COMMON4001 / 400 COMMON4002 / 401 AUTH4011

---

## 3. 게시글 단건 조회 / 수정 / 삭제 / 번역

- 단건 조회: `GET /api/v1/community/posts/{id}` → 본문 + 작성자(닉네임/`author_is_verified`) + 카운트. 404 COMMUNITY4001.
- 수정: `PATCH /api/v1/community/posts/{id}` (본인만, 403 COMMON4031)
- 삭제: `DELETE /api/v1/community/posts/{id}` (soft delete, 본인만)
- 번역 보기: `GET /api/v1/community/posts/{id}/translation?language={}` → `data: { translated_title, translated_content, translated_language }`

---

## 4. 관심글 목록

`GET /api/v1/community/posts/liked?sort=&page=&size=` · Auth ✅

**Response 200** — `data`: `posts`(배열, 각 항목에 `liked_at` 추가) + page/size/total_elements/total_pages.
`sort`: latest(좋아요 누른 시각순) / popular(좋아요 수순), 기본 latest.

---

## 5. 좋아요

- 좋아요: `POST /api/v1/community/posts/{id}/likes` → like_count +1, 201
- 취소: `DELETE /api/v1/community/posts/{id}/likes` → like_count -1, 200

> 중복 방지: `(user_public_id, target_type=POST, target_id)` UNIQUE. 댓글 좋아요는 target_type=COMMENT.

---

## 6. 댓글 작성

`POST /api/v1/community/posts/{postId}/comments` · Auth ✅

게시글에 댓글을 작성한다. 작성자는 JWT `public_id` claim에서 식별된다.

**대댓글은 본 사이클 범위 밖** — 모든 댓글이 최상위(`parent_id=null`)로 INSERT된다. 향후 대댓글 도입 시 Request에 `parent_comment_public_id` 필드 추가 + Service에 부모 검증·1-depth 강제 로직을 추가한다 (Comment.parent_id 컬럼·Response.parent_comment_public_id 필드는 이미 준비됨).

**Path Variable**: `postId` = 게시글 public_id (UUID), 최대 36자

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `content` | string | O | 댓글 내용 (1~2000자, 공백만 입력 차단) |

**Response 201** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 댓글 UUID |
| `post_public_id` | string | N | 게시글 UUID |
| `parent_comment_public_id` | string | Y | 부모 댓글 UUID. **현 사이클은 항상 null** (대댓글 미지원) |
| `content` | string | N | 댓글 내용 |
| `author_nickname` | string | N | 작성자 닉네임 (MemberClient 조회) |
| `author_is_verified` | boolean | N | 작성자 인증 배지 여부 |
| `created_at` | string | N | 작성 시각 (ISO 8601 UTC `Z`) |

작성 성공 시 게시글 `comment_count`가 1 증가한다 (같은 트랜잭션 내 dirty checking).

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (content 빈값/2000자 초과/path variable 형식 위반) |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 404 | COMMUNITY4001 | 존재하지 않는 게시글입니다. |

> COMMUNITY4002(부모 댓글 없음)는 대댓글 도입 시 활성화. 현재는 사용 안 함.

---

## 7. 댓글 목록 조회

`GET /api/v1/community/posts/{id}/comments?page=&size=` · Auth ✅ → 댓글 배열(+ `parent_comment_public_id`, 현재는 항상 null — 대댓글 미지원) + 페이지 메타.

---

## 7-2. 댓글 삭제

`DELETE /api/v1/community/posts/{postId}/comments/{commentId}` · Auth ✅

본인이 작성한 댓글을 soft delete한다(`deleted_at` 갱신, row 보존). 삭제 성공 시 게시글의 `comment_count`가 1 감소한다(같은 트랜잭션 내 dirty checking, 음수 방지 가드). 작성자는 JWT `public_id` claim에서 식별된다.

**대댓글은 본 사이클 범위 밖** — "삭제된 댓글이 부모면 자식 유지" 같은 정책은 대댓글 도입 시 활성화한다.

**Path Variable**
| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `postId` | string | O | 게시글 public_id (UUID), 최대 36자 |
| `commentId` | string | O | 댓글 public_id (UUID), 최대 36자 |

**Response 200** — `data: null`

```json
{ "success": true, "data": null, "message": "요청이 성공적으로 처리되었습니다." }
```

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (path variable 빈값/36자 초과) |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 403 | COMMON4031 | 본인이 작성한 댓글이 아닙니다. |
| 404 | COMMUNITY4001 | 존재하지 않는 게시글입니다. |
| 404 | COMMUNITY4002 | 존재하지 않는 댓글입니다. |

> **COMMUNITY4002 통합 처리**: (a) 댓글 publicId 미존재, (b) 이미 soft delete된 댓글의 재삭제, (c) URL의 `postId`와 댓글의 실제 게시글이 다른 경우 — 셋 다 COMMUNITY4002로 통일한다. 의미상 모두 "이 게시글에 그런 댓글 없음"이며, RESTful 자원 경로 일관성 보장 + 권한 문제(COMMON4031)와 혼동을 피한다.
>
> **참고 (v2 명세 캡처 정정)**: Notion 명세에 `COMMON4011`(401)로 적혀 있던 부분은 표준 `AUTH4011`로 정정(CLAUDE.md §9 + 공통 표준). "이미 삭제된 댓글 재삭제"는 별도 코드 신설 대신 COMMUNITY4002로 통합 처리(404 의미상 동일).

---

## 8. 주요 QnA / FAQ

`GET /api/v1/community/faq` · Auth ✅ → 자주 묻는 질문 목록.

---

## 커뮤니티 에러 코드 메모

| code | HTTP | 의미 |
| --- | --- | --- |
| `COMMUNITY4001` | 404 | 존재하지 않는 게시글입니다. |
| `COMMUNITY4002` | 404 | 존재하지 않는 댓글입니다. |

> 잘못된 카테고리/정렬 값은 `COMMON4001`로 통일. 중복(좋아요)은 `COMMON4091`.
> 도메인 고유 코드가 더 필요하면 COMMUNITY 표에 새 번호로 등록 후 사용(번호 재배치 금지).
