# R1 PoC — Function URL ↔ Spring SseEmitter 스트리밍 중계

> 정본: `docs/document-analysis/ai-chatbot-mcp.md` §12 R1.
> 이 PoC는 "**더미 Lambda가 토큰 3개를 흘리면 Spring을 거쳐 브라우저까지 도달하는가**" 하나만 검증한다.
> 진짜 챗봇 Lambda(Bedrock Tool Use + KB/MCP)는 Phase 4에서 따로 만든다.

---

## 파일 구성

| 파일 | 역할 |
| --- | --- |
| `index.mjs` | AWS Lambda 배포용 (Node.js 20, `awslambda.streamifyResponse`) |
| `local-mock-server.py` | 로컬 검증용 모킹 서버 (Function URL을 흉내내는 chunked SSE) |
| `test-page.html` | 브라우저에서 fetch + ReadableStream으로 점진 표시 |

---

## 검증 시나리오

### A. 로컬 mock만 사용 (가장 빠름, AWS 불필요)

```bash
# 터미널 1 — mock 서버 띄우기
cd services/document-service/poc/r1-stream
python3 local-mock-server.py    # http://127.0.0.1:9000/

# 터미널 2 — document-service 띄우기
# 기본값으로 chatbot.function-url=http://127.0.0.1:9000/, auth-enabled=false 라 따로 환경변수 없어도 됨.
./gradlew :services:document-service:bootRun

# 터미널 3 — SSE 토큰 확인 (curl로 raw stream 보기)
curl -N -X POST http://localhost:8083/api/v1/poc/chat-stream \
     -H "Content-Type: application/json" \
     -H "X-User-Public-Id: demo-user" \
     -d '{"message":"베트남 돈으로 얼마야?","user_lang":"ko"}'
```

curl 출력이 한 줄씩 점진적으로 찍히면 ✅ **R1 통과**.

```
event: token
data: 안녕

event: token
data: 하세요

... (0.3초 간격)

event: done
data: {"session_id":"..."}
```

브라우저로 확인하려면 `test-page.html`을 더블클릭(or `python3 -m http.server`) 후 버튼 클릭. 글자가 한 토큰씩 점진적으로 찍히면 통과.

> ⚠️ 브라우저에서 띄울 때 CORS가 막히면 document-service에 CORS 설정 추가 필요. PoC 단계에서는 curl로 충분.

### B. AWS Lambda + Function URL (실전 검증)

1. **Lambda 함수 생성**
   - Runtime: Node.js 20.x
   - `index.mjs` 업로드
   - Handler: `index.handler`

2. **Function URL 활성화**
   - InvokeMode: **RESPONSE_STREAM** (필수)
   - Auth type: **AWS_IAM**

3. **document-service 실행 환경에 IAM 권한 부여**
   - 정책: `lambda:InvokeFunctionUrl` 허용 (대상 Lambda ARN)
   - 백엔드(EC2/EKS) IAM 역할 또는 로컬 AWS_PROFILE에 부여

4. **환경변수 세팅 후 기동**
   ```bash
   export CHATBOT_FUNCTION_URL="https://<id>.lambda-url.ap-northeast-2.on.aws/"
   export CHATBOT_AUTH_ENABLED=true
   # AWS 자격증명은 ~/.aws/credentials, IMDS, 환경변수 등 DefaultCredentialsProvider 체인 어디든 OK
   ./gradlew :services:document-service:bootRun
   ```

5. 위 A의 curl을 다시 실행 → 동일하게 토큰 스트림이 흐르면 통과.

---

## 디버깅 체크리스트

| 증상 | 원인 후보 |
| --- | --- |
| curl이 즉시 끊김 / 빈 응답 | `chatbot.function-url`이 mock 서버와 다른 포트 / mock 서버 미기동 |
| 토큰이 한꺼번에 도착(점진 표시 X) | curl에 `-N` 옵션 누락 / 중간 프록시 버퍼링 / `Cache-Control: no-cache` 헤더 누락 |
| `HTTP 403` from Function URL | IAM 권한 부족 또는 SigV4 서명 실패. 서버 로그의 AWS SDK 메시지 확인 |
| `HTTP 400 invalid model identifier` | (Phase 4에서 발생) 서울 리전에 `us.` 접두 inference profile 사용. `apac.anthropic.claude-*` 또는 `global.anthropic.claude-*`로 교체 (ai-chatbot-mcp §12 R3) |
| Java HttpClient 헤더 충돌 | Host/Content-Length 등 제한 헤더가 SigV4 결과에 포함됐을 때 — 코드에서 필터링되지만 누락된 헤더가 있으면 추가 필터 필요 |

---

## 다음 단계

R1 통과 후:

1. **Phase 2** — Document 엔티티/Repository/ErrorCode 스켈레톤 (이유진과 인터페이스 합의)
2. **Phase 3** — ChatPocController를 진짜 `POST /api/v1/documents/{id}/chat`로 승격, 권한 검증 + MySQL 요약 추출 추가
3. **Phase 4** — `index.mjs`를 진짜 챗봇 Lambda(Python + Bedrock Tool Use + KB + MCP)로 교체
