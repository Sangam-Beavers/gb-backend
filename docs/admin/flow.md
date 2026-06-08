# 관리자 페이지 흐름 (admin-service)

## 1. 인증 흐름 (Authentik group 정책 — 후속)

```
관리자 브라우저 → Authentik 로그인(idP)
                ↘ JWT 발급(custom claim: public_id + (다음 스프린트) groups: SUPER/CS/...)
관리자 브라우저 → admin-service /api/v1/admin/...
                ↘ Authorization: Bearer <jwt>
admin-service: oauth2ResourceServer(jwt)로 issuer-uri JWKS 검증
              ↘ @CurrentAdminPublicId로 public_id claim 추출
              ↘ (다음 스프린트) group claim → RBAC 분기
              ↘ 컨트롤러 진입, 서비스 호출
```

- claim 누락 시 `CurrentAdminPublicIdArgumentResolver`가 `AUTH4011` fail-fast.
- 토큰 검증 실패는 common-security `RestAuthenticationEntryPoint`가 `AUTH4011` 응답.

## 2. KYC 승인 흐름 (자체 도메인 + cross-service)

```
관리자 → POST /api/v1/admin/users/{userPublicId}/kyc/approve
admin-service: AdminUserManagementServiceImpl.approveKyc
   1) MemberAdminClient.approveKyc(userPublicId, adminPublicId)
        ↳ Phase 1: MockMemberAdminClient — 로그만 남김
        ↳ 다음 스프린트: RealMemberAdminClient → member-service /internal/admin/...
   2) AuditLogService.record(
        adminPublicId, "KYC_APPROVE", "MEMBER", userPublicId,
        ipAddress, before={"kyc_status":"PENDING"}, after={"kyc_status":"APPROVED"}
      )
   3) HTTP 200 ApiResponse.success(null)
```

- before/after JSON은 audit_logs.TEXT 컬럼에 raw로 저장(검색용 아님).
- KYC 거절은 동일 흐름 + `{"reason": "..."}` 본문이 `after`에 포함.

## 3. 거래 로그 CSV 흐름

```
관리자 → GET /api/v1/admin/transactions/export.csv?from=...&to=...&type=...
admin-service: AdminTransactionController.exportCsv
   ↳ Content-Type: text/csv;charset=UTF-8
   ↳ Content-Disposition: attachment; filename="transactions-YYYY-MM-DD.csv"
   ↳ StreamingResponseBody 콜백
        AdminTransactionServiceImpl.exportCsv(filter, out)
           WalletAdminClient.searchTransactions(filter, 0, 1000)
           각 행을 RFC 4180 escape로 직접 write
```

- Phase 1은 1회 1000건 상한(Mock fixture 규모 충분). 페이징 청크 스트리밍은 다음 스프린트.

## 4. 모니터링 service-health 흐름

```
관리자 → GET /api/v1/admin/monitoring/service-health
admin-service: MonitoringServiceImpl.serviceHealth
   ↳ ServiceHealthProperties.urls (member/wallet/document/community)
   ↳ 각 base URL에 GET /actuator/health (timeout 1s)
   ↳ 결과: { name, status, response_time_ms } 4건
```

- 호출 실패/타임아웃 시 status="DOWN", response_time_ms=null. WARN 로그.
- 발표 화면이 멈추지 않도록 fail-open(예외 미전파).

## 5. 감사 로그 append-only 보장

- `AuditLog` 엔티티: `@Setter` 없음, `@Builder`만. `@NoArgsConstructor(access = PROTECTED)`.
- UPDATE/DELETE 메서드 미제공 — wallet의 `TransactionAuditLog`와 동일 패턴.
- 컨트롤러는 audit 기록 자체를 호출하지 않고 service 계층이 도메인 액션 직후 `auditLogService.record(...)`.
