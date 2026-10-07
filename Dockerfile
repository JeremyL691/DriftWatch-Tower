# Base images are pinned by digest; update deliberately and re-run the release gates.
ARG APP_VERSION=1.0.2

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

# curl is required by the container healthcheck. openssl/libssl3t64 are upgraded
# because the pinned base image ships 3.5.5-1ubuntu3.5, which Trivy flags for
# CVE-2026-84782; Ubuntu already publishes the fixed 3.5.5-1ubuntu3.6.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl openssl libssl3t64 openssl-provider-legacy \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/driftwatch-tower-${APP_VERSION}.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
