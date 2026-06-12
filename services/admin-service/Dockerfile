FROM eclipse-temurin:17-jre
WORKDIR /app

RUN (groupadd -r spring 2>/dev/null || true) && \
    (useradd -r -g spring spring 2>/dev/null || true)

COPY --chown=spring:spring \
     services/admin-service/build/libs/admin-service-*.jar \
     /app/app.jar

USER spring

EXPOSE 8085

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV SPRING_PROFILES_ACTIVE="dev"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
