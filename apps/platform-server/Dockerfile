FROM gradle:9.3.1-jdk25 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle :apps:platform-server:bootJar --no-daemon

FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="Apvero Platform Server"
LABEL org.opencontainers.image.source="https://github.com/huangheng-dev/Apvero"
LABEL org.opencontainers.image.licenses="Apache-2.0"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home apvero
WORKDIR /app
COPY --from=build /workspace/apps/platform-server/build/libs/*.jar /app/apvero.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/apvero.jar"]
