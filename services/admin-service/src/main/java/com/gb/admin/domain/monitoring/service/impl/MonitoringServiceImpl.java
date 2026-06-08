package com.gb.admin.domain.monitoring.service.impl;

import com.gb.admin.domain.monitoring.dto.response.AuthFailuresResponse;
import com.gb.admin.domain.monitoring.dto.response.ConfigResponse;
import com.gb.admin.domain.monitoring.dto.response.ConfigResponse.Config;
import com.gb.admin.domain.monitoring.dto.response.DomainSloResponse;
import com.gb.admin.domain.monitoring.dto.response.DomainSloResponse.Slo;
import com.gb.admin.domain.monitoring.dto.response.EmbedsResponse;
import com.gb.admin.domain.monitoring.dto.response.EmbedsResponse.ArgoEmbed;
import com.gb.admin.domain.monitoring.dto.response.EmbedsResponse.GrafanaEmbed;
import com.gb.admin.domain.monitoring.dto.response.QueuesResponse;
import com.gb.admin.domain.monitoring.dto.response.QueuesResponse.Queue;
import com.gb.admin.domain.monitoring.dto.response.ServiceHealthResponse;
import com.gb.admin.domain.monitoring.dto.response.ServiceHealthResponse.ServiceHealth;
import com.gb.admin.domain.monitoring.service.MonitoringService;
import com.gb.admin.global.client.AdminMemberStats;
import com.gb.admin.global.client.AdminWalletStats;
import com.gb.admin.global.client.CommunityAdminClient;
import com.gb.admin.global.client.DocumentAdminClient;
import com.gb.admin.global.client.DocumentStats;
import com.gb.admin.global.client.MemberAdminClient;
import com.gb.admin.global.client.WalletAdminClient;
import com.gb.admin.global.config.EmbedsProperties;
import com.gb.admin.global.config.MonitoringConfigProperties;
import com.gb.admin.global.config.ServiceHealthProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonitoringServiceImpl implements MonitoringService {

    private final RestClient monitoringRestClient;
    private final ServiceHealthProperties serviceHealthProperties;
    private final MonitoringConfigProperties monitoringConfigProperties;
    private final EmbedsProperties embedsProperties;
    private final WalletAdminClient walletAdminClient;
    private final MemberAdminClient memberAdminClient;
    private final DocumentAdminClient documentAdminClient;
    private final CommunityAdminClient communityAdminClient;

    /** 헬스체크 호출 순서를 고정한다(member→wallet→document→community). 발표 화면 안정성. */
    private static final List<String> SERVICE_ORDER = List.of("member", "wallet", "document", "community");

    @Override
    public ServiceHealthResponse serviceHealth() {
        Map<String, String> urls = serviceHealthProperties.urls();
        if (urls == null || urls.isEmpty()) {
            return new ServiceHealthResponse(List.of());
        }
        List<ServiceHealth> result = new ArrayList<>();
        for (String key : SERVICE_ORDER) {
            String baseUrl = urls.get(key);
            String name = key + "-service";
            if (baseUrl == null || baseUrl.isBlank()) {
                result.add(new ServiceHealth(name, "UNKNOWN", null));
                continue;
            }
            result.add(probe(name, baseUrl));
        }
        return new ServiceHealthResponse(result);
    }

    private ServiceHealth probe(String name, String baseUrl) {
        long start = System.currentTimeMillis();
        try {
            ActuatorHealth body = monitoringRestClient.get()
                    .uri(baseUrl + "/actuator/health")
                    .retrieve()
                    .body(ActuatorHealth.class);
            long elapsed = System.currentTimeMillis() - start;
            String status = body != null && body.status() != null ? body.status() : "UNKNOWN";
            return new ServiceHealth(name, status, elapsed);
        } catch (RuntimeException e) {
            // timeout·연결 실패·404 모두 DOWN 처리 — 발표 화면이 멈추면 안 되므로 fail-open으로 status만 보고.
            log.warn("[MonitoringService] {} 헬스체크 실패 — DOWN 보고. msg={}", name, e.getMessage());
            return new ServiceHealth(name, "DOWN", null);
        }
    }

    @Override
    public DomainSloResponse domainSlo() {
        // Real 모드: 4개 도메인 stats 를 합쳐 SLO 를 만든다. fail-open 으로 한 client 가 죽어도 다른 값이 표시되도록.
        String remitRate = "0";
        String chargeRate = "0";
        String aiRate = "0";
        String kycRate = "0";
        try {
            AdminWalletStats ws = walletAdminClient.stats();
            remitRate = toPercent(ws.remittanceSuccessRate());
            chargeRate = toPercent(ws.chargeSuccessRate());
        } catch (RuntimeException e) {
            log.warn("[MonitoringService] walletStats 실패: {}", e.getMessage());
        }
        try {
            AdminMemberStats ms = memberAdminClient.stats();
            kycRate = toPercent(ms.kycPassRate());
        } catch (RuntimeException e) {
            log.warn("[MonitoringService] memberStats 실패: {}", e.getMessage());
        }
        try {
            DocumentStats ds = documentAdminClient.stats();
            long total = ds.successCount() + ds.failedCount() + ds.partialCount();
            if (total > 0) {
                aiRate = String.format("%.1f",
                        ((double) ds.successCount() / (double) total) * 100.0);
            }
        } catch (RuntimeException e) {
            log.warn("[MonitoringService] docStats 실패: {}", e.getMessage());
        }

        return new DomainSloResponse(List.of(
                new Slo("REMITTANCE_SUCCESS_RATE", "송금 성공률",
                        "99.5", remitRate, errorBudgetRemaining("99.5", remitRate), "PERCENT"),
                new Slo("AI_ANALYSIS_SUCCESS_RATE", "AI 분석 성공률",
                        "98.0", aiRate, errorBudgetRemaining("98.0", aiRate), "PERCENT"),
                new Slo("KYC_PASS_RATE", "KYC 통과율",
                        "85.0", kycRate, errorBudgetRemaining("85.0", kycRate), "PERCENT"),
                new Slo("CHARGE_SUCCESS_RATE", "충전 성공률",
                        "99.0", chargeRate, errorBudgetRemaining("99.0", chargeRate), "PERCENT")
        ));
    }

    /** "0.9971" 형식의 0~1 비율을 "99.7" 같은 퍼센트 문자열로 변환. */
    private static String toPercent(String rate) {
        if (rate == null || rate.isBlank()) return "0";
        try {
            double v = Double.parseDouble(rate);
            return String.format("%.1f", v * 100.0);
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    /**
     * SLO 대비 Error Budget 잔량을 % 문자열로 계산.
     * 공식:
     *   allowedFailureRate = 100 - target
     *   actualFailureRate  = 100 - current
     *   consumed           = actualFailureRate / allowedFailureRate
     *   remainingPct       = max(0, (1 - consumed) * 100)
     * 예: target 99.5%, current 99.71% → 잔량 42%
     *     target 99.5%, current 0%(=오늘 거래 없음·전부 실패) → 잔량 0%
     *     target 98%,   current 100% → 잔량 100%
     * target/current 둘 다 0~100 범위의 퍼센트 문자열 (예: "99.5", "100.0").
     */
    private static String errorBudgetRemaining(String targetPct, String currentPct) {
        try {
            double target = Double.parseDouble(targetPct);
            double current = Double.parseDouble(currentPct);
            double allowedFailure = 100.0 - target;
            if (allowedFailure <= 0.0) {
                // target == 100% (불가능 목표) → 임의로 100 또는 0. 0% 실패 시 100, 그 외 0.
                return current >= 100.0 ? "100.0" : "0.0";
            }
            double actualFailure = 100.0 - current;
            double consumed = actualFailure / allowedFailure;            // 0~∞
            double remaining = (1.0 - consumed) * 100.0;
            if (remaining < 0.0) remaining = 0.0;
            if (remaining > 100.0) remaining = 100.0;
            return String.format("%.1f", remaining);
        } catch (NumberFormatException e) {
            return "0.0";
        }
    }

    @Override
    public QueuesResponse queues() {
        long kyc = 0L, reports = 0L, chargeFailed = 0L, analysisFailed = 0L;
        try {
            kyc = memberAdminClient.stats().pendingKycCount();
        } catch (RuntimeException e) {
            log.warn("[MonitoringService] queues.kyc 실패: {}", e.getMessage());
        }
        try {
            reports = communityAdminClient.pendingReportCount();
        } catch (RuntimeException e) {
            log.warn("[MonitoringService] queues.reports 실패: {}", e.getMessage());
        }
        try {
            AdminWalletStats ws = walletAdminClient.stats();
            chargeFailed = ws.failedChargeQueueCount();
        } catch (RuntimeException e) {
            log.warn("[MonitoringService] queues.charge 실패: {}", e.getMessage());
        }
        try {
            analysisFailed = documentAdminClient.stats().failedCount();
        } catch (RuntimeException e) {
            log.warn("[MonitoringService] queues.analysis 실패: {}", e.getMessage());
        }
        return new QueuesResponse(List.of(
                new Queue("KYC_PENDING", "KYC 대기", kyc),
                new Queue("COMMUNITY_REPORTS", "신고 게시글", reports),
                new Queue("CHARGE_FAILED", "충전 실패", chargeFailed),
                new Queue("ANALYSIS_FAILED", "AI 분석 실패", analysisFailed)
        ));
    }

    @Override
    public AuthFailuresResponse authFailures() {
        // Phase 1: 자체 카운터 미구현 — 윈도/총합만 노출하고 사유별은 빈 배열.
        return new AuthFailuresResponse(5, 0L, List.of());
    }

    @Override
    public ConfigResponse config() {
        Map<String, String> raw = monitoringConfigProperties.configs();
        // 키 순서 고정(yml 선언 순서 기준) — Spring이 LinkedHashMap을 주입하지만 안전망으로 한 번 더 감싼다.
        Map<String, String> ordered = new LinkedHashMap<>(raw == null ? Map.of() : raw);

        List<Config> configs = new ArrayList<>();
        // 명세에 박힌 4개 키만 안정적으로 노출 — 새로 추가될 키는 다음 스프린트에서 명세부터 갱신.
        addIfPresent(configs, ordered, "wallet-charge-single-limit",
                "wallet.charge.single-limit", "KRW");
        addIfPresent(configs, ordered, "wallet-exchange-fee-rate",
                "wallet.exchange.fee-rate", null);
        addIfPresent(configs, ordered, "wallet-account-verify-rate-limit-window-seconds",
                "wallet.account.verify-rate-limit.window-seconds", null);
        addIfPresent(configs, ordered, "wallet-account-verify-rate-limit-limit",
                "wallet.account.verify-rate-limit.limit", null);
        return new ConfigResponse(configs);
    }

    private static void addIfPresent(List<Config> dest, Map<String, String> source,
                                     String propertyKey, String displayKey, String currency) {
        String value = source.get(propertyKey);
        if (value != null) {
            dest.add(new Config(displayKey, value, currency));
        }
    }

    @Override
    public EmbedsResponse embeds() {
        // base-url + path 조합으로 최종 url을 빌드한다. 응답 shape은 그대로 유지(프론트 호환).
        EmbedsProperties.Grafana grafanaCfg = embedsProperties.grafana();
        EmbedsProperties.Argocd argocdCfg = embedsProperties.argocd();

        List<GrafanaEmbed> grafana = new ArrayList<>();
        if (grafanaCfg != null && grafanaCfg.dashboards() != null) {
            String grafanaBase = nullSafe(grafanaCfg.baseUrl());
            for (EmbedsProperties.Dashboard d : grafanaCfg.dashboards()) {
                grafana.add(new GrafanaEmbed(d.name(), d.label(), grafanaBase + nullSafe(d.path())));
            }
        }
        String argocdUrl = argocdCfg == null
                ? ""
                : nullSafe(argocdCfg.baseUrl()) + nullSafe(argocdCfg.path());
        ArgoEmbed argocd = new ArgoEmbed(argocdUrl);
        return new EmbedsResponse(grafana, argocd);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** Spring Boot Actuator의 /actuator/health 응답 wire-format(외부 호출이라 @JsonProperty 명시). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ActuatorHealth(
            @JsonProperty("status") String status) {
    }
}
