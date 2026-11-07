# syntax=docker/dockerfile:1
ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
COPY pom.xml ./
COPY src/main/ ./src/main/
# Docker-requiring security tests run at the reviewed release checkpoint, not inside image build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dmaven.test.skip=true package

FROM ${RUNTIME_IMAGE} AS runtime
WORKDIR /app
RUN groupadd --gid 10001 castly \
    && useradd --uid 10001 --gid castly --no-create-home --shell /usr/sbin/nologin castly
COPY --from=build --chown=10001:10001 /build/target/castly-api-0.1.0-SNAPSHOT.jar /app/castly-api.jar
USER 10001:10001
# Render supplies PORT; Spring reads it directly. EXPOSE documents the local default only.
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/castly-api.jar"]
