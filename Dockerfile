# Dependabot proposes updates to these base image digests.
ARG APP_VERSION=1.0.3

FROM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS build
ARG APP_VERSION
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw -q -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d
ARG APP_VERSION
WORKDIR /app

# curl is required by the container healthcheck.
# Ubuntu 26.04 ships Canonical Pebble as an untracked binary. This image launches
# Java directly and has no Pebble service plan; remove the unused Go daemon so
# its vulnerable stdlib cannot ship in our runtime (CVE-2026-78667/78669/97031).
RUN rm -f /usr/bin/pebble \
    && rm -rf /var/lib/pebble \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/driftwatch-tower-${APP_VERSION}.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
