package com.gb.wallet.global.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP 요청에서 클라이언트 IP를 해석한다.
 *
 * <p>리버스 프록시/LB 뒤에서는 {@link HttpServletRequest#getRemoteAddr()}가 프록시 IP를 반환하므로,
 * {@code X-Forwarded-For} 헤더(있으면)의 <b>맨 앞</b> 값(최초 클라이언트)을 우선 사용한다.
 * 헤더가 없거나 비어 있으면 {@code getRemoteAddr()}로 fallback한다(로컬/dev 동작 유지).
 *
 * <p><b>보안 주의:</b> {@code X-Forwarded-For}는 클라이언트가 위조할 수 있다. 이 값은
 * <b>신뢰할 수 있는 리버스 프록시가 헤더를 세팅·정제하는 환경</b>에서만 신뢰한다는 전제다.
 * 현재 용도는 감사 로그(audit_log)의 참고용 IP 기록이며 인가 판단에 쓰지 않는다.
 */
public final class ClientIpResolver {

    private static final String XFF_HEADER = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    /**
     * @return X-Forwarded-For 맨 앞 IP(있으면), 없으면 {@code request.getRemoteAddr()}.
     */
    public static String resolve(HttpServletRequest request) {
        String xff = request.getHeader(XFF_HEADER);
        if (xff != null && !xff.isBlank()) {
            // "client, proxy1, proxy2" 형태 — 맨 앞이 최초 클라이언트.
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
