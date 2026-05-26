# 인증 · 회원 · 마이페이지 — 처리 흐름 (Flow)

> 정본 명세: [`api-spec.md`](./api-spec.md)

---

## 1. 회원가입 흐름

```
[회원가입 화면]
이메일/비밀번호/이름/닉네임/국적/언어/약관 입력
  │
  ├─ (입력 중) 이메일 중복 확인  GET /api/v1/members/check-email?email=
  ├─ (입력 중) 닉네임 중복 확인  GET /api/v1/members/check-nickname?nickname=
  │
회원가입  POST /api/v1/auth/register
  → users INSERT (is_verified=FALSE)
  → 인증 이메일 발송 트리거
  → 201 { email }
  │
[이메일 인증 안내 화면]
가입 인증 이메일 발송(재발송)  POST /api/v1/auth/email/verify-request
  │
사용자가 이메일 링크 클릭 → 인증 완료
```

---

## 2. 로그인 흐름

### 이메일 로그인
```
[로그인 화면]
이메일/비밀번호 입력
  │
이메일 로그인  POST /api/v1/auth/login
  ├─ 성공 → 200 { access_token, refresh_token, token_type, expires_in }
  ├─ 자격 오류 → 401 AUTH4001
  ├─ 이메일 미인증 → 403 AUTH4004
  └─ 과다 시도 → 429 COMMON4291  (Redis Rate Limit)
```

### Google 소셜 로그인
```
Google OAuth 인가 코드 획득(클라이언트)
  │
Google 소셜 로그인  POST /api/v1/auth/login/google
  ├─ 기존 회원 → 200 토큰 발급
  └─ 신규 회원 → is_new_member=true 반환
        │
     [추가 정보 입력 화면]
     Google 가입 후 추가 정보  POST /api/v1/members/profile (또는 social-profile)
       (닉네임/국적/언어/약관 등 보완)
```

### 토큰 갱신 / 로그아웃
```
Access 만료 → JWT 토큰 refresh  POST /api/v1/auth/reissue  (refresh_token 사용)
로그아웃  POST /api/v1/auth/logout
  → 해당 토큰을 Redis 블랙리스트 등록 (blacklist:{token}, TTL=남은만료)
```

---

## 3. 비밀번호 찾기/재설정 흐름

```
[비밀번호 찾기]
재설정 링크 발송  POST /api/v1/auth/password/reset-request (또는 reset-email)
  → 이메일로 재설정 링크 발송
  │
사용자가 링크 클릭 → [재설정 화면]
비밀번호 재설정  POST /api/v1/auth/password/reset (토큰 + 새 비밀번호)
```

---

## 4. 마이페이지 흐름

```
[마이페이지]
내 프로필 조회  GET /api/v1/members/me
  → 닉네임, 국적, is_verified(배지), temperature_grade 등
  │
├─ 프로필 수정  PATCH /api/v1/members/me
├─ 프로필 사진 변경  PATCH /api/v1/members/me/profile-image
│
├─ [활동 내역]  ※ 타 도메인 API
│    ├─ 주머니 내역   GET /api/v1/wallets/me/transactions   (remittance)
│    ├─ 환전 내역     GET /api/v1/exchanges                  (remittance)
│    └─ 서류 분석 내역 GET /api/v1/documents                  (document-analysis)
│
├─ [설정]
│    ├─ 알림 설정 조회/저장  GET/PATCH /api/v1/members/me/notification-settings
│    └─ 언어 설정 조회/변경  GET/PATCH /api/v1/members/me/language
│
├─ [인증]
│    ├─ 인증 상태 조회  GET /api/v1/members/me/verification
│    └─ 신분증 인증 요청 POST /api/v1/members/me/verification
│         → user_verifications INSERT (status=PENDING)
│         → 관리자 검토 후 APPROVED 시 users.is_verified=TRUE
│
└─ 탈퇴  DELETE /api/v1/members/me  (soft delete: users.deleted_at SET)
```

---

## 5. 상태/예외 처리 포인트

- 로그인 실패 누적 → `login:fail:user:{id}` INCR, 5회 초과 시 5분 차단(429).
- 로그아웃된 토큰으로 접근 → 블랙리스트 조회 후 401.
- 미인증 계정의 충전/송금/환전 시도 → 해당 도메인에서 차단(인증 배지 확인).
- 신분증 인증은 비동기(관리자 검토). 요청 직후 상태는 PENDING.
