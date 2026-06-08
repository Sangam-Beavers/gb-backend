# 모니터링 인프라 (Phase 2)

> 발표 + 다음 스프린트용 기술 설계 문서.
> Phase 1(admin-service의 비즈니스 모니터링 API)은 완료, Phase 2(Prometheus/Grafana 인프라)는
> 본 문서를 기준으로 진행한다.

## 1. 개요 — 하이브리드 모니터링 전략

운영 모니터링은 **두 축**으로 나눠 본다.

| 축 | 도구 | 책임 |
| --- | --- | --- |
| 비즈니스 메트릭 | `admin-service` 자체 API | SLO, 큐 카운트, 운영 설정, 헬스 요약 — **도메인 의미**가 들어가는 지표 |
| 인프라 메트릭 | Prometheus + Grafana | JVM, HTTP, DB, Redis, K8s — **표준 익스포터**가 잘 만들어둔 지표 |

자체 API로 도메인 지표를 노출하고, 인프라 지표는 표준 도구로 받아 admin 화면에 iframe 임베드한다.
"내가 만든 화면 ↔ 외부 화면"의 경계를 admin UI에서 자연스럽게 잇는 구조다.

## 2. 아키텍처

```
[5 Spring Boot services] --(GET /actuator/prometheus)--> [Prometheus]
   member · community · document · wallet · admin                 |
                                                                  ↓
                                                              [Grafana]
                                                                  ↓ iframe
                                                       [admin Monitoring tab]
```

- 5개 서비스 모두 `micrometer-registry-prometheus`로 `/actuator/prometheus` 노출.
- Prometheus가 15s 주기로 scrape.
- Grafana가 Prometheus를 datasource로 붙임.
- admin-service의 `GET /api/v1/admin/monitoring/embeds`가 Grafana/ArgoCD URL을 반환,
  프론트는 그 URL을 iframe으로 임베드.

## 3. 로컬 docker-compose 실행

자세한 실행 방법은 [`infra/observability/README.md`](../../infra/observability/README.md) 참고.

요약:

```bash
cd infra/observability
docker-compose up -d
```

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)

대시보드 import는 Grafana UI에서:
- ID **4701** — JVM Micrometer
- ID **11378** — Spring Boot Statistics
- uid는 `jvm-micrometer` / `spring-boot-statistics`로 유지(admin embed URL과 매칭).

## 4. 메트릭 노출 검증

5개 서비스를 띄운 뒤 각각 curl로 확인:

```bash
curl -s http://localhost:8081/actuator/prometheus | head -n 30   # member
curl -s http://localhost:8082/actuator/prometheus | head -n 30   # community
curl -s http://localhost:8083/actuator/prometheus | head -n 30   # document
curl -s http://localhost:8084/actuator/prometheus | head -n 30   # wallet
curl -s http://localhost:8085/actuator/prometheus | head -n 30   # admin
```

`jvm_memory_used_bytes`, `http_server_requests_seconds_count` 같은 표준 메트릭이 출력되면 OK.

Prometheus UI(`http://localhost:9090/targets`)에서 5개 target이 모두 `UP`이면 scrape 정상.

## 5. EKS 전환 계획

운영은 EKS에서 `kube-prometheus-stack` Helm chart로 한다. 로컬 docker-compose는 발표/개발용이고,
운영 인프라는 Kubernetes-native 방식으로 표준화한다.

### 5-1. 설치

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

helm install gb-observability prometheus-community/kube-prometheus-stack \
  --namespace monitoring --create-namespace \
  --values values-monitoring.yaml
```

### 5-2. ServiceMonitor CRD로 5개 서비스 scrape

각 서비스 Deployment의 Pod label에 `app.kubernetes.io/part-of: gb-backend`를 부여한 뒤,
공통 ServiceMonitor 하나로 5개를 같이 잡는다.

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: gb-backend
  namespace: monitoring
spec:
  selector:
    matchLabels:
      app.kubernetes.io/part-of: gb-backend
  namespaceSelector:
    matchNames:
      - gb-backend
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

서비스마다 Service 리소스의 `ports[].name`을 `http`로 통일해 두면 endpoint가 자동 매칭된다.

### 5-3. Grafana 대시보드 provisioning

EKS에서는 ConfigMap에 dashboard JSON을 박고, sidecar(`grafana-sidecar`)가 label
`grafana_dashboard=1`인 ConfigMap을 자동으로 읽어들이게 한다.

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: gb-jvm-dashboard
  namespace: monitoring
  labels:
    grafana_dashboard: "1"
data:
  jvm-micrometer.json: |
    { ... }
```

`infra/observability/grafana/dashboards/*.json`을 그대로 ConfigMap으로 옮기면 된다.

## 6. Grafana 인증

발표 데모는 admin/admin로 충분하지만, 운영에서는 OIDC로 admin-service와 같은 IdP를 공유한다.

- **dev**: Authentik (`Application: gb-grafana`, redirect_uri: `https://grafana.gb.internal/login/generic_oauth`)
- **stage/prod**: Cognito User Pool (별도 App Client)

Grafana의 OIDC 설정 예시 (`grafana.ini` 또는 Helm values):

```ini
[auth.generic_oauth]
enabled = true
name = Authentik
allow_sign_up = true
client_id = ${OIDC_CLIENT_ID}
client_secret = ${OIDC_CLIENT_SECRET}
scopes = openid email profile
auth_url = ${OIDC_ISSUER_URI}/application/o/authorize/
token_url = ${OIDC_ISSUER_URI}/application/o/token/
api_url = ${OIDC_ISSUER_URI}/application/o/userinfo/
```

admin 화면에서 iframe으로 임베드하므로, Grafana의 `auth.anonymous` 또는 OIDC SSO 한쪽으로
브라우저 단일 로그인 흐름을 맞춰야 임베드가 자연스럽다 (CORS/Frame ancestors도 함께 설정).

## 7. 알람 (Alertmanager)

다음 스프린트. PromQL로 SLO 위반·메트릭 임계 위반 alert를 정의하고 Alertmanager가 라우팅한다.

흐름:
```
PrometheusRule (CRD) → Alertmanager → SNS Topic → Slack webhook (#gb-alerts)
```

예시 (송금 성공률 SLO 위반):
```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: gb-remittance-slo
spec:
  groups:
    - name: remittance
      rules:
        - alert: RemittanceSuccessRateLow
          expr: |
            sum(rate(remittance_success_total[10m]))
              / sum(rate(remittance_attempt_total[10m])) < 0.995
          for: 5m
          labels:
            severity: critical
          annotations:
            summary: "송금 성공률이 SLO(99.5%) 아래로 떨어졌습니다"
```

## 8. 비용

| 항목 | 로컬/EKS 자체 운영 | AWS Managed |
| --- | --- | --- |
| 메트릭 저장 | Prometheus + EBS gp3 | CloudWatch / AMP |
| 대시보드 | Grafana OSS (무료) | Amazon Managed Grafana |
| 알람 | Alertmanager (무료) | CloudWatch Alarms |
| 인증 | OIDC SSO (Authentik/Cognito) | IAM Identity Center |

**1단계(MVP)**: docker-compose. 비용 0. 발표·로컬 개발용.
**2단계(EKS 운영)**: kube-prometheus-stack을 EKS에 직접 운영. EBS + 노드 비용만.
**3단계(필요 시)**: AMP/Managed Grafana 전환. 운영 부담을 AWS에 위임할 가치가 보이면.

당분간은 2단계까지로 충분. 매니지드 전환은 운영 메트릭 양·SLO 알람 부담이 본격적으로 보일 때.
