# 커뮤니티 — 처리 흐름 (Flow)

> 정본 명세: [`api-spec.md`](./api-spec.md)

---

## 1. 게시판 진입 / 목록 / 검색

```
[커뮤니티 진입]
게시글 목록·검색  GET /api/v1/community/posts?category=&keyword=&sort=&page=&size=
  - keyword 없으면 일반 목록, 있으면 검색 모드
  - sort: latest(최신) / popular(인기) / accuracy(정확도, keyword 있을 때만)
  - 응답: posts[] + page/size/total_elements/total_pages
  │
주요 QnA/FAQ  GET /api/v1/community/faq   (또는 qna)
```

---

## 2. 게시글 상세 / 작성 / 수정 / 삭제

```
[게시글 상세]
게시글 단건 조회  GET /api/v1/community/posts/{id}
  → 본문 + 작성자(닉네임/인증배지/온도) + like_count/comment_count
  │
  ├─ 번역 보기  GET /api/v1/community/posts/{id}/translation?language=
  │
[작성]
게시글 작성  POST /api/v1/community/posts
  Body: { category, title, content, image_urls? }
  → 201 { public_id, ... , like_count:0, comment_count:0 }
  │
[수정]
게시글 수정  PATCH /api/v1/community/posts/{id}
  → 본인만 가능(403 COMMON4031)
  │
[삭제]
게시글 삭제  DELETE /api/v1/community/posts/{id}
  → soft delete (deleted_at SET), 본인만
```

---

## 3. 댓글 / 대댓글

```
댓글 목록 조회  GET /api/v1/community/posts/{id}/comments?page=&size=
  → 댓글 + parent_comment_public_id로 대댓글 구조
  │
댓글 작성  POST /api/v1/community/posts/{postId}/comments
  Body: { content, parent_comment_public_id? }
    - parent 없으면 최상위, 있으면 대댓글
  → 201, 게시글 comment_count +1
  │
댓글 삭제  DELETE /api/v1/community/posts/{postId}/comments/{commentId}
  → 본인만, comment_count -1
```

---

## 4. 좋아요 / 관심글

```
게시글 좋아요  POST   /api/v1/community/posts/{id}/likes     → like_count +1
좋아요 취소    DELETE /api/v1/community/posts/{id}/likes     → like_count -1
  (중복 방지: (member, target_type, target_id) UNIQUE)
  │
관심글 목록    GET /api/v1/community/posts/liked?sort=&page=&size=
  → 내가 좋아요한 게시글 + liked_at
```

---

## 5. 신고

```
게시글 신고  POST /api/v1/community/posts/{postId}/reports
  Body: { reason(SPAM/INAPPROPRIATE/MISINFORMATION/HATE/OTHER), detail? }
  → 201 { public_id, post_public_id, reason, created_at }
  → 중복 신고 시 409 COMMON4091
  │
댓글 신고    POST /api/v1/community/posts/{postId}/comments/{commentId}/reports
  Body: { reason, detail? }
  → 201
```

---

## 6. 이웃 온도 (조회 / 평가)

```
이웃 온도 조회  GET /api/v1/community/members/{memberId}/temperature
  → { member_public_id, nickname, temperature_grade, average_score, review_count }
  (게시글 카드/상세의 작성자 온도 배지 표시용)
  │
이웃 온도 평가  POST /api/v1/community/members/{memberId}/temperature
  Body: { score(1~5), comment? }
    - 자기 평가 불가, 중복 평가 불가
  → user_reviews INSERT → 대상 회원 temperature_grade 재집계
```

---

## 7. 상태/예외 처리 포인트

- 게시글 없음 → `COMMUNITY4001`(404), 댓글 없음 → `COMMUNITY4002`(404).
- 본인 글/댓글이 아닌데 수정·삭제 → `COMMON4031`(403).
- 중복 좋아요/신고/평가 → `COMMON4091`(409) 또는 멱등 처리.
- 잘못된 카테고리/정렬/사유 값 → `COMMON4001`(400).
- 회원 없음(온도 조회) → `MEMBER4001`(404).
- 작성/신고/평가 등 생성 계열은 모두 `201 Created`.
