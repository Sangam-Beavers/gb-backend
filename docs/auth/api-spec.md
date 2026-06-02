# 인증 · 회원 · 마이페이지 — API 명세 (정본)

> 이 문서는 Notion 개별 상세 명세를 옮긴 **정본(正本)** 이다.
> 전역 규칙(응답 래퍼/에러 코드/금액·식별자·시각)은 [`../conventions.md`](../conventions.md)를 따른다.
> 모든 성공 응답은 `{ "success": true, "data": {...}, "message": "..." }` 래퍼로 감싼다. 아래 표의 필드는 `data` 내부 필드다.
>
> ⚠️ **인증 방식 = 방식 B (외부 IdP 검증 전용 + Authorization Code flow).** 토큰 발급·비밀번호 보관은 외부 IdP(개발=Authentik, 운영=Cognito)가 담당하고, 백엔드는 들어온 JWT를 **검증만** 한다(OAuth2 Resource Server). 로그인은 프론트(앱)가 IdP와 직접 수행하며, 백엔드에는 로그인/토큰발급 엔드포인트가 없다.
>
> 본인 식별은 JWT custom claim **`public_id`**(UUID)에서 추출한다 — 컨트롤러에서 `@CurrentUserPublicId String userPublicId`로 주입받는다(`X-User-Public-Id` 헤더 임시처리는 인증 적용 완료된 서비스에서 대체됨). claim 누락/토큰 무효는 `AUTH4011`로 fail-fast. 회원가입 시 우리 `public_id`를 IdP 사용자 attribute로 저장해 토큰 claim으로 노출한다(토큰 sub ↔ publicId 매핑). 상세: [`../conventions.md`](../conventions.md) §9·§14, [`login-authorization-code.md`](./login-authorization-code.md), [`spec-realignment-auth-b.md`](./spec-realignment-auth-b.md).
>
> 🔧 **방식 B 정합화 진행 중:** 아래 명세 중 일부는 방식 A(백엔드 자체 JWT 발급) 기준 잔재가 남아 있다. **확정된 변경**(로그인·토큰재발급 폐기, 회원가입 IdP 프로비저닝)은 반영했고, **팀 논의가 필요한 항목**(Google 로그인·로그아웃·비번 재설정·이메일 인증)은 "⚠️ 재정의 필요"로 표시만 했다. 결정 후 확정 반영한다.

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
| 가입 인증 이메일 발송 | POST | `/api/v1/auth/email/verify-request` | ❌ | ⚠️ SMTP(메일 발송) 인프라 선행 필요 |
| 재설정 링크 발송 | POST | `/api/v1/auth/password/reset-request` | ❌ | ⚠️ SMTP 선행 + 비번은 IdP 보관 → IdP 경유 재설정 |
| 비밀번호 재설정 | POST | `/api/v1/auth/password/reset` | ❌ | ⚠️ SMTP 선행 + IdP set_password 경유 |
| 서버 health check | GET | `/health` | ❌ | |
| 이메일/닉네임 중복 확인 | GET | `/api/v1/members/check-*` | ❌ | ✅ 구현 완료 |

### 회원 (/members)
| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 이메일 중복 확인 | GET | `/api/v1/members/check-email?email={}` | ❌ |
| 닉네임 중복 확인 | GET | `/api/v1/members/check-nickname?nickname={}` | ❌ |
| Google 가입 후 추가 정보 | POST/PATCH | `/api/v1/members/profile` (소셜 프로필 보완) | ✅ |
| 내 프로필 조회 | GET | `/api/v1/members/me` | ✅ |
| 프로필 수정 | PATCH | `/api/v1/members/me` | ✅ |
| 프로필 사진 변경 | PATCH | `/api/v1/members/me/profile-image` | ✅ |
| 인증 상태 조회 | GET | `/api/v1/members/me/verification` | ✅ |
| 신분증 인증 요청 | POST | `/api/v1/members/me/verification` | ✅ |
| 알림 설정 조회 | GET | `/api/v1/members/me/notification-settings` | ✅ |
| 알림 설정 저장 | PATCH | `/api/v1/members/me/notification-settings` | ✅ |
| 언어 설정 조회 | GET | `/api/v1/members/me/language` | ✅ |
| 언어 설정 변경 | PATCH | `/api/v1/members/me/language` | ✅ |
| 탈퇴 | DELETE | `/api/v1/members/me` | ✅ |

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
| `terms_agreed` | boolean | O | 이용약관 동의 |
| `privacy_agreed` | boolean | O | 개인정보 처리방침 동의 |

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
- 비밀번호 재설정: `POST /api/v1/auth/password/reset` (Body: `token`, `new_password`) → 200.

> 방식 B에서 비밀번호는 IdP가 보관하므로 재설정도 IdP를 경유한다(Authentik recovery flow 위임 또는
> 백엔드가 IdP `set_password` 호출). 어느 쪽이든 **재설정 메일 발송용 SMTP 인프라가 선행**돼야 한다.
> SMTP 가용 여부 확인 + 방식 결정 후 구현. (참고: [`spec-realignment-auth-b.md`](./spec-realignment-auth-b.md) §4)

---

## 7. 가입 인증 이메일 발송 — ⚠️ SMTP 선행 필요

`POST /api/v1/auth/email/verify-request` · Auth ❌ (Body: `email`) → 인증 메일 발송. 200.

> 메일 발송(SMTP) 인프라가 선행돼야 구현 가능. 개발기 SMTP 가용 여부 확인 필요.

---

## 8. 중복 확인

- 이메일: `GET /api/v1/members/check-email?email={}` · Auth ❌ → `data: { "available": true }`
- 닉네임: `GET /api/v1/members/check-nickname?nickname={}` · Auth ❌ → `data: { "available": true }`

---

## 9. 내 프로필 조회

`GET /api/v1/members/me` · Auth ✅ (본인 식별: JWT custom claim `public_id` → `@CurrentUserPublicId`. 인증 미적용 서비스는 `X-User-Public-Id` 헤더 임시처리 — conventions §9·§14)

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `public_id` | string | N | 회원 식별자 (UUID) |
| `email` | string | N | 이메일 |
| `nickname` | string | N | 닉네임 |
| `nationality` | string | N | 국적 코드 |
| `is_verified` | boolean | N | 인증 배지 여부 |
| `temperature_grade` | string | N | RED/YELLOW/GREEN/PURPLE/BLUE |
| `profile_image_url` | string | Y | 프로필 사진 URL (미설정 시 null) |
| `created_at` | string | N | 가입 일시 (ISO 8601 UTC Z) |

**Error**: 401 COMMON4011 / 404 MEMBER4001

---

## 10. 프로필 수정 / 사진 변경 / 설정

- 프로필 수정: `PATCH /api/v1/members/me` (닉네임 등 부분 수정) → 200
- 프로필 사진: `PATCH /api/v1/members/me/profile-image` → 200
- 알림 설정 조회/저장: `GET`/`PATCH /api/v1/members/me/notification-settings`
- 언어 설정 조회/변경: `GET`/`PATCH /api/v1/members/me/language`

---

## 11. 신분증 인증 요청

`POST /api/v1/members/me/verification` · Auth ✅

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `identity_document_type` | string | O | `ALIEN_REGISTRATION` / `PASSPORT` / `NATIONAL_ID` |
| `document_number` | string | O | 문서 번호 (서버에서 AES-256 암호화 저장) |
| `s3_key` | string | O | 사전 업로드된 신분증 이미지 S3 key |

**Response 201** — `data`: `status`="PENDING", `submitted_at`(ISO 8601 UTC Z)
message: "신분증 인증 요청이 접수되었습니다. 검토 후 결과를 알려드립니다."

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. |
| 401 | COMMON4011 | 인증 정보가 유효하지 않습니다. |
| 409 | COMMON4091 | 이미 존재하는 리소스입니다. (이미 검토 중/완료) |

---

## 12. 인증 상태 조회

`GET /api/v1/members/me/verification` · Auth ✅

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `identity_document_type` | string | N | 제출 신분증 유형 |
| `status` | string | N | PENDING / APPROVED / REJECTED |
| `reviewed_at` | string | Y | 검토 시각 (미검토 시 null) |
| `created_at` | string | N | 요청 시각 |

**Error**: 401 COMMON4011 / 404 MEMBER4001

---

## 13. 탈퇴

`DELETE /api/v1/members/me` · Auth ✅ → soft delete(`users.deleted_at` SET). 200.

---

## 참고: 활동 내역 진입 API (타 도메인)

마이페이지에서 진입하지만 명세는 해당 폴더 소관:
- 주머니 거래내역: `GET /api/v1/wallets/me/transactions` → [`../remittance/api-spec.md`](../remittance/api-spec.md)
- 환전 내역: `GET /api/v1/exchanges` → [`../remittance/api-spec.md`](../remittance/api-spec.md)
- 서류 분석 내역: `GET /api/v1/documents` → [`../document-analysis/api-spec.md`](../document-analysis/api-spec.md)
