# document-service AWS 자원 정보 회신 (인프라 핸드오프)

> 대상: 인프라팀 (EKS stage/prod 배포 + IRSA·ParameterStore/Secrets 기입용)
> 기준: gb-backend(document-service) · gb-document-lambda(Lambda A/B) · frontend 코드 실제 구현
> 리전: 전 자원 `ap-northeast-2`

---

## ⚠️ 먼저 — 설정 주입 방식 (중요)

- **`application.yaml` 무수정 + env 주입으로 거의 다 됨.** `gb.analysis.*`·`chatbot.*`·`cloud.aws.region`은 모두 `${ENV:기본값}`으로 외부화됨. DataSource는 base yaml에 없지만 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`(릴랙스 바인딩) env로 주입 가능.
- **단 하나 예외 — JWT issuer-uri**: base yaml에 `spring.security.oauth2...` 블록이 없음. 팀 변수명 `AUTH_ISSUER_URI`는 yaml 매핑이 있어야만 동작하는데 그 매핑이 base에 없음(dev/prod yml은 gitignore). 둘 중 하나 필요:
  - (a) 인프라가 풀네임 env `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` 주입, 또는
  - (b) 백엔드가 base yaml에 `issuer-uri: ${AUTH_ISSUER_URI}` 추가(권장).
- **`application-dev.yml`은 EKS 불필요**(로컬 dev 전용). EKS에서는 **`dev` 프로파일 금지** — Mock 퍼블리셔·온프렘 DB가 뜸. `SPRING_PROFILES_ACTIVE=stage|prod` 사용.
- **`application-prod.yml`은 배포 설정 아님**: gitignore(이미지에 없음) + `consumer-enabled: false` 하드코딩(env로 못 덮음) + 온프렘 DB. → 운영용 env-driven yml을 별도 정리하거나 env로만 구성.

---

## A. 업로드 S3 버킷 (gb-document-bucket)

- 버킷 이름: **gb-document-bucket** (업로드는 `original/` 접두사 아래, 같은 버킷에 `masked/`도 사용)
- 소유 계정: **(o) 계정 B**
- 누가 생성: **(o) document 담당이 이미 생성함**
  - 버킷 ARN `arn:aws:s3:::gb-document-bucket`
  - 객체 ARN `arn:aws:s3:::gb-document-bucket/original/*` (및 `/masked/*`)
- presigned PUT 프론트 오리진 (CORS 허용): **dev `http://localhost:5173`, prod `https://global-bridge.me`**
  - 허용 메서드: `PUT` (+프리플라이트 `OPTIONS`)
  - 허용 헤더: `Content-Type`, `x-amz-meta-source`, `x-amz-meta-document_id`, `x-amz-meta-analysis_document_type` (+prod: `x-amz-meta-result_queue_arn`)
- 업로드 객체 수명주기: **`original/` 접두사 7일**
- 암호화: **(o) SSE-S3(AES256)** + 버킷 "Bucket owner enforced"(ACL 비활성) 권장 — 계정 A가 올린 객체를 계정 B Lambda A가 읽고 삭제하려면 소유권이 버킷 소유자(계정 B)로 넘어가야 함.

## B. SQS 큐 (분석 파이프라인)

- 결과 큐 `GB_ANALYSIS_RESULT_QUEUE_ARN`: `arn:aws:sqs:ap-northeast-2:241706222734:gb-analysis-results` / **계정 A**
- consumer 큐 이름 `GB_ANALYSIS_CONSUMER_QUEUE_NAME`: `gb-analysis-results`
- 누가 생성/소유: **(o) 인프라가 생성(계정 A)** — 큐 2개 + 각 DLQ 1개

## C. 챗봇 Lambda (CHATBOT_FUNCTION_URL)

- Function URL: `https://et2vrpzj7hruxjp6yn2zu4fkem0lowsz.lambda-url.ap-northeast-2.on.aws/`
- 계정: **계정 B**
- 인증 방식: **(o) IAM(SigV4)** — 운영 `CHATBOT_AUTH_ENABLED=true`, signingName=`lambda`
- 호출 대상 함수 ARN: `arn:aws:lambda:ap-northeast-2:713729793436:function:gb-chatbot` ← **계정 B 회신 요청**
- URL 비밀 취급: **(o) 아니오 → Parameter Store** (IAM 인가라 URL 자체는 비밀 아님.)

## D. 파드 IAM 권한 (document-service IRSA, 계정 A)

```
S3:
  s3:PutObject  on arn:aws:s3:::gb-document-bucket/original/*     (presigned PUT 발급)
  s3:GetObject  on arn:aws:s3:::gb-document-bucket/*              (masked/ presigned GET + retry HeadObject)

SQS (결과큐, 계정 A):
  sqs:ReceiveMessage, sqs:DeleteMessage(+Batch), sqs:GetQueueUrl,
  sqs:GetQueueAttributes, sqs:ChangeMessageVisibility(+Batch)
    on arn:aws:sqs:ap-northeast-2:<계정A>:gb-analysis-results-{stage|prod}
  (요청큐 구현 시: sqs:SendMessage on <요청큐>)

Lambda (챗봇, 계정 B):
  lambda:InvokeFunctionUrl on arn:aws:lambda:ap-northeast-2:<계정B>:function:<챗봇함수>

```

> 도출 근거: 파드가 자기 IRSA 자격증명으로 호출하는 SDK 메서드 = IAM 액션. 호출처는 `AwsClientConfig`(S3Presigner/S3Client/SqsClient) + `spring-cloud-aws-starter-sqs`(SqsAsyncClient 폴링) + `ChatbotLambdaClientImpl`(Aws4Signer) 세 군데가 전부.

## E. 계정 경계 / 크로스계정

- 방식: **(o) 자원 정책으로 계정 A 허용** ← 코드가 IRSA 자격증명으로 직접 호출(STS AssumeRole 안 함)
  - 계정 B 버킷 정책: 계정 A document-service 역할에 `s3:PutObject/GetObject`(`original/*`, `masked/*`) 허용
  - 계정 B 챗봇 Lambda resource policy(FunctionUrl `AuthType=AWS_IAM`): 계정 A 역할 principal 허용

## F. 비밀 (Secrets Manager)

- `DOCUMENT_DB_PASSWORD`: DB 비밀번호 (인프라 ESO/Secrets 경로)

## G. 리전 확인

- 모든 자원 리전: **(o) ap-northeast-2**

---

## ➕ 파드 환경변수 (인프라 주입)

| 변수 | stage/prod 값 | 비고 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `stage` / `prod` | **`dev` 금지** |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Aurora | base yaml에 없음 → 필수 주입 |
| `AUTH_ISSUER_URI` 또는 `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Cognito issuer | ⚠️ base yaml 매핑 없음(위 ⚠️ 참고) |
| `GB_ANALYSIS_SOURCE` | `production` | |
| `GB_ANALYSIS_UPLOAD_BUCKET` | `gb-document-bucket` | 기본값 있음 |
| `GB_ANALYSIS_RESULT_QUEUE_ARN` | `arn:aws:sqs:ap-northeast-2:<계정A>:gb-analysis-results-{stage\|prod}` | |
| `GB_ANALYSIS_CONSUMER_ENABLED` | `true` | Consumer + `spring.cloud.aws.sqs.enabled` 동시 제어 |
| `GB_ANALYSIS_CONSUMER_QUEUE_NAME` | `gb-analysis-results-{stage\|prod}` | URL 아님, 이름 |
| `GB_ANALYSIS_UPLOAD_URL_EXPIRES_SECONDS` | `600` | 기본값 |
| `GB_ANALYSIS_AWS_REGION` | `ap-northeast-2` | 기본값 |
| `GB_ANALYSIS_STALE_TIMEOUT_MINUTES` / `_STALE_SWEEP_CRON` | `30` / `0 */10 * * * *` | 기본값 |
| `CHATBOT_FUNCTION_URL` | 계정 B URL | stage/prod 공유 여부 계정 B 확인 |
| `CHATBOT_AUTH_ENABLED` | `true` | IAM invoke 권한 전제 |
| `CHATBOT_AWS_REGION` | `ap-northeast-2` | 기본값 |

**네트워크 주의**: 챗봇 `POST /documents/{id}/chat`은 SSE 스트리밍(타임아웃 120s) → **ALB idle timeout > 120s + 응답 버퍼링 비활성**. Redis는 document-service 직접 의존 없음(배선 불필요).

---

## 회신 후 인프라 작업 (참고)

1. document-service IRSA 역할 생성 (D항 권한)
2. ParameterStore/Secrets 기입 (환경변수 표)
3. 계정 B 협조 요청: 버킷 정책(파드 PutObject/GetObject) · 챗봇 Lambda resource policy(파드 invoke) · 챗봇 함수 ARN 회신 (E·C항)