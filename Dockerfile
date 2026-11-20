# Base images are pinned by digest; update deliberately and re-run the release gates.
ARG APP_VERSION=1.0.3

FROM eclipse-temurin:21-jdk@sha256:4d06038800655fe1211760cd561de70ef2ed7a47f5d69255e9834414602b7026 AS build
ARG APP_VERSION
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw -q -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre@sha256:d7051a45dd955e4d5d1db4d3f4269fe13d1c6dff8cc6b7ef89fc8577b96c1982
ARG APP_VERSION
WORKDIR /app

# curl is required by the container healthcheck. The pinned base image lags
# behind Ubuntu security updates (Trivy has flagged openssl, apt and brotli in
# turn), so apply all pending package upgrades at build time.
# Ubuntu 26.04 ships Canonical Pebble as an untracked binary. This image launches
# Java directly and has no Pebble service plan; remove the unused Go daemon so
# its vulnerable stdlib cannot ship in our runtime (CVE-2026-78667/78669/97031).
RUN rm -f /usr/bin/pebble \
    && rm -rf /var/lib/pebble \
    && apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/driftwatch-tower-${APP_VERSION}.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
