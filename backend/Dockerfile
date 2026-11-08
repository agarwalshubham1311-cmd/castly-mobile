# syntax=docker/dockerfile:1
# Build from the backend directory (Render Root Directory: backend).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -DskipTests package && \
    find target -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*.original' -print -quit | grep -q . && \
    cp "$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*.original' -print -quit)" /workspace/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system --gid 10001 castly && \
    useradd --system --uid 10001 --gid 10001 --no-create-home castly
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar
USER 10001:10001
EXPOSE 10000
ENV PORT=10000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
