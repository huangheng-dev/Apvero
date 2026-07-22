FROM gradle:9.3.1-jdk25 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle :apps:platform-server:bootJar --no-daemon

FROM eclipse-temurin:25-jre AS runtime-base
LABEL org.opencontainers.image.title="Apvero Platform Server"
LABEL org.opencontainers.image.source="https://github.com/huangheng-dev/Apvero"
LABEL org.opencontainers.image.licenses="Apache-2.0"
RUN useradd --system --uid 10001 --create-home apvero
WORKDIR /app
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/apvero.jar"]

# CI packages the exact JAR produced and tested by the backend job. Keeping this
# target in the production Dockerfile prevents runtime-image configuration drift.
FROM runtime-base AS runtime-prebuilt
COPY --chown=10001:10001 apps/platform-server/build/libs/*.jar /app/apvero.jar

# The default target remains a self-contained source build for local users and
# release pipelines that do not provide a prebuilt artifact.
FROM runtime-base AS runtime
COPY --from=build --chown=10001:10001 /workspace/apps/platform-server/build/libs/*.jar /app/apvero.jar
