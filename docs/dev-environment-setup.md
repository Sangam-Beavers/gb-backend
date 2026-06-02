# 개발 환경 셋업 가이드 (gb-backend)

> 새로 합류했거나 다른 PC에서 처음 받았을 때, **각자 로컬에서 해야 하는 설정**을 모았다.
> 코드(git)에는 없고 개발자별로 직접 세팅해야 하는 것들이라, 안 하면 서비스가 안 뜨거나 기능이 막힌다.
>
> ⚠️ 아래 비밀값(DB 비번, Authentik 토큰/시크릿, Redis 비번 등)은 **git·채팅에 절대 올리지 말 것.**
> 값은 팀 보안 채널(Vault 등)에서 받는다. 이 문서엔 "무엇을 설정해야 하는지"만 적는다.
>
> 전제: 사내망(`*.fisa`)에 접근 가능해야 한다(VPN/사내망). DB·Redis·Authentik이 모두 사내망에 있다.

---

## 0. 한눈에 (체크리스트)

처음 세팅이면 위에서부터 순서대로:

- [ ] 1. JDK 17 + 사내망 연결 확인
- [ ] 2. Authentik 인증서를 JVM cacerts에 등록 (안 하면 인증서 에러)
- [ ] 3. `application-dev.yml` 파일을 각 서비스에 배치 (git-ignored)
- [ ] 4. 환경변수 등록 (DB 비번, Redis 비번, Authentik 토큰/시크릿 등)
- [ ] 5. 개발기 DB 보정 — `members.password` 컬럼 드롭
- [ ] 6. (실행에 필요한 외부) Redis / MySQL / Authentik 접근 확인
- [ ] 7. 빌드·기동 확인

---

## 1. JDK / 사내망

- **JDK 17** (프로젝트 기준). `JAVA_HOME`이 JDK 17을 가리키는지 확인.
- DB(`10.10.1.193`), Redis(`10.10.1.194`), Authentik(`sso.sb.fisa`)이 **사내망**에 있으므로 VPN/사내망 연결이 필요하다. (연결 안 되면 기동 시 DB·Authentik 연결 실패)

---

## 2. Authentik 인증서 등록 (JVM cacerts) ⭐ 잘 빠뜨림

Authentik(`sso.sb.fisa`)은 **사설 인증서**를 쓴다. JVM이 이 인증서를 신뢰하도록 등록하지 않으면,
백엔드가 Authentik에 접속할 때(회원가입 프로비저닝, 토큰 검증 등) **인증서 에러**(`SEC_E_UNTRUSTED_ROOT`,
`PKIX path building failed` 등)가 난다. 이건 코드가 아니라 **각 PC의 JVM 설정**이라 git으로 공유되지 않는다.

**먼저 등록돼 있는지 확인:**
```bash
# Windows (PowerShell)
keytool -list -cacerts -storepass changeit | findstr /i "fisa sso"
# macOS / Linux
keytool -list -cacerts -storepass changeit | grep -i "fisa\|sso"
```
→ `sso-sb-fisa ... trustedCertEntry`가 보이면 이미 등록된 것(이 단계 건너뜀).

**등록 (macOS / Linux):**
```bash
# 1) Authentik 인증서 받기
echo | openssl s_client -connect sso.sb.fisa:443 -servername sso.sb.fisa 2>/dev/null \
  | openssl x509 > /tmp/sb-local-ca.crt

# 2) JVM 신뢰 저장소(cacerts)에 등록 (sudo 비번 본인 입력)
sudo keytool -importcert -alias sso-sb-fisa -file /tmp/sb-local-ca.crt \
  -cacerts -storepass changeit -noprompt
```

**등록 (Windows / PowerShell):** — PowerShell만으로 인증서 받기 + 등록 (openssl 불필요)

```powershell
# 1) Authentik 인증서를 PowerShell로 직접 받아 C:\sso-sb-fisa.cer 로 저장
$cert = $null
$tcp = New-Object System.Net.Sockets.TcpClient("sso.sb.fisa", 443)
$ssl = New-Object System.Net.Security.SslStream($tcp.GetStream(), $false, ({ $true }))
$ssl.AuthenticateAsClient("sso.sb.fisa")
$cert = $ssl.RemoteCertificate
[System.IO.File]::WriteAllBytes("C:\sso-sb-fisa.cer", $cert.Export("Cert"))
$ssl.Close(); $tcp.Close()
Write-Host "저장됨: C:\sso-sb-fisa.cer"

# 2) JVM cacerts에 등록 (★ PowerShell을 '관리자 권한'으로 실행해야 함)
keytool -importcert -alias sso-sb-fisa -file C:\sso-sb-fisa.cer -cacerts -storepass changeit -noprompt
```

> **관리자 권한 PowerShell 여는 법:** 시작 메뉴 → "PowerShell" 우클릭 → "관리자 권한으로 실행".
> (일반 권한으로 2)를 실행하면 cacerts 파일 쓰기 거부로 `Access denied`/`keytool error`가 난다.)

**브라우저로 받는 대안** (위 1번이 안 되면):
1. 크롬에서 `https://sso.sb.fisa` 접속 → 주소창 자물쇠/「주의 요함」 클릭 → 인증서 보기
2. "세부 정보" 탭 → "파일에 복사"(내보내기) → DER 형식(.cer)으로 `C:\sso-sb-fisa.cer` 저장
3. 위 2)번 keytool 명령 실행 (관리자 PowerShell)

> 주의: cacerts 기본 비번은 `changeit`. 시스템 JDK 저장소를 바꾸는 작업이라 관리자 권한 필요.
> 등록 후 `keytool -list -cacerts -storepass changeit | findstr sso` 로 `sso-sb-fisa` 보이면 성공.

> ⚠️ `keytool`을 못 찾는다고 나오면(`'keytool'은(는) ... 인식되지 않습니다`) JDK가 PATH에 없는 것 →
> JDK 17 bin 경로를 쓰거나(`& "C:\경로\jdk-17\bin\keytool.exe" ...`), `JAVA_HOME` 설정 후 새 터미널에서 재시도.

---

## 3. `application-dev.yml` 배치 (git-ignored)

`application-{dev,stage,prod}.yml`은 접속 정보를 담아 **git에서 제외**된다(커밋 안 됨).
따라서 각 서비스에 이 파일이 **로컬에 있어야** dev 프로파일로 뜬다. 팀에서 파일을 받아 아래 위치에 둔다:

```
services/member-service/src/main/resources/application-dev.yml
services/wallet-service/src/main/resources/application-dev.yml
services/community-service/src/main/resources/application-dev.yml
services/document-service/src/main/resources/application-dev.yml
```

각 yml은 DB/Redis 접속 + (인증 적용 서비스는) Authentik 설정을 담는다. 비밀값은 직접 적지 않고
환경변수 placeholder(`${...}`)로 두며, 실제 값은 4번 환경변수로 주입한다.

> ⚠️ **인증(JWT 검증)이 켜진 서비스는 yml에 `issuer-uri`가 반드시 있어야 한다.** 없으면 기동 시
> `JwtDecoder ... could not be found`로 앱이 안 뜬다(아래 에러표). 현재 **member·wallet·community**가
> 인증 적용 상태이므로 해당 서비스 yml에 아래가 있어야 한다:
> ```yaml
> spring:
>   security:
>     oauth2:
>       resourceserver:
>         jwt:
>           issuer-uri: https://sso.sb.fisa/application/o/gb-backend/
> ```
> (issuer-uri는 비밀값 아님. `.fisa` 접속 + 인증서 등록(2번)이 돼 있어야 부팅 시 JWKS를 받아온다.)

> 실행 시 dev 프로파일 활성화: 실행 옵션에 `-Dspring.profiles.active=dev`
> (IntelliJ Run Configuration에 보통 이미 설정돼 있음)

---

## 4. 환경변수 등록 ⭐ 안 하면 기동 실패

yml의 `${XXX}` 들은 환경변수에서 값을 읽는다. 없으면 `Could not resolve placeholder 'XXX'`로
**앱이 안 뜬다.** OS 시스템 환경변수 또는 IntelliJ Run Configuration의 "환경 변수"에 등록한다.
값은 팀 보안 채널에서 받는다(여기 적지 않음).

### 서비스별 필요한 환경변수

| 서비스 | 환경변수 | 용도 |
| --- | --- | --- |
| **공통(Redis 쓰는 서비스)** | `REDIS_PASSWORD` | Redis 접속 비번 |
| member-service | `MEMBER_DB_PASSWORD` | member_db 접속 비번 |
| member-service | `AUTH_ADMIN_TOKEN` | Authentik 관리 API 토큰 (회원가입 시 사용자 프로비저닝) |
| member-service | `AUTH_CLIENT_SECRET` | OAuth2 client secret (ROPC 잔재 — 코드 미사용이나 yml에 남아 있으면 placeholder 해석 위해 필요할 수 있음. 비우면 안 뜰 경우 더미값/제거) |
| wallet-service | `WALLET_DB_PASSWORD` | wallet_db 접속 비번 |
| wallet-service | `BANK_API_BASE_URL` | Mock 은행 base URL (기본값 있음: `http://localhost:9000`. 충전·현금화 테스트 시 실제 주소 필요) |
| community-service | `COMMUNITY_DB_PASSWORD` | community_db 접속 비번 |
| document-service | `DOCUMENT_DB_PASSWORD` | document_db 접속 비번 |

> 참고:
> - `REDIS_PASSWORD`는 Redis 쓰는 모든 서비스에 공통 필요.
> - wallet의 `REDIS_HOST`/`REDIS_PORT`/`BANK_API_BASE_URL`은 기본값이 있어 미설정 시 기본값으로 뜬다(`REDIS_PASSWORD`는 기본값 없음 → 필수).
> - `AUTH_ADMIN_TOKEN`은 슈퍼관리자(akadmin) 대신 **전용 서비스 계정 토큰** 사용 권장(Authentik → Directory → Tokens). 사용자 생성/비번설정 권한 필요.
> - Authentik 비민감 값(issuer-uri, client-id, api-base-uri)은 yml에 그대로 있음(노출 OK).

---

## 5. 개발기 DB 보정 — `members.password` 컬럼 드롭 ⭐ 안 하면 회원가입 500

방식 B에서 비밀번호는 우리 DB에 저장하지 않는다(IdP가 보관). 과거 스키마에 남아 있던
`members.password`(NOT NULL, 기본값 없음) 때문에 회원가입 시 `Field 'password' doesn't have a default value`
500 에러가 난다. `ddl-auto: update`는 **기존 컬럼을 지우지 않으므로** 각자 1회 수동 드롭한다.

```sql
-- 각자 개발기 member_db에서 1회 실행 (이미 없으면 무시)
ALTER TABLE members DROP COLUMN password;
```

---

## 6. 외부 의존 접근 확인

기동 전에 아래가 사내망에서 닿는지 확인(안 닿으면 기동/기능 실패):

| 대상 | 주소 | 용도 |
| --- | --- | --- |
| MySQL | `10.10.1.193:3306` | 각 서비스 DB (member_db / wallet_db / community_db / document_db) |
| Redis | `10.10.1.194:6379` | 분산 락 · 멱등성 캐시 · 환전 견적 저장 (비번 필요) |
| Authentik | `https://sso.sb.fisa` | 회원가입 프로비저닝 · 토큰 검증 (인증서 등록 필요 — 2번) |

---

## 7. 빌드 · 기동 확인

```bash
# 컴파일
./gradlew :services:member-service:compileJava

# 테스트 (H2 인메모리 — 외부 DB 불필요. useJUnitPlatform 필수, 루트 build.gradle에 설정됨)
./gradlew :services:member-service:test

# 기동 (IntelliJ에서 각 Application 실행, dev 프로파일)
# 콘솔에 "Started XxxServiceApplication ... port" 뜨고 안 멈추면 성공
```

**기동 성공 후 빠른 점검:**
- Swagger: `http://localhost:{port}/swagger-ui/index.html` (member 8081 등 — 콘솔에서 포트 확인)
- 회원가입(`POST /api/v1/auth/register`) 호출 → 201 + Authentik Users에 사용자 생성되면 인증서·토큰·DB 다 정상.

---

## 자주 막히는 에러 → 원인 빠른 표

| 에러 | 원인 | 해결 |
| --- | --- | --- |
| `Could not resolve placeholder 'XXX'` | 환경변수 XXX 누락 | 4번 — 환경변수 등록 후 재시작 |
| `JwtDecoder ... could not be found` (APPLICATION FAILED TO START) | 인증 켜진 서비스인데 yml에 `issuer-uri` 없음 | 3번 — yml에 issuer-uri 추가 |
| `SEC_E_UNTRUSTED_ROOT` / `PKIX path building failed` | Authentik 인증서 미등록 | 2번 — cacerts에 등록 |
| `'keytool'은(는) 인식되지 않습니다` | JDK가 PATH에 없음 | JDK17 bin 경로로 실행 or JAVA_HOME 설정 |
| 회원가입 500 `Field 'password' doesn't have a default value` | members.password 컬럼 잔존 | 5번 — 컬럼 드롭 |
| 회원가입 500 / `프로비저닝 실패` | AUTH_ADMIN_TOKEN 누락·권한부족·만료 | 4번 토큰 확인 + Authentik 권한/만료 확인 |
| Redis 연결 실패 | REDIS_PASSWORD 누락 / 사내망 미연결 | 4번 + VPN |
| DB 연결 실패 | DB 비번 환경변수 누락 / 사내망 미연결 | 4번 + VPN |
| 기동 시 application-dev.yml 못 찾음 / 기본 프로파일로 뜸 | dev.yml 미배치 또는 프로파일 미지정 | 3번 + `-Dspring.profiles.active=dev` |
```
