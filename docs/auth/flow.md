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

## 2. 로그인 흐름 (방식 B = Authorization Code flow)

### 이메일 로그인 — 프론트가 IdP와 직접 (백엔드 엔드포인트 없음)
```
[로그인 화면] → "로그인" 버튼
  │
프론트 → IdP(Authentik) 로그인 페이지로 리다이렉트 (PKCE)
  │  사용자가 IdP 화면에서 직접 로그인 (백엔드는 비밀번호를 보지 않음)
IdP → code 반환 (등록된 redirect_uri로)
  │
프론트 → IdP 토큰 엔드포인트에서 code를 토큰으로 교환 (access/id token)
  │
사용자 → 토큰을 들고 우리 API 호출 (Authorization: Bearer)
  │
백엔드 → OAuth2 Resource Server가 issuer-uri JWKS로 서명·만료 검증 (실패 → AUTH4011)
         토큰 custom claim public_id로 본인 식별
```
> 백엔드엔 `/auth/login`이 없다(폐기). 자격 오류·미인증 처리는 IdP가 담당한다.

### Google 소셜 로그인 — ⚠️ 방식 B 재정의 필요 (팀 논의)
```
검토 방향: Authentik에 Google을 소셜 Source로 연동
  → 사용자가 IdP 로그인 페이지에서 "Google로 로그인" 선택
  → 이후는 이메일 로그인과 동일 (IdP가 토큰 발급, 백엔드는 검증만)
  → 신규 소셜 회원의 도메인 필드 보완만 우리 API: POST /api/v1/members/me/social-profile
```
> 방식 A의 `POST /auth/login/google`(백엔드가 auth_code 처리)는 폐기 검토 대상. 확정 후 반영.

### 토큰 갱신 / 로그아웃
```
토큰 갱신: 프론트가 IdP와 직접 처리 (백엔드 /auth/reissue 폐기 검토)
로그아웃:  ⚠️ 방식 B 재정의 필요 — 로컬삭제 / IdP end-session / 블랙리스트 중 택1 (팀 논의)
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
