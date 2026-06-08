# 관측 인프라 (Observability Stack)

Prometheus + Grafana로 5개 Spring Boot 서비스(member/wallet/document/community/admin)
메트릭을 수집·시각화하는 로컬 개발용 스택.

## 사전 조건

- Docker + docker-compose
- 5개 서비스가 호스트(8081~8085)에서 실행 중
- 각 서비스에 `micrometer-registry-prometheus` 의존성 + `/actuator/prometheus` 노출 (이미 적용됨)

## 실행

```bash
cd infra/observability
docker-compose up -d
```

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)

## 메트릭 노출 확인 (5개 서비스 각각)

```bash
curl http://localhost:8081/actuator/prometheus  # member
curl http://localhost:8082/actuator/prometheus  # community
curl http://localhost:8083/actuator/prometheus  # document
curl http://localhost:8084/actuator/prometheus  # wallet
curl http://localhost:8085/actuator/prometheus  # admin
```

## Grafana 대시보드 import

stub JSON이 자동 provisioning됨. 실제 대시보드는 Grafana UI에서:

- Dashboards → New → Import
- ID 입력 (4701 = JVM Micrometer, 11378 = Spring Boot Statistics)
- Datasource: Prometheus 선택

uid는 `jvm-micrometer` / `spring-boot-statistics`로 유지해야 admin-service의 embed URL과 매칭됩니다.

## admin-service 임베드 연동

`admin-service`의 `GET /api/v1/admin/monitoring/embeds` 응답이 가리키는 URL이 본 스택 기준입니다.

- `GRAFANA_BASE_URL` (기본 `http://localhost:3000`)
- `ARGOCD_BASE_URL` (기본 `http://localhost:8080`)

환경별로 다른 호스트면 환경변수만 덮어쓰세요. dashboard path는 yml의 `admin.monitoring.embeds.grafana.dashboards[*].path`로 관리합니다.

## EKS 전환

`docs/admin/monitoring-infra.md` 참고. kube-prometheus-stack Helm chart로 교체.

## 정리

```bash
docker-compose down          # 컨테이너 중지
docker-compose down -v       # 데이터(볼륨) 까지 삭제
```
