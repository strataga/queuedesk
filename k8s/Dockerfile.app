# QueueDesk backend image for the local kind lab (Phase 9). Multi-stage: project
# wrapper build on Temurin 25 (matches CI), then a slim non-root JRE runtime.
# No registry involved: scripts/k8s loads the built tag into kind directly.
FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates perl && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY mvnw pom.xml ./
COPY scripts/java-env.sh scripts/java-env.sh
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline -DskipTests
COPY src src
RUN ./mvnw -q package -DskipTests && cp target/queuedesk-*.jar /app.jar

FROM eclipse-temurin:25-jre
RUN useradd --create-home --uid 10001 app
USER app
WORKDIR /app
COPY --from=build --chown=app /app.jar /app/app.jar
EXPOSE 8080
# JAVA_OPTS is set by the k8s manifest (heap cap inside the container limit).
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
