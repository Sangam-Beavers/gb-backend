# 인증 · 회원 · 마이페이지 — 요구사항 (Requirements)

> 도메인 prefix: `/auth`, `/members`
> 사용 뷰: 1. 로그인 / 8. 마이페이지
> 정본 명세: [`api-spec.md`](./api-spec.md) · 공통 규칙: [`../conventions.md`](../conventions.md)

---

## 1. 범위

이 기능은 두 화면군을 담당한다.

- **1. 로그인** — 로그인, 회원가입, 소셜 로그인, 비밀번호 찾기/재설정, 중복 확인
- **8. 마이페이지** — 프로필 조회/수정, 신분증 인증, 언어 설정, 활동 내역 진입, 탈퇴

---

## 2. 유저 스토리

### 로그인/회원가입
- 사용자는 **이메일+비밀번호**로 로그인할 수 있다.
- 사용자는 **Google로 간편 로그인/가입**할 수 있다.
- 회원가입 시 이메일·비밀번호·비밀번호 확인·이름·닉네임(중복 확인)·국적·주 사용 언어·약관 동의를 입력한다.
- 비밀번호를 잊은 경우 **재설정 링크를 이메일로 받아** 재설정할 수 있다.
- 언어 선택 박스는 로그인/회원가입 화면 상단에 항상 노출된다.

### 마이페이지
- 사용자는 자신의 **프로필(닉네임, 국적, 인증 배지 등)** 을 조회/수정할 수 있다.
- 사용자는 **활동 내역**(주머니 내역, 환전 내역, 서류 분석 내역)으로 이동할 수 있다. (각 내역 API는 remittance/document-analysis 폴더 소관)
- 사용자는 **언어 설정**을 변경할 수 있다.
- 사용자는 **추가 인증(신분증)** 을 통해 인증 배지를 얻고 송금 서비스를 이용할 수 있다.
- 사용자는 계정을 **탈퇴**할 수 있다. (soft delete)

---

## 3. 사용자 등급과 인증

| 등급 | 인증 수단 | 가능 기능 |
| --- | --- | --- |
| 미인증 | 이메일만 | 커뮤니티, 서류 분석 조회 |
| 인증됨 ✓ | 신분증 인증 | + 충전 · 송금 · 환전 |

**신분증 유형** (`identity_document_type`): `ALIEN_REGISTRATION`(외국인등록증) / `PASSPORT`(여권) / `NATIONAL_ID`(본국 신분증)

- 신분증 번호는 **AES-256 암호화 저장**(또는 해시), 원본 최소 보관.
- 인증 승인(APPROVED) 시 `members.is_verified = TRUE` 업데이트 → 커뮤니티/송금에서 신뢰도 표시.

---

## 4. 인증 기술 메모 (방식 B = 외부 IdP 검증 전용)

- **토큰 발급·비밀번호 보관은 외부 IdP가 담당**, 백엔드는 검증만(OAuth2 Resource Server). 자체 JWT 발급 안 함.
- 로그인은 프론트가 IdP와 직접(Authorization Code flow). 백엔드 로그인/재발급 엔드포인트 없음.
- 환경별 IdP: 개발 = Authentik, 운영/스테이징 = Cognito. (Spring은 `issuer-uri` 설정만 다름)
- `members.auth_provider_id`에 JWT `sub`(IdP 식별자) 저장. 본인 식별용 `public_id`는 가입 시 IdP attribute로
  저장해 토큰 custom claim(`public_id`)으로 노출 → `@CurrentUserPublicId`로 추출(#83).
- ⚠️ 아래는 방식 B에서 재정의/확인이 필요한 항목 (팀 논의):
  - 로그아웃 무효화 방식 (로컬삭제 / IdP end-session / 블랙리스트 중 택1)
  - 비밀번호 재설정 → IdP 경유 + SMTP(메일 발송) 인프라 선행
  - 로그인 실패 Rate Limiting → 로그인이 IdP에서 일어나므로 적용 위치 재검토
  - 상세: [`spec-realignment-auth-b.md`](./spec-realignment-auth-b.md)

---

## 5. 비기능 요구사항

- 비밀번호는 해시 저장(평문 금지).
- 이메일/닉네임 중복 확인은 가입 전 별도 API로 사전 검사.
- 회원 식별자는 응답에서 `public_id`만 노출. 내부 `id` 금지.

---

## 6. 관련 데이터

- `members`, `user_verifications` (member 도메인 내부 → BIGINT FK)
- 상세 스키마: [`../database.md`](../database.md) §2
