# 로그인 방식 결정 — ROPC 프록시 → Authorization Code flow

> 작성: 2026-05-30 / 상태: **백엔드 코드 반영 완료, IdP·프론트 설정 대기**
>
> 이 문서는 "로그인을 어떻게 처리할지"에 대한 **결정과 근거**를 남긴 기록이다.
> 기존 [`flow.md`](./flow.md) §2·[`api-spec.md`](./api-spec.md)의 로그인 부분(우리 백엔드가
> `/auth/login`으로 토큰 발급)은 **아래 결정으로 대체**된다. 팀 합의 후 두 문서에 반영 필요.

---

## 1. 한 줄 요약

> 로그인은 **Authorization Code flow**로, **프론트(앱)가 외부 IdP와 직접** 수행한다.
> 백엔드는 토큰을 **검증만** 하고(방식 B = 검증 전용), 로그인 토큰을 발급·중계하지 않는다.
> 회원가입(`/auth/register`)만 우리 API가 받아 IdP에 사용자를 프로비저닝한다.

---

## 2. 배경 — 원래는 ROPC 프록시였다

처음 설계(방식 B의 한 변형)는 **ROPC(Resource Owner Password Credentials, `grant_type=password`)** 였다.

- 사용자가 우리 `/api/v1/auth/login`에 이메일/비밀번호를 보냄
- 우리 백엔드가 그 자격증명을 **IdP 토큰 엔드포인트로 그대로 전달**
- IdP가 검증·발급한 토큰을 우리가 **중계**

즉 "우리 백엔드가 사용자의 생(raw) 비밀번호를 손에 쥐고 IdP에 대신 물어보는" 방식.

## 3. 왜 못 쓰는가 — Authentik이 사용자 ROPC를 지원하지 않음

개발기 IdP인 **Authentik(v2026.2.2)** 은 일반 사용자용 ROPC를 **지원하지 않는다.**

- Authentik의 `grant_type=password`는 **사람 로그인용이 아니라 기계 간 인증(M2M / client_credentials)** 경로로 처리된다.
- `username`엔 **서비스 계정**, `password`엔 사용자 비밀번호가 아니라 **별도 발급한 앱 토큰(INTENT_APP_PASSWORD)** 을 넣어야 한다.
- 따라서 올바른 사용자 비밀번호를 넣어도 `invalid_grant`로 거절된다. (Authentik 소스 `token.py`의
  `__post_init_client_credentials`에서 확인)

**결론: 코드 버그가 아니라 Authentik이 원래 안 해주는 것.** ROPC 프록시 설계 자체가 개발기에서 성립 불가.

> 참고: AWS Cognito는 ROPC(USER_PASSWORD_AUTH)를 지원하지만, 운영용으로 아직 구성 전이고
> 개발기(Authentik)에선 테스트가 불가능해 "개발기·운영기 동일 방식"이 깨진다.

---

## 4. 결정 — Authorization Code flow (방식 A)

**OIDC 표준 리다이렉트 로그인.** 프론트가 IdP 로그인 페이지로 사용자를 보내고, IdP가 돌려준
`code`를 토큰으로 교환한다.

```
① 사용자 → IdP 로그인 페이지에서 직접 로그인 (백엔드는 비번을 보지 않음)
② IdP → code 반환 (등록된 redirect_uri로)
③ 프론트(앱) → code를 IdP 토큰 엔드포인트에서 토큰으로 교환  ← "code 교환 주체 = 프론트"
④ 사용자 → 토큰을 들고 우리 API 호출
⑤ 백엔드 → oauth2ResourceServer(jwt)가 issuer-uri의 JWKS로 RS256 서명·만료 검증
```

### 채택 이유 (우리 프로젝트 기준)

| 근거 | 내용 |
|---|---|
| MSA | 각 서비스가 토큰을 **각자 독립 검증**(Stateless). 백엔드가 세션/토큰 교환을 들고 있을 필요 없음 |
| 방식 B 일치 | "백엔드는 검증만"이라는 기존 설계와 정확히 일치 |
| 개발·운영 동일 | OIDC 표준이라 Authentik(개발)·Cognito(운영) **둘 다 같은 코드**로 동작. yml의 `issuer-uri`만 환경별 교체 |
| 모바일 표준 | 네이티브 앱은 Authorization Code + PKCE가 업계 표준(RFC 8252) |
| 보안 | 백엔드가 사용자 비밀번호를 만지지 않음 |

> "code를 토큰으로 교환하는 주체"는 **프론트(앱)** 로 정했다(BFF 아님). MSA + Stateless + 모바일에 적합.

### 회원가입은 우리 API 유지

로그인만 IdP로 넘기고, **회원가입은 우리 `/auth/register` 유지.** 이유: 가입 시 닉네임·국적·언어 등
**우리 도메인 필드**가 필요한데 IdP는 이를 모르기 때문. 백엔드가 IdP에 사용자를 만들고(`IdpUserClient`)
로컬 회원도 같이 저장한다.

---

## 5. 백엔드 코드 변경 (반영 완료)

**삭제 (로그인 = 앱↔IdP 직접이라 백엔드 불필요):**
- `global/client/IdpAuthClient.java`, `RealIdpAuthClient.java` (ROPC 로그인 프록시)
- `domain/member/dto/request/LoginRequest.java`, `dto/response/LoginResponse.java`
- `global/client/dto/IdpToken.java`
- `MemberController`의 `POST /api/v1/auth/login`, `MemberService.login(...)` 및 구현, 로그인 단위테스트

**유지/수정:**
- `SecurityConfig` — Resource Server(`oauth2ResourceServer(jwt)`, STATELESS) **유지**. permitAll에서 `/auth/login` 제거
- 회원가입 `/auth/register` + `IdpUserClient`(`RealIdpUserClient`) **유지**
- `Member.authProviderId`(토큰 `sub` ↔ 우리 회원 매핑) **유지**

**wallet · community 전환 완료(2026-06-01):** 두 서비스도 member와 동일한 방식 B(OAuth2 Resource Server)로 전환했다. `X-User-Public-Id` 헤더 임시처리(CLAUDE.md §9)를 제거하고, `SecurityConfig`를 `oauth2ResourceServer(jwt)` + 공개경로(swagger/api-docs/actuator)만 permitAll로 교체, 컨트롤러는 토큰 custom claim `public_id`를 `@CurrentUserPublicId`(서비스별 `global/security` ArgumentResolver)로 추출한다. 인증 실패는 `AUTH4011`. **남은 곳: document-service**(헤더 임시처리 유지 — 동일 패턴으로 후속 전환).

---

## 6. 남은 작업

### 6-1. Authentik 설정 — Subject mode
gb-backend Provider의 **Subject mode**를 **"Based on the User's UUID"** 로 변경.
- 위치: Authentik Admin → Applications → **Providers** → `gb-backend` → Edit → **Advanced protocol settings** → **Subject mode**
- 이유: 가입 때 저장한 `authProviderId`(= Authentik user uuid)와 로그인 토큰의 `sub`가 **일치해야**
  "토큰 → 우리 회원" 매핑이 된다. 기본값(`hashed_user_id`)이면 어긋남.

### 6-2. 프론트/IdP 연동 (프론트 이슈 + Authentik 설정)
- Authentik에 프론트의 **redirect_uri 등록**, client 설정(public client + PKCE 권장)
- 프론트: IdP 로그인 페이지 리다이렉트 → `code` 수신 → 토큰 교환 → 토큰 보관 → API 호출
- 백엔드 코드 변경은 없음(검증 설정은 이미 완료). `issuer-uri`만 환경별 yml로.

### 6-3. ⚠️ 팀 공지 — 개발기 DB `members.password` 컬럼 드롭
방식 B에서 비밀번호는 우리 DB에 저장하지 않는다. 과거 스키마에 남아 있던 `members.password`(NOT NULL,
기본값 없음) 컬럼 때문에 가입 시 `Field 'password' doesn't have a default value` 500 에러가 났다.
`ddl-auto: update`는 **기존 컬럼을 지우지 않으므로** 각자 개발기 DB에서 수동으로 드롭해야 한다.

```sql
-- 각자 개발기 member_db에서 1회 실행 (이미 없으면 무시)
ALTER TABLE members DROP COLUMN password;
```
(작성자 개발기에는 이미 드롭 적용됨. 팀원 개발기 DB엔 남아 있을 수 있음.)

### 6-4. `application-dev.yml` 잔여 키 정리(선택)
ROPC 클라이언트 삭제로 `auth.idp.token-uri` / `client-id` / `client-secret`은 더 이상 쓰이지 않는다.
(둬도 무해. `issuer-uri` / `api-base-uri` / `admin-token`은 계속 필요.)
