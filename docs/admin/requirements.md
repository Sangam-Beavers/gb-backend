# 관리자 페이지 요구사항 (admin-service)

> 외국인 근로자 플랫폼 운영 관리자 페이지. **Phase 1만 본 PR 범위**. Phase 2(Grafana/Prometheus 인프라)는 후속.
>
> SSOT 우선순위: 본 문서 → `docs/admin/api-spec.md` → 코드. 인증·응답 규약은 [`conventions.md`](../conventions.md) 우선.

## 페이지별 기능

### 1. Dashboard
- **요약 카드** — 오늘 거래 합계(KRW/USD/VND 통화별), DAU, 오늘 분석 문서 수, 대기열 4종(KYC/신고/충전 실패/AI 분석 실패).
- **알림** — 즉시 처리 필요한 알림(SUSPICIOUS_TRANSACTION/KYC_PENDING/COMMUNITY_REPORT) 리스트.

### 2. Monitoring
- **service-health** — member/wallet/document/community `/actuator/health` 호출. 응답시간 측정.
- **domain-slo** — 도메인 SLO 4종(송금 성공률·AI 분석 성공률·KYC 통과율·충전 성공률). Phase 1은 mock 값.
- **queues** — 대기열 카운트 4종.
- **auth-failures** — 인증 실패 카운터(자체 카운터는 다음 스프린트, 윈도·총합만).
- **config** — wallet 정책 mirror(charge.single-limit, exchange.fee-rate, verify-rate-limit).
- **embeds** — Phase 2 Grafana 5종 + ArgoCD URL placeholder.

### 3. TxLogs(거래 로그)
- 필터: from/to/type/status/risk + 페이지네이션.
- CSV export: `StreamingResponseBody` + 자체 CSV writer.
- 데이터 소스: `WalletAdminClient` (Phase 1 Mock fixture, 다음 스프린트 wallet `/internal/admin` 도입).

### 4. Users(회원 관리)
- 검색: q(이메일/이름/닉네임 부분 일치) + kyc_status.
- KYC 승인/거절: 승인은 단순 POST, 거절은 `{ "reason": "..." }` 본문 필수.
- 모든 변경 액션은 `audit_logs`에 before/after JSON snapshot으로 기록.

### 5. Community(커뮤니티 운영)
- 신고 게시글 목록(category 필터).
- 게시글 숨김(POST `/{id}/hide`), 삭제(DELETE `/{id}`).
- 두 액션 모두 `audit_logs` 기록.

### 6. DocumentAI
- 문서 분석 통계(`/documents/stats`): 오늘 분석 수 + success/failed/partial 분포.
- 최근 분석 목록(`/documents/recent`).

## 인증·인가
- OAuth2 Resource Server(방식 B). `@CurrentAdminPublicId`로 JWT claim `public_id` 추출.
- Phase 1은 group claim 기반 RBAC 미적용 — `.authenticated()`만으로 보호.
- 다음 스프린트에서 SUPER/CS/COMPLIANCE/FINANCE role별 인가 분기.

## 데이터 저장
- 자체 도메인: `admin_db` 스키마(MySQL 8.0). `admin_users`, `admin_user_roles`, `audit_logs`.
- Cross-service: client 인터페이스(`MemberAdminClient` 등)만 보유, 자기 DB에 회원/거래 데이터를 중복 저장하지 않는다(CLAUDE §7).

## 금지 사항(Phase 1 한정)
- 다른 서비스(member/wallet/document/community) 코드 수정 금지(internal/admin 엔드포인트도 다음 스프린트).
- common 모듈 변경 금지.
- 새 의존성 추가는 build.gradle 명시분만(micrometer-registry-prometheus).
