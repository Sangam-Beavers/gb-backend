# 인증 명세서 방식 B 정합화 정리 (팀 논의용)

> 작성일: 2026-05-31 / 작성: 주영 서
> 목적: 로그인을 **방식 B(외부 IdP 검증 전용 + Authorization Code flow)** 로 전환한 뒤,
> 기존 명세서(`api-spec.md`, `flow.md`, `requirements.md`)가 여전히 **방식 A(백엔드 자체 JWT 발급)**
> 기준으로 남아 있어 발생하는 불일치를 정리한다. 이 표를 기준으로 팀 합의 후 정본 문서를 갱신한다.

---

## 0. 한 줄 배경

- **방식 A (옛 설계, 명세서에 적힌 것):** 백엔드가 이메일/비번을 받아 검증하고 자체 JWT(access/refresh)를 발급.
- **방식 B (현재 코드, #37 머지 완료):** 토큰 발급·비번 보관은 **외부 IdP(개발=Authentik, 운영=Cognito)**.
  백엔드는 들어온 토큰을 **검증만** 함. 로그인은 프론트가 IdP와 직접(Authorization Code flow).
- 결과: 명세서의 "토큰 발급 / 비번 저장 / 리프레시 / 블랙리스트" 전제가 더 이상 성립하지 않음 → 정합화 필요.

---

## 1. 엔드포인트별 정합화 표

| API | 명세서(방식 A) 현재 기재 | 방식 B에서 실제 | 조치 |
| --- | --- | --- | --- |
| 이메일 로그인 `POST /auth/login` | 백엔드가 토큰 발급 | 백엔드 엔드포인트 **없음**. 프론트가 IdP와 직접 | **폐기 표시** (코드 구현=완료가 아니라 "백엔드 없음"으로) |
| 토큰 재발급 `POST /auth/reissue` | 백엔드가 refresh로 재발급 | 토큰 갱신도 **IdP 소관**. 백엔드 없음 | **폐기 검토** (담당=Kyubo와 확인) |
| 회원가입 `POST /auth/register` | 백엔드가 비번 해시 저장 | 백엔드가 IdP에 사용자 프로비저닝, **비번 미저장** | **유지** (설명만 방식 B로: 비번은 IdP 보관) |
| Google 소셜 로그인 `POST /auth/login/google` | 백엔드가 Google 인가코드 받아 토큰 발급 | **Authentik이 Google 중개.** 사용자는 IdP 로그인 페이지에서 "Google로 로그인" → 이후 이메일 로그인과 동일(백엔드는 검증만) | **재정의 필요** (아래 §2) |
| Google 가입 후 추가정보 `PATCH /members/me/social-profile` | 소셜 신규회원 추가정보 보완 | 방식 B에서도 필요(우리 도메인 필드: 닉네임/국적/언어). 단 본인 식별은 토큰 sub | **유지** (단 인증 방식 헤더→토큰 확정 필요) |
| 로그아웃 `POST /auth/logout` | 토큰을 Redis 블랙리스트 등록 | Stateless(방식 B)에선 서버가 토큰을 들고 있지 않음. 무효화하려면 IdP end-session 또는 블랙리스트 별도 도입 | **재정의 필요** (아래 §3) |
| 비밀번호 찾기/재설정 `POST /auth/password/*` | 백엔드가 토큰 만들어 메일 발송 + 새 비번 저장 | 비번은 IdP 보관 → 재설정도 IdP 경유. 메일(SMTP) 인프라 필요 | **재정의 필요** (아래 §4) |
| 이메일/닉네임 중복확인 `GET /members/check-*` | — | 방식 B와 무관하게 동작 | **유지** (구현 완료) |

---

## 2. Google 소셜 로그인 — 방식 B 재정의안

**권장:** Authentik의 소셜 로그인(Federation/Source) 기능으로 Google을 연동한다.

- Authentik 관리자: Google을 OAuth Source로 등록 → 로그인 화면에 "Google로 로그인" 노출.
- 사용자 흐름: 프론트 → IdP 로그인 페이지 → "Google" 선택 → Google 인증 → IdP가 토큰 발급 → 백엔드는 검증만.
- **백엔드 신규 코드 거의 없음.** 이미 만든 토큰 검증(`oauth2ResourceServer`)이 그대로 동작.
- 단, "신규 소셜 회원의 도메인 필드(닉네임/국적/언어) 보완"은 우리 API(`social-profile`)가 담당.

**논의 필요:** `POST /auth/login/google`(auth_code 받아 백엔드가 처리) 엔드포인트를 **폐기**하고
Authentik 중개로 갈지 확정. 폐기 시 `AUTH4006`(Google 인증 실패)도 백엔드에서 불필요.

---

## 3. 로그아웃 — 방식 B 재정의안

방식 B는 Stateless(서버가 토큰을 저장하지 않음)라, 명세의 "Redis 블랙리스트"는 전제가 다르다. 선택지:

- **A. 클라이언트 로컬 토큰 삭제만** (가장 단순) — 앱이 보관 토큰을 지움. 서버 무효화 없음.
- **B. IdP end-session 연동** — IdP 세션까지 종료(이전 프론트 시도에서 본 방식). redirect_uri 등록 필요.
- **C. 블랙리스트 별도 도입** — 굳이 즉시 무효화가 필요하면 Redis 블랙리스트 + 검증 필터 추가(방식 B에 역행, 비용 큼).

**논의 필요:** 데모/MVP 범위에서 A로 충분한지, 보안 요건상 B/C가 필요한지.

---

## 4. 비밀번호 재설정 — 방식 B 재정의안

비번은 IdP가 보관하므로 재설정도 IdP 경유. 선택지:

- **A. Authentik recovery flow로 위임** — 사용자를 Authentik 비번 찾기 페이지로. 메일 발송·재설정 모두 IdP.
  (Authentik recovery flow 활성화 + SMTP 설정 필요)
- **B. 백엔드가 관리 API로 재설정** — 우리가 메일 발송(SMTP) + 링크 검증 후 Authentik `set_password` 호출.
  (`RealIdpUserClient`에 이미 쓰는 `set_password/` 엔드포인트 재사용 가능)

**공통 선행:** 개발기 **SMTP(메일 발송)** 가용 여부 + Authentik recovery flow 설정 확인.

---

## 5. 에러 코드 영향 (conventions.md §에러표)

방식 B 전환으로 **백엔드에서 안 쓰게 되는** 코드 후보 (폐기 확정 전까지 표에 남겨두되 "방식 B 미사용" 주석 권장):

- `AUTH4001` 이메일/비번 불일치 — 백엔드가 비번 검증 안 함 → IdP 소관
- `AUTH4004`(재발급), `AUTH4005`(refresh 만료) — 재발급 폐기 시 불필요
- `AUTH4006` Google 인증 실패 — Authentik 중개 시 불필요
- 계속 쓰는 것: `AUTH4011`(검표원 표준 — 토큰 누락/무효), `COMMON*`, `MEMBER4002/4003`

> 번호는 재배치 금지(클라이언트 호환). 폐기는 "미사용 표시"로, 삭제하지 않음.

---

## 6. 데이터/인프라 영향

- `members.password` 컬럼 — 방식 B에서 미사용. 개발기 DB 드롭 필요(팀 공지). (`login-authorization-code.md` §6-3)
- SMTP(메일 발송) — 비번 재설정의 선행. **인프라 담당 확인 필요.**
- Redis 블랙리스트 — 로그아웃 방식 결정에 따라 도입 여부 갈림(§3).

---

## 7. 팀 결정이 필요한 항목 (체크리스트)

- [ ] `/auth/login`, `/auth/reissue` 폐기 확정 (명세서 정본 갱신)
- [ ] Google 로그인: Authentik 중개(방식 B)로 전환 확정 → `/auth/login/google` 폐기 여부
- [ ] 로그아웃 방식: A(로컬삭제) / B(end-session) / C(블랙리스트) 중 선택
- [ ] 비번 재설정 방식: A(IdP 위임) / B(백엔드 관리 API) 중 선택
- [ ] 개발기 SMTP 가용 여부 + Authentik recovery flow 설정 확인 (담당자 지정)
- [ ] `members.password` 컬럼 드롭 팀 전체 적용
- [ ] 에러코드 표 "방식 B 미사용" 표시 갱신
