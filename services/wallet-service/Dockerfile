# syntax=docker/dockerfile:1.6
# ─────────────────────────────────────────────────────────────
# 1) Builder — Gradle 멀티모듈 빌드
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace

# settings.gradle이 모든 모듈을 include 하므로 repo 전체 복사
# (.dockerignore가 build/, .git/, .idea/ 등 제외)
COPY . .

RUN chmod +x gradlew && \
    ./gradlew :services:wallet-service:bootJar -x test --no-daemon

# ─────────────────────────────────────────────────────────────
# 2) Runtime — JRE만 (이미지 크기 ↓)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

# 비루트 사용자
RUN groupadd -r spring && useradd -r -g spring spring
USER spring

# 버전 변경에 강하게 glob 사용
COPY --from=builder --chown=spring:spring \
     /workspace/services/wallet-service/build/libs/wallet-service-*.jar \
     /app/app.jar

EXPOSE 8080

# 컨테이너 메모리 한도 인식 + 75%까지 힙 할당
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV SPRING_PROFILES_ACTIVE="dev"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
