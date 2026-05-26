# Common 모듈 연동 가이드

각 서비스(`wallet` / `member` / `community` / `document`)가 공통 모듈
(`common-response`, `common-exception`)을 사용하기 위한 **필수 연동 규칙**입니다.
4개 서비스 모두 동일하게 적용하세요.

> 의존 방향은 단방향입니다: **서비스 → common-exception → common-response**.
> common 모듈은 어떤 서비스에도 의존하지 않습니다.

---

## 1. 두 common 모듈 의존 추가

`services/<서비스>/build.gradle`:

```groovy
dependencies {
    implementation project(':common:common-response')
    implementation project(':common:common-exception')
    // ... 서비스별 의존성
}
```

- `common-response`: `ApiResponse`(성공), `ErrorResponse`(실패), `SuccessStatus`
- `common-exception`: `ErrorCode`, `CommonErrorCode`, `BusinessException`, `GlobalExceptionHandler`
- `common-exception`이 `common-response`를 이미 의존하므로 둘 다 명시해 두면 컴파일/런타임 모두 안전합니다.

## 2. 메인 클래스 `scanBasePackages = "com.gb"`

`GlobalExceptionHandler`는 `com.gb.common.exception.handler` 패키지에 있어
서비스 기본 스캔 범위(`com.gb.<서비스>`) **밖**입니다. 스캔 범위를 `com.gb`로 넓혀야
전역 예외 처리기가 빈으로 등록됩니다.

```java
@SpringBootApplication(scanBasePackages = "com.gb")
public class WalletServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}
```

> 넓히지 않으면 `BusinessException`을 던져도 핸들러가 동작하지 않아 기본 Spring 에러 응답이 나갑니다.

## 3. 서비스별 ErrorCode는 common `ErrorCode` 인터페이스 구현

각 서비스는 자신의 도메인 에러 코드 enum을 만들고 `com.gb.common.exception.ErrorCode`를 구현합니다.
코드 형식은 `{DOMAIN}{4자리숫자}`, 서버 오류는 도메인 코드 신설 없이 `COMMON5000`을 사용합니다.

> **중요:** 에러 코드(상수명·code·HTTP·message)는 반드시 **API 명세 §12-4의 도메인 표를 SSOT로** 등록합니다.
> 임의로 번호를 추측하지 말고, 명세 표에 이미 있으면 그대로 옮기고, 없으면 명세 표에 먼저 등록한 뒤 코드에 반영하세요.

```java
package com.gb.wallet.global.exception.code;

import com.gb.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WalletErrorCode implements ErrorCode {

    // 명세 §12-4 WALLET 도메인 표 기준
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "WALLET4001", "존재하지 않는 지갑입니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "WALLET4002", "지갑 잔액이 부족합니다.");

    private final HttpStatus httpStatus; // @Getter가 getHttpStatus/getCode/getMessage 생성 → 인터페이스 충족
    private final String code;
    private final String message;
}
```

사용:

```java
throw new BusinessException(WalletErrorCode.WALLET_NOT_FOUND);
```

---

## 응답 포맷 요약

### 성공 — `ApiResponse`
- `code` 필드 없음. `data`는 `null`이어도 `"data": null`로 노출됩니다.

```json
{ "success": true, "data": { "balance": "50000" }, "message": "요청이 성공적으로 처리되었습니다." }
```
```json
{ "success": true, "data": null, "message": "요청이 성공적으로 처리되었습니다." }
```

> 금액·잔액·환율은 명세 §0에 따라 **문자열(string) 십진수**로 전송합니다. 위 `balance`가 `"50000"`인 이유입니다.

### 실패 — `ErrorResponse`
- `data` 필드 없음. `GlobalExceptionHandler`가 자동 생성합니다.

```json
{ "success": false, "code": "WALLET4002", "message": "지갑 잔액이 부족합니다." }
```

### 공통 에러 코드 (`CommonErrorCode`)
| 코드 | HTTP | 의미 |
|---|---|---|
| COMMON4001 | 400 | 요청 값이 올바르지 않습니다. (`@Valid` 실패 기본) |
| COMMON4002 | 400 | 필수 입력 항목이 누락되었습니다. |
| COMMON4011 | 401 | 인증 정보가 유효하지 않습니다. |
| COMMON4031 | 403 | 접근 권한이 없습니다. |
| COMMON4041 | 404 | 존재하지 않는 리소스입니다. |
| COMMON4091 | 409 | 이미 존재하는 리소스입니다. |
| COMMON4221 | 422 | 처리할 수 없는 요청입니다. |
| COMMON4291 | 429 | 요청 횟수를 초과했습니다. |
| COMMON5000 | 500 | 서버 오류가 발생했습니다. (미처리 예외 전부) |
| COMMON5031 | 503 | 일시적으로 처리할 수 없습니다. |

> 도메인 인증 실패(JWT 누락/무효)는 도메인 코드를 새로 만들지 말고 `COMMON4011`을, 권한 없음은 `COMMON4031`을 재사용합니다. (명세 §12-2)

### 도메인 에러 코드 (참고 — 명세 §12-4 SSOT)
각 서비스가 구현할 도메인 코드는 명세 §12-4를 기준으로 합니다. 아래는 일부 예시이며, 전체·최신본은 항상 API 명세를 확인하세요.

| 도메인 | 코드 | HTTP | message |
|---|---|---|---|
| WALLET | WALLET4001 | 404 | 존재하지 않는 지갑입니다. |
| WALLET | WALLET4002 | 400 | 지갑 잔액이 부족합니다. |
| TRANSFER | TRANSFER4001 | 404 | 존재하지 않는 송금 내역입니다. |
| TRANSFER | TRANSFER4002 | 400 | 지원하지 않는 통화입니다. |

> 신규 에러는 명세 §12-3·§12-4 매핑표를 먼저 검색해 재사용하고, 없으면 해당 도메인 표 맨 아래 번호를 이어서 등록합니다. 번호는 한번 부여하면 재사용·재배치 금지(클라이언트 호환).