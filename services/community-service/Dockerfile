FROM eclipse-temurin:17-jre
WORKDIR /app

RUN if ! id -u spring >/dev/null 2>&1; then \
      groupadd -r -f spring && useradd -r -g spring spring; \
    fi

COPY --chown=spring:spring \
     services/community-service/build/libs/community-service-*.jar \
     /app/app.jar

USER spring

EXPOSE 8082

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV SPRING_PROFILES_ACTIVE="dev"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
