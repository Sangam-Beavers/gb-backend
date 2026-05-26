# 인증 · 회원 · 마이페이지 — API 명세 (정본)

> 이 문서는 Notion 개별 상세 명세를 옮긴 **정본(正本)** 이다.
> 전역 규칙(응답 래퍼/에러 코드/금액·식별자·시각)은 [`../conventions.md`](../conventions.md)를 따른다.
> 모든 성공 응답은 `{ "success": true, "data": {...}, "message": "..." }` 래퍼로 감싼다. 아래 표의 필드는 `data` 내부 필드다.
>
> ⚠️ **인증 현황:** 표의 `Auth ✅`는 최종 설계상 인증이 필요한 엔드포인트라는 뜻이다. **인증은 현재 미구현**이므로, 본인 식별이 필요한 API(`/members/me*` 등)는 지금 단계에서 JWT 추출 대신 **`@RequestHeader("X-User-Public-Id")` + TODO**로 처리한다. 상세: [`../conventions.md`](../conventions.md) §14.

---

## 엔드포인트 목록

### 인증 (/auth)
| API | Method | Endpoint | Auth |
| --- | --- | --- | --- |
| 이메일 로그인 | POST | `/api/v1/auth/login` | ❌ |
| 회원가입 | POST | `/api/v1/auth/register` | ❌ |
| Google 소셜 로그인 | POST | `/api/v1/auth/login/google` | ❌ |
| 토큰 재발급 | POST | `/api/v1/auth/reissue` | ❌ (refresh_token 자격) |
| 로그아웃 | POST | `/api/v1/auth/logout` | ✅ |
| 가입 인증 이메일 발송 | POST | `/api/v1/auth/email/verify-request` | ❌ |
| 재설정 링크 발송 | POST | `/api/v1/auth/password/reset-request` | ❌ |
| 비밀번호 재설정 | POST | `/api/v1/auth/password/reset` | ❌ |
| 서버 health check | GET | `/health` | ❌ |

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

## 1. 이메일 로그인

`POST /api/v1/auth/login` · Auth ❌

**Request Body**
| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `email` | string | O | 로그인 이메일 |
| `password` | string | O | 비밀번호 |

**Response 200** — `data`
| 필드 | 타입 | nullable | 설명 |
| --- | --- | --- | --- |
| `access_token` | string | N | JWT 액세스 토큰 |
| `refresh_token` | string | N | JWT 리프레시 토큰 |
| `token_type` | string | N | 항상 "Bearer" |
| `expires_in` | integer | N | 액세스 토큰 만료(초) |

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. |
| 401 | AUTH4001 | 이메일 또는 비밀번호가 올바르지 않습니다. |
| 403 | AUTH4004 | 이메일 인증이 완료되지 않은 계정입니다. |
| 429 | COMMON4291 | 요청 횟수를 초과했습니다. 잠시 후 다시 시도해주세요. |

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

**Response 201** — `data`: `{ "email": "user@example.com" }`
message: "회원가입이 완료되었습니다. 이메일 인증을 진행해주세요."

**Error**
| HTTP | code | message |
| --- | --- | --- |
| 400 | COMMON4001 | 요청 값이 올바르지 않습니다. |
| 409 | MEMBER4002 | 이미 사용 중인 이메일입니다. |
| 409 | MEMBER4003 | 이미 사용 중인 닉네임입니다. |
| 422 | COMMON4221 | 처리할 수 없는 요청입니다. |

---

## 3. Google 소셜 로그인

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

## 4. 토큰 재발급

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

## 5. 로그아웃

`POST /api/v1/auth/logout` · Auth ✅

해당 토큰을 Redis 블랙리스트(`blacklist:{token}`, TTL=남은 만료)에 등록. Response 200.

---

## 6. 비밀번호 찾기/재설정

- 재설정 링크 발송: `POST /api/v1/auth/password/reset-request` (Body: `email`) → 이메일 발송. 200.
- 비밀번호 재설정: `POST /api/v1/auth/password/reset` (Body: `token`, `new_password`) → 200.

> 작업표 CSV에는 `reset-email`도 등장한다. 정본 경로는 `reset-request`(링크 발송) + `reset`(실제 재설정). 구현 시 둘 중 하나로 통일.

---

## 7. 가입 인증 이메일 발송

`POST /api/v1/auth/email/verify-request` · Auth ❌ (Body: `email`) → 인증 메일 발송. 200.

---

## 8. 중복 확인

- 이메일: `GET /api/v1/members/check-email?email={}` · Auth ❌ → `data: { "available": true }`
- 닉네임: `GET /api/v1/members/check-nickname?nickname={}` · Auth ❌ → `data: { "available": true }`

---

## 9. 내 프로필 조회

`GET /api/v1/members/me` · Auth ✅ (본인 식별: 최종은 JWT sub, 현재는 `X-User-Public-Id` 헤더 — conventions §14)

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
