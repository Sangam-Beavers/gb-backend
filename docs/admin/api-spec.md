# admin-service API 명세

> Base URL: `/api/v1/admin`. 인증 필요(Bearer JWT). 응답 envelope·snake_case·UTC ISO 8601은 [`conventions.md`](../conventions.md) 준수.

## 1. Admins (본인 정보)

### `GET /admins/me`
JWT의 `public_id`로 admin DB 조회. 미스 시 mock(admin01 SUPER) 반환(Phase 1).

응답:
```json
{
  "success": true,
  "data": {
    "public_id": "00000000-0000-0000-0000-000000000001",
    "email": "admin01@gb.com",
    "nickname": "Admin Kim",
    "is_active": true,
    "roles": ["SUPER"]
  },
  "message": "요청이 성공적으로 처리되었습니다."
}
```

## 2. Dashboard

### `GET /dashboard/summary`
```json
{
  "success": true,
  "data": {
    "today_transactions_total": { "KRW": "82400000.0000", "USD": "12500.0000", "VND": "1234567000.0000" },
    "daily_active_users": 12430,
    "today_documents_analyzed": 348,
    "queues": { "kyc_pending": 25, "community_reports": 17, "charge_failed": 3, "analysis_failed": 2 }
  },
  "message": "..."
}
```

### `GET /dashboard/alerts`
```json
{
  "success": true,
  "data": {
    "alerts": [
      { "type": "SUSPICIOUS_TRANSACTION", "label": "이상거래", "message": "고액 송금 3건 탐지", "status": "REVIEW_NEEDED", "created_at": "2026-06-08T10:21:00Z" },
      { "type": "KYC_PENDING", "label": "KYC", "message": "신분증 OCR 승인 대기 25건", "status": "PENDING", "created_at": "2026-06-08T09:00:00Z" },
      { "type": "COMMUNITY_REPORT", "label": "커뮤니티", "message": "신고 누적 게시글 17건", "status": "ACTION_NEEDED", "created_at": "2026-06-08T08:30:00Z" }
    ]
  }
}
```

## 3. Monitoring

### `GET /monitoring/service-health`
```json
{
  "success": true,
  "data": {
    "services": [
      { "name": "member-service", "status": "UP", "response_time_ms": 12 },
      { "name": "wallet-service", "status": "UP", "response_time_ms": 18 },
      { "name": "document-service", "status": "UP", "response_time_ms": 23 },
      { "name": "community-service", "status": "UP", "response_time_ms": 15 }
    ]
  }
}
```
- 호출 실패 시 `{ "name": "...", "status": "DOWN", "response_time_ms": null }`.

### `GET /monitoring/domain-slo`
```json
{
  "success": true,
  "data": {
    "slos": [
      { "name": "REMITTANCE_SUCCESS_RATE", "label": "송금 성공률", "target": "99.5", "current": "99.71", "error_budget_remaining_pct": "88.0", "unit": "PERCENT" },
      { "name": "AI_ANALYSIS_SUCCESS_RATE", "label": "AI 분석 성공률", "target": "98.0", "current": "99.2", "error_budget_remaining_pct": "65.0", "unit": "PERCENT" },
      { "name": "KYC_PASS_RATE", "label": "KYC 통과율", "target": "85.0", "current": "87.3", "error_budget_remaining_pct": "74.0", "unit": "PERCENT" },
      { "name": "CHARGE_SUCCESS_RATE", "label": "충전 성공률", "target": "99.0", "current": "99.5", "error_budget_remaining_pct": "92.0", "unit": "PERCENT" }
    ]
  }
}
```

### `GET /monitoring/queues`
```json
{
  "success": true,
  "data": {
    "queues": [
      { "name": "KYC_PENDING", "label": "KYC 대기", "count": 25 },
      { "name": "COMMUNITY_REPORTS", "label": "신고 게시글", "count": 17 },
      { "name": "CHARGE_FAILED", "label": "충전 실패", "count": 3 },
      { "name": "ANALYSIS_FAILED", "label": "AI 분석 실패", "count": 2 }
    ]
  }
}
```

### `GET /monitoring/auth-failures`
```json
{ "success": true, "data": { "window_minutes": 5, "total_failures": 0, "by_reason": [] } }
```

### `GET /monitoring/config`
```json
{
  "success": true,
  "data": {
    "configs": [
      { "key": "wallet.charge.single-limit", "value": "10000000", "currency": "KRW" },
      { "key": "wallet.exchange.fee-rate", "value": "0.005", "currency": null },
      { "key": "wallet.account.verify-rate-limit.window-seconds", "value": "60", "currency": null },
      { "key": "wallet.account.verify-rate-limit.limit", "value": "10", "currency": null }
    ]
  }
}
```

### `GET /monitoring/embeds`
```json
{
  "success": true,
  "data": {
    "grafana": [
      { "name": "kubernetes_cluster", "label": "Kubernetes Cluster", "url": "https://grafana.gb.internal/d/cluster?kiosk=tv&theme=dark" },
      { "name": "jvm", "label": "JVM Micrometer", "url": "https://grafana.gb.internal/d/jvm?kiosk=tv&theme=dark" },
      { "name": "rds", "label": "AWS RDS", "url": "https://grafana.gb.internal/d/rds?kiosk=tv&theme=dark" },
      { "name": "redis", "label": "Redis", "url": "https://grafana.gb.internal/d/redis?kiosk=tv&theme=dark" },
      { "name": "sqs", "label": "AWS SQS", "url": "https://grafana.gb.internal/d/sqs?kiosk=tv&theme=dark" }
    ],
    "argocd": { "url": "https://argocd.gb.internal/applications?showSidebar=false" }
  }
}
```

## 4. Transactions

### `GET /transactions?from=&to=&type=&status=&risk=&page=&size=`
페이지네이션 응답(`transactions` 배열).

### `GET /transactions/export.csv?from=&to=&type=&status=&risk=`
- Content-Type: `text/csv;charset=UTF-8`
- Content-Disposition: `attachment; filename="transactions-YYYY-MM-DD.csv"`
- 칼럼: `transaction_public_id,user_public_id,user_name,type,amount,currency_code,status,risk_level,executed_at`

## 5. Users (회원 관리)

### `GET /users?kyc_status=&q=&page=&size=`
KYC 상태/검색어로 필터.

### `POST /users/{publicId}/kyc/approve`
승인. 본문 없음. audit_log 기록.

### `POST /users/{publicId}/kyc/reject`
```json
{ "reason": "신분증 사진이 흐려 식별 불가" }
```

## 6. Community

### `GET /community/reports?category=&page=&size=`

### `POST /community/posts/{publicId}/hide`
### `DELETE /community/posts/{publicId}`

## 7. Documents

### `GET /documents/stats`
```json
{
  "success": true,
  "data": { "today_analyzed": 348, "success_count": 332, "failed_count": 11, "partial_count": 5 }
}
```

### `GET /documents/recent?page=&size=`

## 8. Audit Logs

### `GET /audit-logs?action=&target_type=&from=&to=&page=&size=`
정렬 `created_at desc` 고정(Phase 1).

## 에러 코드

| code | HTTP | 의미 |
|---|---|---|
| `AUTH4011` | 401 | 인증이 필요합니다(공통). |
| `COMMON4031` | 403 | 접근 권한이 없습니다(공통, 다음 스프린트 role 검사). |
| `COMMON4001` | 400 | 요청 값 검증 실패(reason 필수 등). |
| `ADMIN4001` | 404 | 존재하지 않는 관리자입니다(Phase 1 미사용 — 다음 스프린트). |
| `COMMON5000` | 500 | 서버 오류(cross-service 장애 등). |
