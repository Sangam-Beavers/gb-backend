# 인증 · 회원 · 마이페이지 — API 명세 (정본)

> 이 문서는 Notion 개별 상세 명세를 옮긴 **정본(正本)** 이다.
> 전역 규칙(응답 래퍼/에러 코드/금액·식별자·시각)은 [`../conventions.md`](../conventions.md)를 따른다.
> 모든 성공 응답은 `{ "success": true, "data": {...}, "message": "..." }` 래퍼로 감싼다. 아래 표의 필드는 `data` 내부 필드다.
>
> ⚠️ **인증 방식 = 방식 B (외부 IdP 검증 전용 + Authorization Code flow).** 토큰 발급·비밀번호 보관은 외부 IdP(개발=Authentik, 운영=Cognito)가 담당하고, 백엔드는 들어온 JWT를 **검증만** 한다(OAuth2 Resource Server). 로그인은 프론트(앱)가 IdP와 직접 수행하며, 백엔드에는 로그인/토큰발급 엔드포인트가 없다.
>
> 본인 식별은 JWT custom claim **`public_id`**(UUID)에서 추출한다 — 컨트롤러에서 `@CurrentUserPublicId String userPublicId`로 주입받는다(`X-User-Public-Id` 헤더 임시처리는 인증 적용 완료된 서비스에서 대체됨). claim 누락/토큰 무효는 `AUTH4011`로 fail-fast. 회원가입 시 우리 `public_id`를 IdP 사용자 attribute로 저장해 토큰 claim으로 노출한다(토큰 sub ↔ publicId 매핑). 상세: [`../conventions.md`](../conventions.md) §9·§14, [`login-authorization-code.md`](./login-authorization-code.md), [`spec-realignment-auth-b.md`](./spec-realignment-auth-b.md).
>
> 🔧 **방식 B 정합화 진행 중:** 아래 명세 중 일부는 방식 A(백엔드 자체 JWT 발급) 기준 잔재가 남아 있다. **확정된 변경**(로그인·토큰재발급 폐기, 회원가입 IdP 프로비저닝)은 반영했고, **팀 논의가 필요한 항목**(Google 로그인·로그아웃·비번 재설정)은 "⚠️ 재정의 필요"로 표시만 했다. 결정 후 확정 반영한다.

---

## 엔드포인트 목록

### 인증 (/auth)
| API | Method | Endpoint | Auth | 비고 |
| --- | --- | --- | --- | --- |
| ~~이메일 로그인~~ | ~~POST~~ | ~~`/api/v1/auth/login`~~ | — | ❌ **폐기** — 로그인은 프론트가 IdP와 직접(Authorization Code flow). 백엔드 엔드포인트 없음 |
| 회원가입 | POST | `/api/v1/auth/register` | ❌ | ✅ 구현 완료 (IdP 프로비저닝 + publicId attribute 저장) |
| Google 소셜 로그인 | POST | `/api/v1/auth/login/google` | ❌ | ⚠️ 방식 B 재정의 필요 (Authentik 소셜 연동 중개로 전환 검토 — 팀 논의) |
| ~~토큰 재발급~~ | ~~POST~~ | ~~`/api/v1/auth/reissue`~~ | — | ❌ **폐기 검토** — 토큰 갱신은 IdP 소관. (Kyubo 담당 확인) |
| 로그아웃 | POST | `/api/v1/auth/logout` | ✅ | ⚠️ 방식 B 재정의 필요 (Stateless라 무효화 방식 재논의 — 로컬삭제/IdP end-session/블랙리스트) |
| 재설정 링크 발송 | POST | `/api/v1/auth/password/reset-request` | ❌ | ⚠️ SMTP 선행 + 비번은 IdP 보관 → IdP 경유 재설정 |
| 비밀번호 재설정 | POST | `/api/v1/auth/password/reset` | ❌ | ⚠️ SMTP 선행 + IdP set_password 경유 |
| 서버 health check | GET | `/actuator/health` | ❌ | Spring Actuator 기본 경로(`management.endpoints` 노출 설정 — 루트 `/health` 재매핑 없음, 11A 정합) |
| 이메일/닉네임 중복 확인 | GET | `/api/v1/members/check-*` | ❌ | ✅ 구현 완료 |

### 회원 (/members)
| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 이메일 중복 확인 | GET | `/api/v1/members/check-email?email={}` | ❌ |
| 닉네임 중복 확인 | GET | `/api/v1/members/check-nickname?nickname={}` | ❌ |
| Google 가입 후 추가 정보 | POST | `/api/v1/members/me/social-profile` (소셜 프로필 보완) | ✅ |
| 내 프로필 조회 | GET | `/api/v1/members/me` | ✅ |
| 프로필 수정 | PATCH | `/api/v1/members/me` | ✅ |
| 프로필 사진 변경 (※ 미구현) | PATCH | `/api/v1/members/me/profile-image` | ✅ |
| 인증 상태 조회 | GET | `/api/v1/members/me/verification` | ✅ |
| 신분증 인증 요청 | POST | `/api/v1/members/me/verification` | ✅ |
| 언어 설정 조회 | GET | `/api/v1/members/me/language` | ✅ |
| 언어 설정 변경 | PATCH | `/api/v1/members/me/language` | ✅ |
| 탈퇴 | DELETE | `/api/v1/members/me` | ✅ |
| 회원 표시정보 배치 조회 (서비스 간) | GET | `/api/v1/members/display-info?public_ids={a,b,c}` | ✅ |
| 이메일로 회원 표시정보 조회 (서비스 간) | GET | `/api/v1/members/by-email?email={}` | ✅ |

---

## 1. 이메일 로그인 — ❌ 폐기 (방식 B)

> **이 엔드포인트는 폐기되었다.** 방식 B에서 로그인은 프론트(앱)가 IdP 로그인 페이지에서 직접
> 수행하고(Authorization Code flow), 백엔드는 토큰을 발급·중계하지 않는다. 따라서 `POST /api/v1/auth/login`,
> `access_token`/`refresh_token` 발급, `AUTH4001`(비밀번호 불일치) 등 방식 A 잔재는 모두 제거된다.
> 백엔드는 들어온 토큰을 OAuth2 Resource Server로 검증만 한다(검증 실패 → AUTH4011).
> 상세: [`login-authorization-code.md`](./login-authorization-code.md).

---

## 2. 회원가입

`POST /api/v1/auth/register` · Auth ❌

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `email` | string | O | 가입 이메일 |
| `password` | string | O | 비밀번호 |
| `name` | string | O | 실명 |
| `nickname` | string | O | 닉네임 |
| `nationality` | string | O | 국적 (ISO 3166-1 alpha-2, 예: "VN") |
| `language` | string | O | 주 사용 언어 (BCP 47 소문자, 예: "vi") |
| `terms_agreed` | boolean | △ | 이용약관 동의 (※ 임시: 프론트 미연동으로 **미전송 허용** — 미전송 시 백엔드가 동의 처리. 전송 시 `false`는 거부. 프론트 연동 후 필수(O)로 복구) |
| `privacy_agreed` | boolean | △ | 개인정보 처리방침 동의 (terms_agreed와 동일 임시 정책) |

**Response 201** — `data`: `{ "public_id": "...", "email": "user@example.com", "nickname": "..." }`
message: "회원가입이 완료되었습니다."

> ✅ **구현 완료 (방식 B).** 비밀번호는 우리 DB에 저장하지 않고 IdP(Authentik)가 보유·검증한다.
> 가입 흐름: ① 이메일/닉네임 중복 검사 → ② `public_id`(UUID) 선생성 → ③ IdP에 사용자 프로비저닝
> (`IdpUserClient`, 비밀번호 설정 + `attributes.public_id` 저장) → ④ 받은 IdP sub를 `authProviderId`로
> 우리 DB에 저장. IdP의 `public_id` attribute는 로그인 토큰의 custom claim(`public_id`)으로 노출돼
> "토큰 sub ↔ 우리 회원" 매핑에 쓰인다(#83).

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. |
| 409 | MEMBER4002 | 이미 사용 중인 이메일입니다. |
| 409 | MEMBER4003 | 이미 사용 중인 닉네임입니다. |
| 500 | COMMON5000 | IdP 프로비저닝 실패 등 서버 오류. |

---

## 3. Google 소셜 로그인 — ⚠️ 방식 B 재정의 필요 (팀 논의)

> 아래는 방식 A(백엔드가 Google 인가코드 받아 자체 토큰 발급) 기준 잔재다. 방식 B에서는
> **Authentik의 소셜 로그인(Source/Federation) 기능으로 Google을 연동**하는 방안을 검토한다 —
> 사용자는 IdP 로그인 페이지에서 "Google로 로그인"을 누르고, 이후는 일반 로그인과 동일(백엔드는 토큰 검증만).
> 이 경우 `POST /auth/login/google`(백엔드가 auth_code 처리)·`AUTH4006`은 불필요해진다.
> 신규 소셜 회원의 도메인 필드 보완(`social-profile`)만 우리 API가 담당. **확정 전까지 아래 명세는 참고용.**

`POST /api/v1/auth/login/google` · Auth ❌

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `auth_code` | string | O | Google OAuth 인가 코드 |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `access_token` | string | N | |
| `refresh_token` | string | N | |
| `token_type` | string | N | "Bearer" |
| `expires_in` | integer | N | |
| `is_new_member` | boolean | N | true이면 추가 정보 입력 화면으로 이동 |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. |
| 401 | AUTH4006 | Google 인증에 실패했습니다. |
| 409 | MEMBER4002 | 이미 사용 중인 이메일입니다. |

> ✅ 코드 분리 확정: 원래 명세에는 Google 인증 실패가 `AUTH4005`로 적혀 있었으나, `AUTH4005`는 토큰 재발급 명세의 "리프레시 토큰 만료"가 시드 번호 순서상 먼저 점유했다(아래 §4 참고). 따라서 **Google 인증 실패는 신규 `AUTH4006`으로 분리**한다. (Notion 정본도 이 명세의 에러 코드를 `AUTH4005` → `AUTH4006`으로 수정 필요)

---

## 4. 토큰 재발급 — ❌ 폐기 검토 (방식 B / Kyubo 담당 확인)

> 방식 B에서 토큰 발급·갱신은 IdP 소관이므로 백엔드 재발급 엔드포인트는 불필요하다.
> 프론트가 IdP와 토큰 갱신(refresh)을 직접 처리한다. 아래는 방식 A 잔재이며 폐기 대상.
> (이 엔드포인트는 Kyubo 담당분이므로 폐기 확정은 담당자 확인 후.)

`POST /api/v1/auth/reissue` · Auth ❌ (Body의 refresh_token이 자격증명)

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `refresh_token` | string | O | 리프레시 토큰 (JWT) |

**Response 200** — `data`: `access_token`, `refresh_token`(Rotation 적용, 기존 무효화), `token_type`="Bearer", `expires_in`(int)

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4002 | 필수 입력 항목이 누락되었습니다. |
| 401 | AUTH4004 | 유효하지 않은 리프레시 토큰입니다. |
| 401 | AUTH4005 | 만료된 리프레시 토큰입니다. 다시 로그인해 주세요. |

> ✅ `AUTH4005`(리프레시 토큰 만료)는 이 명세가 정본으로 고정한다. Google 인증 실패가 쓰던 동일 번호는 `AUTH4006`으로 분리했다(§3 참고).

---

## 5. 로그아웃 — ⚠️ 방식 B 재정의 필요 (팀 논의)

`POST /api/v1/auth/logout` · Auth ✅

> 방식 A 기준 "Redis 블랙리스트 등록"은 방식 B(Stateless)와 전제가 다르다. 백엔드가 토큰을 보관하지
> 않으므로 무효화 방식을 팀이 정해야 한다. 선택지: ① 클라이언트 로컬 토큰 삭제만(가장 단순) /
> ② IdP end-session 연동(IdP 세션까지 종료) / ③ Redis 블랙리스트 별도 도입(즉시 무효화가 꼭 필요할 때).
> **확정 후 반영.** (참고: [`spec-realignment-auth-b.md`](./spec-realignment-auth-b.md) §3)

---

## 6. 비밀번호 찾기/재설정 — ⚠️ 방식 B 재정의 + SMTP 선행 필요

- 재설정 링크 발송: `POST /api/v1/auth/password/reset-request` (Body: `email`) → 이메일 발송. 200.
  **요청 빈도 초과 시 `COMMON4291`(429) — 이메일 단위 rate-limit(`ratelimit:pwreset:{email}`, 1시간/5회,
  MEM-04 메일 폭탄 차단. 키는 trim+소문자 정규화, Redis 장애 시 fail-open).** 가입 여부는 응답으로 노출하지
  않으며(미가입도 200), 토큰·발송 이메일은 입력값이 아니라 **회원의 저장 이메일(가입 표기)** 을 사용한다
  (IdP username 정확 일치 보장 — 11D member-idp-3).
- 비밀번호 재설정: `POST /api/v1/auth/password/reset` (Body: `token`, `new_password`) → 200. 토큰 무효/만료 시 `400 MEMBER4004`.

> 방식 B에서 비밀번호는 IdP가 보관하므로 재설정도 IdP를 경유한다(Authentik recovery flow 위임 또는
> 백엔드가 IdP `set_password` 호출). 어느 쪽이든 **재설정 메일 발송용 SMTP 인프라가 선행**돼야 한다.
> SMTP 가용 여부 확인 + 방식 결정 후 구현. (참고: [`spec-realignment-auth-b.md`](./spec-realignment-auth-b.md) §4)

---

## 7. 중복 확인

- 이메일: `GET /api/v1/members/check-email?email={}` · Auth ❌ → `data: { "available": true }`
- 닉네임: `GET /api/v1/members/check-nickname?nickname={}` · Auth ❌ → `data: { "available": true }`

---

## 8. 내 프로필 조회

`GET /api/v1/members/me` · Auth ✅ (본인 식별: JWT custom claim `public_id` → `@CurrentUserPublicId`. 인증 미적용 서비스는 `X-User-Public-Id` 헤더 임시처리 — conventions §9·§14)

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 회원 식별자 (UUID) |
| `email` | string | N | 이메일 |
| `nickname` | string | N | 닉네임 |
| `nationality` | string | N | 국적 코드 |
| `is_verified` | boolean | N | 인증 배지 여부 |
| `profile_image_url` | string | Y | 프로필 사진 URL. **현재 이미지 업로드 도메인 미구현 — 항상 null** |
| `created_at` | string | N | 가입 일시 (ISO 8601 UTC Z) |

**Error**: 401 AUTH4011 / 404 MEMBER4001

---

## 9. 프로필 수정 / 사진 변경 / 설정

- 프로필 수정: `PATCH /api/v1/members/me` (닉네임 등 부분 수정) → 200
- 프로필 사진: `PATCH /api/v1/members/me/profile-image` → 200
- 언어 설정 조회/변경: `GET`/`PATCH /api/v1/members/me/language`

### 9-1. 언어 설정 (구현됨)

주 사용 언어는 자유 문자열(BCP 47, 예 `"vi"`, `"ko"`)로 저장한다(지원 언어 화이트리스트/enum 미정의 — 검증은 필수 여부만). 본인 식별은 JWT claim `public_id`.

- **조회** `GET /api/v1/members/me/language` · Auth ✅
  **Response 200** — `data`: `{ "language": "vi" }`
- **변경** `PATCH /api/v1/members/me/language` · Auth ✅
  **Request** `{ "language": "ko" }` (필수, 빈 값 불가)
  **Response 200** — `data`: `{ "language": "ko" }`

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (language 누락/빈 값) |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 404 | MEMBER4001 | 존재하지 않는 회원입니다. |

---

## 10. 신분증 인증 요청

`POST /api/v1/members/me/verification` · Auth ✅

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `identity_document_type` | string | O | `ALIEN_REGISTRATION` / `NATIONAL_ID_KR` / `NATIONAL_ID_US` / `NATIONAL_ID_VN` / `NATIONAL_ID_PH` (이슈 #108 — 여권/일반 NATIONAL_ID 제거, 4개국 분기) |
| `document_number` | string | O | 문서 번호 (서버에서 AES-256-GCM 암호화 저장, `EncryptedStringConverter` 자동 변환) |
| `s3_key` | string | △ | 사전 업로드된 신분증 이미지 S3 key. **현재 선택 — OCR/이미지 업로드 도입 전 임시 정책(이슈 #152).** 미전송 시 null 저장, 향후 OCR 도입 시 필수(O)로 복구. **최대 500자**(컬럼 한도 — 초과 시 COMMON4001) |

**Response 201** — `data`: `status`="APPROVED" (데모 정책 — 형식 검증 통과 시 즉시 승인), `submitted_at`(ISO 8601 UTC Z)
message: 기본 생성 메시지("성공적으로 생성되었습니다." — 코드 `SuccessStatus.CREATED`)

> **데모 즉시 승인**: 현 구현은 실 신원확인 API 없이 유형별 번호 형식(정규식) 검증 통과 시 **즉시 APPROVED + 배지 부여**한다(코드·DB·엔티티 전 계층 일관 — database.md `user_verifications` 주석 참조). 실 KYC/관리자 검토 단계를 도입하면 응답이 `status`="PENDING" + 안내문("신분증 인증 요청이 접수되었습니다. 검토 후 결과를 알려드립니다.")으로 바뀐다. (10D member-verification-2 — 명세를 데모 구현에 정렬)

> 🆕 **사이드이펙트(이슈 #152):** APPROVED 시점에 wallet-service `POST /api/v1/wallets`를 호출해
> 사용자당 1개의 전자지갑이 자동 개설된다(멱등). 이미 지갑이 있으면 그대로 유지. 지갑 생성 호출이
> 실패해도 인증 자체는 성공으로 commit(fail-open) — 호출 실패는 WARN 로깅으로만 남고, 사용자는
> 멱등 API로 추후 재호출/보정 가능. **호출은 인증 트랜잭션 커밋 "후"(tx 밖)에 수행한다** — 외부 HTTP가
> 쓰기 tx·DB 커넥션을 보유하지 않는다(self-proxy tx 분리, community 댓글 작성과 동일 구조).

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 409 | COMMON4091 | 이미 존재하는 리소스입니다. (이미 검토 중/완료) |

---

## 11. 인증 상태 조회

`GET /api/v1/members/me/verification` · Auth ✅

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `identity_document_type` | string | N | 제출 신분증 유형 |
| `status` | string | N | PENDING / APPROVED / REJECTED |
| `reviewed_at` | string | Y | 검토 시각 (미검토 시 null) |
| `created_at` | string | N | 요청 시각 |

**Error**: 401 AUTH4011 / 404 MEMBER4001

---

## 12. 탈퇴

`DELETE /api/v1/members/me` · Auth ✅ → 200 (요청/응답 바디 없음, `data`는 null).

탈퇴는 두 가지를 **IdP-first 순서**로 처리한다(가입 IdP-first와 동일 사상 — 외부 HTTP를 쓰기 트랜잭션 밖에서):
1. **외부 IdP(Authentik) 사용자 비활성화** — 저장된 `auth_provider_id`(= Authentik user uuid)로 사용자를 찾아 `is_active=false`로 PATCH한다(트랜잭션 밖). 이후 IdP 로그인/토큰 발급이 막힌다(하드 삭제 아님 — 복구·감사 보존). 대상이 IdP에 이미 없으면 멱등 통과한다.
2. **로컬 soft delete** — `members.deleted_at`을 현재 시각으로 세팅(row는 보존, 자체 짧은 트랜잭션). 이후 `findByPublicIdAndDeletedAtIsNull` 조회에서 제외된다.

> IdP 비활성화가 실패하면 로컬에 손대기 전에 끝나 로컬은 무변경이다(정합성). 드물게 ① 성공 후 ②가 실패하면 "IdP만 비활성·로컬 활성"이 남지만, 비활성화가 멱등이라 재시도(액세스 토큰 만료 전)로 수습된다 — 완전 원자화(saga)는 v1 범위 밖.
> 탈퇴자 `email`/`nickname`은 여전히 "사용 중"으로 취급되어 동일 값 재가입은 막힌다(`existsBy*`는 `deleted_at`을 필터하지 않음 — 의도된 동작).

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 404 | MEMBER4001 | 존재하지 않는 회원입니다. |
| 500 | COMMON5000 | 서버 내부 오류입니다. (IdP 연동 실패) |

---

## 13. 회원 표시정보 조회 (서비스 간 — display-info / by-email)

> **소비자가 프론트가 아니라 다른 백엔드 서비스다.** community(게시글·댓글 작성자 닉네임/인증배지)와
> wallet(최근 송금 수신자 표시·확인증 본명·validate-member 수신자 검증)의 `MemberClient`가 호출한다.
> 호출 측은 **현재 요청의 JWT를 그대로 릴레이**해 인증한다(별도 service token 없음 — 같은 IdP 발급 토큰을
> member-service가 재검증). MSA 경계라 식별자는 `public_id`(UUID)만 사용한다(conventions §7 — member DB 직접 SELECT 금지).
>
> 응답 필드는 **호출 측 실사용처가 있는 표시 필드만** 담는다(PII 최소화): `email`은 어떤 호출 측도
> 소비하지 않아 노출하지 않고, `name`(본명)은 송금 확인증 표기용으로만 포함한다.

### 13-1. 표시정보 배치 조회

`GET /api/v1/members/display-info?public_ids={a,b,c}` · Auth ✅ (JWT 릴레이)

| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `public_ids` | string | Y | 회원 public_id(UUID) 콤마 구분 목록. **1~100개**(초과 시 COMMON4001) |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `members` | array | N | 요청 id 중 **존재하는 활성(미탈퇴) 회원만** 담는다. 미존재·탈퇴 id는 항목에서 제외(에러 아님) — 호출 측 MemberClient가 "Unknown" 폴백으로 채운다. 순서 비보장 |
| `members[].public_id` | string | N | 회원 식별자 (UUID) |
| `members[].name` | string | N | 이름(본명) — 송금 확인증 등 격식 문서 표기용 |
| `members[].nickname` | string | N | 닉네임 |
| `members[].nationality` | string | N | 국적 코드 (ISO 3166-1 alpha-2) |
| `members[].is_verified` | boolean | N | 신분증 인증 배지 여부 |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (public_ids 누락/빈 값/100개 초과) |
| 401 | AUTH4011 | 인증이 필요합니다. |

### 13-2. 이메일로 표시정보 단건 조회

`GET /api/v1/members/by-email?email={}` · Auth ✅ (JWT 릴레이)

wallet의 앱 사용자 유효성 검증(`validate-member`)이 호출한다. **검증 용도라 폴백 없이 fail-fast** —
미존재·**탈퇴** 회원 모두 404 MEMBER4001(탈퇴자는 송금 수신자가 될 수 없음. 재가입 차단용 중복확인과 달리
`deleted_at` 필터를 건다).

**Response 200** — `data`: §13-1의 `members[]` 항목과 동일 필드(`public_id`/`name`/`nickname`/`nationality`/`is_verified`).

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. (이메일 누락/형식 위반) |
| 401 | AUTH4011 | 인증이 필요합니다. |
| 404 | MEMBER4001 | 존재하지 않는 회원입니다. (탈퇴 포함) |

---

## 참고: 활동 내역 진입 API (타 도메인)

마이페이지에서 진입하지만 명세는 해당 폴더 소관:
- 주머니 거래내역: `GET /api/v1/wallets/me/transactions` → [`../remittance/api-spec.md`](../remittance/api-spec.md)
- 환전 내역: `GET /api/v1/exchanges` → [`../remittance/api-spec.md`](../remittance/api-spec.md)
- 서류 분석 내역: `GET /api/v1/documents` → [`../document-analysis/api-spec.md`](../document-analysis/api-spec.md)
