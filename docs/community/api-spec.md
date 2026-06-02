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

**Path Variable**: `postId` = 게시글 public_id (UUID)

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `content` | string | O | 댓글 내용 |
| `parent_comment_public_id` | string | X | 부모 댓글 UUID. null이면 최상위, 있으면 대댓글 |

**Response 201** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 댓글 UUID |
| `post_public_id` | string | N | 게시글 UUID |
| `parent_comment_public_id` | string | Y | 부모 댓글 UUID. 최상위면 null |
| `content` | string | N | 댓글 내용 |
| `author_nickname` | string | N | 작성자 닉네임 |
| `author_is_verified` | boolean | N | 작성자 인증 배지 여부 |
| `created_at` | string | N | 작성 시각(UTC Z) |

작성 성공 시 게시글 `comment_count` +1.

**Error**: 401 AUTH4011 / 404 COMMUNITY4001(게시글 없음) / 404 COMMUNITY4002(부모 댓글 없음)

---

## 7. 댓글 목록 / 삭제

- 목록: `GET /api/v1/community/posts/{id}/comments?page=&size=` → 댓글 배열(+ `parent_comment_public_id`로 대댓글 구조) + 페이지 메타.
- 삭제: `DELETE /api/v1/community/posts/{postId}/comments/{commentId}` (본인만) → comment_count -1.

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
