# 기술 스택 (Tech Stack)

> Claude Code가 코드를 작성할 때 사용할 언어·프레임워크·라이브러리와 그 선택 근거.

---

## 1. 한눈에 보기

| 영역 | 선택 | 비고 |
| --- | --- | --- |
| 백엔드 프레임워크 | **Spring MVC** (WebFlux 아님) | 동기, JPA 사용 |
| ORM | **JPA (Hibernate)** | 트랜잭션·연관관계·낙관적 락 |
| DB | **MySQL 8.0** | Aurora MySQL(운영/스테이징) · 온프렘 MySQL(개발) 공통 스키마 |
| 캐시/락 | **Redis** | 분산 락, 멱등성, 세션, 카운터, 캐시 |
| 인증 | **JWT** | Authentik(개발) / Cognito(운영·스테이징) |
| 컨테이너 오케스트레이션 | **Kubernetes** | 온프렘(Cilium) / AWS EKS |
| AI (계정 B) | **AWS Bedrock (Claude)** + **Aurora PostgreSQL + pgvector** | OCR/분석/번역 + 법령 RAG |
| 메시징 | **SQS** | 계정 B → 계정 A 분석 결과 비동기 전달 |

---

## 2. Spring MVC를 선택한 이유 (WebFlux 채택 안 함)

| 항목 | Spring MVC | Spring WebFlux |
| --- | --- | --- |
| 처리 방식 | 동기 (요청당 스레드) | 비동기 리액티브 |
| JPA | ✅ 사용 가능 | ❌ R2DBC 써야 함 |
| 트랜잭션 디버깅 | 스택 트레이스 명확 | 리액티브 체인으로 복잡 |
| 금융 레퍼런스 | 매우 많음 | 적음 |
| 팀 학습 비용 | 낮음 | 높음 |

**근거 3가지**

1. **JPA와 검증된 조합** — 금융 트랜잭션, 연관관계 매핑, 낙관적 락(`@Version`)을 안정적으로 구현. WebFlux는 R2DBC라 `@Transactional`·Dirty Checking 같은 편의 기능을 못 쓴다.
2. **금융 트랜잭션 디버깅이 쉽다** — 송금 장애 시 어느 단계에서 실패했는지 동기 스택 트레이스로 명확히 추적된다.
3. **팀 학습 비용 절감** — MSA 자체가 처음이라 배울 게 많은데, 리액티브(Mono/Flux/백프레셔)까지 동시에 학습하면 부담이 크다. MSA·Kafka·Redis·mTLS 같은 핵심 과제에 집중한다.

> WebFlux는 수많은 동시 연결을 적은 스레드로 처리하는 실시간 스트리밍/채팅류에 유리하다. 우리 서비스는 요청-응답 기반 금융 트랜잭션이 중심이라 그 장점이 크지 않다.

---

## 3. Redis를 쓰는 이유 (DB가 있는데도)

DB만으로도 기능은 되지만, 아래 상황에서 Redis가 필요하다.

| 용도 | 동작 | 왜 Redis인가 |
| --- | --- | --- |
| **분산 락** | `SET lock:user:{id} 1 NX EX 5` | DB 락은 단일 DB 내에서만. MSA 여러 Pod 간 공유는 Redis 락 |
| **멱등성 키** | `SET idempotency:{key} result EX 86400` | 네트워크 재시도로 인한 중복 송금 방지 |
| **토큰 블랙리스트** | 로그아웃 토큰 등록, 만료시간 TTL | 매 요청 DB 조회 대신 빠른 조회로 즉시 무효화 |
| **Rate Limiting** | `INCR login:fail:{id}` + `EXPIRE` | 로그인 브루트포스 차단 |
| **카운터** | `INCR view:post:{id}`, 주기 배치로 DB 동기화 | 조회수 매번 UPDATE 방지(DB 부하 분산) |
| **환율·공통코드 캐시** | TTL 60초 캐싱 | 외부 환율 API 반복 호출 방지 |
| **세션 캐시** | 진행 중 대화/세션 TTL 보관 | 빠른 문맥 조회 |

> ⚠️ **잔액(balance)은 절대 캐싱하지 않는다.** 캐시와 실제 잔액 불일치는 잘못된 송금으로 이어진다.

Redis 키 네이밍 규칙은 [`database.md`](./database.md)의 Redis 섹션 참고.

---

## 4. 데이터 저장 정책

| 데이터 | 저장소 |
| --- | --- |
| 회원/금융/커뮤니티/문서 메타 + AI 분석 결과 | **MySQL 8.0** (Aurora / 온프렘 공통) |
| 법령 임베딩 벡터 (RAG) | **Aurora PostgreSQL + pgvector** (계정 B) |
| 분산 락/캐시/세션/카운터 | **Redis** |
| 업로드 원본 파일 (분석 처리 중) | **S3** (계정 B), 처리 후 삭제 |

> ❌ **DynamoDB는 사용하지 않는다.** (이전 설계에서 제거됨. 분석 결과는 MySQL `document_results`에 직접 저장)

---

## 5. 버전·환경 메모

- DB: MySQL 8.0
- 온프렘 K8s: Rocky Linux 9 / Kubernetes 1.35 / Cilium(CNI, kube-proxy 대체) / vSphere CSI
- 금액 컬럼: `DECIMAL(18,4)` (FLOAT/DOUBLE 금지 — 부동소수점 오차)
- 외부 노출 ID: `VARCHAR(36)` UUID (`public_id`)

> 모델 문자열·SDK 사용법 등 Anthropic/AWS 제품의 최신 세부값이 필요하면, 추정하지 말고 공식 문서를 확인할 것.
