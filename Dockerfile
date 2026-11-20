# Dependabot proposes updates to these base image digests.
ARG APP_VERSION=1.0.3

FROM eclipse-temurin:21-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1 AS build
ARG APP_VERSION
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw -q -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre@sha256:ca73b4d3961703d6d7830a4361f49ef0478f8f28b39defc07bb72f4cc7f834f6
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
