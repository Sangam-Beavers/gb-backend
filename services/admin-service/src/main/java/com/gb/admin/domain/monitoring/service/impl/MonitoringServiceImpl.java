package com.gb.admin.domain.monitoring.service.impl;

import com.gb.admin.domain.monitoring.dto.response.AuthFailuresResponse;
import com.gb.admin.domain.monitoring.dto.response.BusinessAnalyticsResponse;
import com.gb.admin.domain.monitoring.dto.response.BusinessAnalyticsResponse.Bucket;
import com.gb.admin.domain.monitoring.dto.response.BusinessAnalyticsResponse.Demographics;
import com.gb.admin.domain.monitoring.dto.response.BusinessAnalyticsResponse.Revenue;
import com.gb.admin.domain.monitoring.dto.response.ConfigResponse;
import com.gb.admin.domain.monitoring.dto.response.ConfigResponse.Config;
import com.gb.admin.domain.monitoring.dto.response.DomainSloResponse;
import com.gb.admin.domain.monitoring.dto.response.DomainSloResponse.Slo;
import com.gb.admin.domain.monitoring.dto.response.EmbedsResponse;
import com.gb.admin.domain.monitoring.dto.response.EmbedsResponse.ArgoEmbed;
import com.gb.admin.domain.monitoring.dto.response.InfraAlertsResponse;
import com.gb.admin.domain.monitoring.dto.response.InfraAlertsResponse.Alert;
import com.gb.admin.domain.monitoring.dto.response.EmbedsResponse.GrafanaEmbed;
import com.gb.admin.domain.monitoring.dto.response.QueuesResponse;
import com.gb.admin.domain.monitoring.dto.response.QueuesResponse.Queue;
import com.gb.admin.domain.monitoring.dto.response.ServiceHealthResponse;
import com.gb.admin.domain.monitoring.dto.response.ServiceHealthResponse.ServiceHealth;
import com.gb.admin.domain.monitoring.service.MonitoringService;
import com.gb.admin.global.client.AdminMemberDemographics;
import com.gb.admin.global.client.AdminMemberStats;
import com.gb.admin.global.client.AdminWalletStats;
import com.gb.admin.global.client.CommunityAdminClient;
import com.gb.admin.global.client.DocumentAdminClient;
import com.gb.admin.global.client.DocumentStats;
import com.gb.admin.global.client.MemberAdminClient;
import com.gb.admin.global.client.PrometheusClient;
import com.gb.admin.global.client.PrometheusClient.PromSample;
import com.gb.admin.global.client.WalletAdminClient;
import com.gb.admin.global.config.EmbedsProperties;
import com.gb.admin.global.config.MonitoringConfigProperties;
import com.gb.admin.global.config.ServiceHealthProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
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
    private final PrometheusClient prometheusClient;

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

    @Override
    public BusinessAnalyticsResponse businessAnalytics() {
        // 인프라 헬스와 분리된 비즈니스 카테고리. member 인구통계 + wallet 사용/매출을 합쳐 내려준다.
        // 각 client 호출은 fail-open — 한쪽이 죽어도 나머지 값은 화면에 표시된다(발표 안정성).

        // ── 인구통계 (member-service) ──
        List<Bucket> gender = List.of();
        List<Bucket> age = List.of();
        List<Bucket> nationality = List.of();
        try {
            AdminMemberDemographics d = memberAdminClient.demographics();
            gender = toBuckets(d.genderDistribution());
            age = toBuckets(d.ageDistribution());
            nationality = toBuckets(d.nationalityDistribution());
        } catch (RuntimeException e) {
            log.warn("[MonitoringService] demographics 실패(fail-open): {}", e.getMessage());
        }

        // ── 회원 규모 (member-service) ──
        long totalMembers = 0L;
        long newMembersToday = 0L;
        try {
            AdminMemberStats ms = memberAdminClient.stats();
            totalMembers = ms.totalMembers();
            newMembersToday = ms.newMembersToday();
        } catch (RuntimeException e) {
            log.warn("[MonitoringService] businessAnalytics.memberStats 실패(fail-open): {}", e.getMessage());
        }

        // ── 거래/매출 (wallet-service) ──
        long dailyActiveUsers = 0L;
        Map<String, String> todayTransactionsTotal = new LinkedHashMap<>();
        List<Bucket> transactionsByAction = new ArrayList<>();
        try {
            AdminWalletStats ws = walletAdminClient.stats();
            dailyActiveUsers = ws.dailyActiveUsers();
            if (ws.todayTransactionsTotal() != null) {
                // 금액은 String 전송(CLAUDE §5). BigDecimal → toPlainString.
                for (Map.Entry<String, BigDecimal> e : ws.todayTransactionsTotal().entrySet()) {
                    BigDecimal amount = e.getValue() == null ? BigDecimal.ZERO : e.getValue();
                    todayTransactionsTotal.put(e.getKey(), amount.toPlainString());
                }
            }
            if (ws.byAction() != null) {
                for (Map.Entry<String, Long> e : ws.byAction().entrySet()) {
                    transactionsByAction.add(new Bucket(e.getKey(),
                            e.getValue() == null ? 0L : e.getValue()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("[MonitoringService] businessAnalytics.walletStats 실패(fail-open): {}", e.getMessage());
        }

        // ── 매출원: 환전 수수료율 (admin config mirror) ──
        Map<String, String> rawConfigs = monitoringConfigProperties.configs();
        String exchangeFeeRate = rawConfigs == null
                ? "0"
                : rawConfigs.getOrDefault("wallet-exchange-fee-rate", "0");

        return new BusinessAnalyticsResponse(
                new Demographics(gender, age, nationality),
                new Revenue(totalMembers, newMembersToday, dailyActiveUsers,
                        todayTransactionsTotal, transactionsByAction, exchangeFeeRate));
    }

    @Override
    public InfraAlertsResponse infraAlerts() {
        // Prometheus(YACE) 지표 기준 인프라 경보. PromQL에 임계 조건을 넣어 결과 시리즈=발화 경보로 본다.
        // base-url 미설정/호출 실패 시 PrometheusClient가 빈 리스트 → 경보 0건(fail-soft).
        List<Alert> alerts = new ArrayList<>();
        // ⚠️ 임시 테스트(경보 점등 확인용): RDS CPU 임계를 1%로 낮춤 → 평상시 값(~5%)에도 발화한다.
        //    확인 끝나면 반드시 `> 1` 을 `> 80` 으로 원복할 것.
        evalRule(alerts, "aws_rds_cpuutilization_average{dimension_DBInstanceIdentifier!=\"\"} > 1",
                "critical", "RDS", "dimension_DBInstanceIdentifier", "RDS CPU 높음", "%");
        evalRule(alerts, "aws_rds_aurora_replica_lag_average{dimension_DBInstanceIdentifier!=\"\"} > 1000",
                "warning", "RDS", "dimension_DBInstanceIdentifier", "Aurora replica lag 높음", "ms");
        evalRule(alerts, "aws_rds_freeable_memory_average{dimension_DBInstanceIdentifier!=\"\"} < 536870912",
                "warning", "RDS", "dimension_DBInstanceIdentifier", "RDS 가용 메모리 낮음", " bytes");
        evalRule(alerts, "aws_elasticache_database_memory_usage_percentage_average{dimension_CacheClusterId!=\"\"} > 80",
                "warning", "ELASTICACHE", "dimension_CacheClusterId", "ElastiCache 메모리 사용률 높음", "%");
        evalRule(alerts, "aws_elasticache_evictions_sum{dimension_CacheClusterId!=\"\"} > 0",
                "warning", "ELASTICACHE", "dimension_CacheClusterId", "ElastiCache eviction 발생", "건");
        return new InfraAlertsResponse(alerts);
    }

    /** PromQL 조건을 평가해 반환된 각 시리즈를 경보로 변환한다. */
    private void evalRule(List<Alert> dest, String promql, String level, String source,
                          String labelKey, String label, String unit) {
        for (PromSample s : prometheusClient.query(promql)) {
            String instance = s.labels().getOrDefault(labelKey, "?");
            String value = formatPromValue(s.value());
            dest.add(new Alert(level, source, label + " — " + instance + " " + value + unit));
        }
    }

    /** Prometheus 값 문자열을 소수 1자리로 정리(정수면 정수로). */
    private static String formatPromValue(String raw) {
        if (raw == null || raw.isBlank()) return "?";
        try {
            double v = Double.parseDouble(raw);
            if (v == Math.floor(v) && !Double.isInfinite(v)) {
                return String.valueOf((long) v);
            }
            return String.format("%.1f", v);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static List<Bucket> toBuckets(List<AdminMemberDemographics.Bucket> src) {
        if (src == null) return List.of();
        List<Bucket> out = new ArrayList<>(src.size());
        for (AdminMemberDemographics.Bucket b : src) {
            out.add(new Bucket(b.key(), b.count()));
        }
        return out;
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
