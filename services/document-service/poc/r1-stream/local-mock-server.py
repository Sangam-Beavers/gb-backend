"""
R1 PoC — 로컬 모킹 서버 (Function URL 대체)

목적: AWS Lambda 배포 전에 Spring side(SseEmitter 중계)만 먼저 검증하기 위한 로컬 서버.
      Function URL과 동일한 chunked transfer + SSE 응답을 흉내낸다. IAM 서명 없음(localhost는
      ChatbotLambdaClient에서 서명 스킵하도록 분기됨).

실행:
    python3 local-mock-server.py
    → http://localhost:9000/  에서 POST 요청을 받아 토큰을 0.3초 간격으로 스트리밍.

테스트:
    curl -N -X POST http://localhost:9000/ \
         -H "Content-Type: application/json" \
         -d '{"message":"테스트","session_id":"abc"}'
"""

import json
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


TOKENS = [
    "안녕", "하세요", ". ",
    "분석", "된 ", "계약서", "를 ", "확인", "했어요", ". ",
    "추가로 ", "궁금한 ", "점을 ", "물어보세요", ".",
]


class StreamHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        # 요청 페이로드 읽기(검증용 로깅만).
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception:
            payload = {}
        print(f"[mock] incoming: {payload}")

        session_id = payload.get("session_id") or str(uuid.uuid4())

        # SSE 헤더. Transfer-Encoding: chunked는 헤더 명시 없이 wfile.write를 청크 단위로 호출해도 OK.
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()

        try:
            for token in TOKENS:
                frame = f"event: token\ndata: {token}\n\n".encode("utf-8")
                self.wfile.write(frame)
                self.wfile.flush()
                time.sleep(0.3)

            done = f"event: done\ndata: {json.dumps({'session_id': session_id})}\n\n".encode("utf-8")
            self.wfile.write(done)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            # 클라이언트가 먼저 끊으면 무시.
            pass

    def log_message(self, fmt, *args):
        # 기본 액세스 로그는 시끄럽기만 하니 직접 출력 모드만 사용.
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 9000), StreamHandler)
    print("[mock] R1 PoC mock stream server on http://127.0.0.1:9000/")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock] bye")
