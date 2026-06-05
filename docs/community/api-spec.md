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
| 주요 QnA 목록 | GET | `/api/v1/community/qna?category=&size=` | ❌ (공개) |

---

## 1. 게시글 목록·검색

`GET /api/v1/community/posts` · Auth ✅

**Query Parameter**
| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `category` | string | X | LIFE_INFO/JOB/VISA/COUNTRY/RESIDENCE/QUESTION/FREE |
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
| `category` | string | O | LIFE_INFO/JOB/VISA/COUNTRY/RESIDENCE/QUESTION/FREE |
| `title` | string | O | 제목 (1~255자) |
| `content` | string | O | 본문 (1~10,000자 — 초과 시 COMMON4001. 컬럼 TEXT 한계 내 입력단 상한, 11D community-1) |

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

**Error**: 400 COMMON4001 / 401 AUTH4011

> 필수값 누락·잘못된 category 등 Bean Validation 실패는 `GlobalExceptionHandler`가 **COMMON4001로 통일**한다(컨트롤러 Swagger와 일치). `COMMON4002`(MISSING_REQUIRED_FIELD)는 community 흐름에서 던지지 않는다.

---

## 3. 게시글 단건 조회 / 수정 / 삭제 / 번역

- 단건 조회: `GET /api/v1/community/posts/{id}` → 본문 + 작성자(닉네임/`author_is_verified`) + 카운트. 404 COMMUNITY4001.
- 수정: `PATCH /api/v1/community/posts/{id}` (본인만, 403 COMMON4031. 부분 수정 — 전송 필드만 변경, title/content 상한은 §2와 동일)
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

`GET /api/v1/community/posts/{id}/comments?page=&size=` · Auth ✅

특정 게시글에 달린 댓글을 **작성순(오래된 순)** 으로 페이지네이션해 반환한다. 삭제된 댓글(`deleted_at IS NOT NULL`)은 결과에서 제외된다. 작성자 표시 정보(닉네임/인증 배지)는 MemberClient로 조회해 채운다(DB 직접 SELECT 없음 — MSA 경계, CLAUDE.md §7). 본인 식별을 쓰지 않지만 인증은 필요하다.

**대댓글은 본 사이클 범위 밖** — 모든 항목의 `parent_comment_public_id`는 항상 null이다(§6과 동일 항목 형태).

**Path Variable**: `id` = 게시글 public_id (UUID), 최대 36자

**Query Parameter**
| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `page` | integer | X | 0-base 페이지 번호 (기본 0, 가드 0~10000) |
| `size` | integer | X | 페이지당 개수 (기본 20, 가드 1~100) |

**정렬**: `created_at ASC, id ASC` — 작성순(오래된 댓글이 먼저). created_at 동률은 id ASC를 보조 키로 사용한다(id는 외부 비노출, 정렬 키로만).

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `comments` | array | N | 댓글 목록 (작성순) |
| `comments[].public_id` | string | N | 댓글 UUID |
| `comments[].post_public_id` | string | N | 게시글 UUID |
| `comments[].parent_comment_public_id` | string | Y | 부모 댓글 UUID. **현 사이클은 항상 null** (대댓글 미지원) |
| `comments[].content` | string | N | 댓글 내용 |
| `comments[].author_nickname` | string | N | 작성자 닉네임 (MemberClient 조회) |
| `comments[].author_is_verified` | boolean | N | 작성자 인증 배지 여부 |
| `comments[].created_at` | string | N | 작성 시각 (ISO 8601 UTC `Z`) |
| `page` | integer | N | 현재 페이지 번호 (0-base) |
| `size` | integer | N | 페이지당 개수 |
| `total_elements` | integer | N | 전체 댓글 수 (삭제 제외) |
| `total_pages` | integer | N | 전체 페이지 수 |

```json
{
  "success": true,
  "data": {
    "comments": [
      {
        "public_id": "c1d2e3f4-...",
        "post_public_id": "a1b2c3d4-...",
        "parent_comment_public_id": null,
        "content": "저도 작년에 똑같은 일 겪었어요. 노동부 1350에 신고해 차액 다 받았어요.",
        "author_nickname": "Minh",
        "author_is_verified": true,
        "created_at": "2026-05-26T04:15:30Z"
      }
    ],
    "page": 0,
    "size": 20,
    "total_elements": 1,
    "total_pages": 1
  },
  "message": "요청이 성공적으로 처리되었습니다."
}
```

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (page/size 범위 위반, path variable 빈값/36자 초과) |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 404 | COMMUNITY4001 | 존재하지 않는 게시글입니다. (없거나 삭제된 게시글) |

> 응답 항목은 §6(댓글 작성)의 `data` 형태 + 페이지 메타다. 코드 구현은 명세보다 풍부할 수 있으나(예: id ASC tie-break) SSOT는 본 표다.

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

## 8. 주요 QnA 목록

`GET /api/v1/community/qna` · **Auth ❌ (공개)**

특정 카테고리의 활성 게시글을 답변(댓글) 수 내림차순으로 상위 N건 반환한다. 페이지네이션 메타는 없다(Top N 고정 목록). 작성자 정보·본문은 응답에 포함하지 않으며, 상세는 단건 조회 API(§3 `GET /posts/{id}`)로 별도 조회한다.

> **인증 불필요**: 비로그인 사용자도 인기 질문을 둘러볼 수 있도록 공개로 둔다(SecurityConfig `permitAll`). 본인 식별을 쓰지 않는 read-only Top N 조회라 보안 영향 없음. 다른 community 엔드포인트는 모두 Auth ✅.

**Query Parameter**
| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `category` | string | X | `LIFE_INFO / JOB / VISA / COUNTRY / RESIDENCE / QUESTION / FREE`. 미입력 시 `QUESTION` 카테고리만 반환 |
| `size` | integer | X | 반환 개수 (기본 5, 가드 1~100) |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `posts` | array | N | QnA 게시글 목록 (답변 수 내림차순, 동률은 최근 글 우선) |
| `posts[].public_id` | string | N | 게시글 UUID |
| `posts[].title` | string | N | 제목 |
| `posts[].comment_count` | integer | N | 답변(댓글) 수 |
| `posts[].created_at` | string | N | 작성 시각 (ISO 8601 UTC `Z`) |

```json
{
  "success": true,
  "data": {
    "posts": [
      { "public_id": "a1b2c3d4-...", "title": "E-9 비자로 근무지 변경이 가능한가요?", "comment_count": 7, "created_at": "2026-05-20T09:00:00Z" },
      { "public_id": "b2c3d4e5-...", "title": "건강보험 피부양자 등록은 어떻게 하나요?", "comment_count": 4, "created_at": "2026-05-18T14:20:00Z" }
    ]
  },
  "message": "요청이 성공적으로 처리되었습니다."
}
```

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (잘못된 카테고리 / size 범위 1~100 위반) |

> **명세 모호성 해석 (옵션 A)**: 원 명세에 "category = QUESTION 필터"(고정)와 "Query category"(상위 카테고리 필터 — LIFE_INFO 등)가 동시에 적혀 있으나 현 `PostCategory` enum은 단일 카테고리만 갖는다. 가장 자연스러운 운영 의미로 **"category 미입력 → QUESTION 카테고리 / 입력 → 해당 카테고리"** 로 통일했다 (답변 많은 인기글 = QnA의 일반화).
>
> **인증 정책**: Notion 명세 캡처에 401 `COMMON4011`(인증 필요)이 적혀 있으나, 본 API는 비로그인 사용자 접근이 가능해야 하므로 SecurityConfig에서 공개 처리 → 401 응답 자체가 발생하지 않는다.
>
> **정렬 tie-break**: `comment_count DESC, id DESC` — comment_count 동률에서 최근 글이 위로 오도록 id DESC를 보조 키로 사용한다(id는 외부 비노출, 정렬 키로만).

---

## 커뮤니티 에러 코드 메모

| code | HTTP | 의미 |
| --- | --- | --- |
| `COMMUNITY4001` | 404 | 존재하지 않는 게시글입니다. |
| `COMMUNITY4002` | 404 | 존재하지 않는 댓글입니다. |

> 잘못된 카테고리/정렬 값은 `COMMON4001`로 통일. 중복(좋아요)은 `COMMON4091`.
> 도메인 고유 코드가 더 필요하면 COMMUNITY 표에 새 번호로 등록 후 사용(번호 재배치 금지).
