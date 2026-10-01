# Pinned versions and images

This file is the version manifest for a release candidate. It is updated deliberately:
any change to a runtime, image, or build tool invalidates the gates bound to the
previous candidate and must be re-verified (see the execution guide, section 8.3).

## Build and runtime

| Component | Pinned value | Notes |
|---|---|---|
| JDK | Eclipse Temurin / OpenJDK 21 (`[21,22)` enforced by maven-enforcer) | Releases are validated on 21 only |
| Maven wrapper | 3.9.9 (`.mvn/wrapper/maven-wrapper.properties`) | enforcer requires >= 3.9 |
| Spring Boot | 3.5.16 | manages Spring Framework, Spring Kafka, Micrometer, Testcontainers; upgraded from 3.3.5 to clear CVE-2026-22733 / CVE-2026-41731 |
| Spring Kafka | 3.3.16 | from the Boot BOM |
| Kafka clients / Streams | 3.9.2 | from the Boot BOM; broker is Apache Kafka 3.8.0 (client is backward compatible with older brokers) |
| PostgreSQL JDBC | 42.7.13 | property override: Boot 3.5.16 manages 42.7.11 and CVE-2026-54291 is fixed in 42.7.12 |
| Flyway | 11.7.2 | from the Boot BOM |
| Micrometer Prometheus registry | 1.15.12 | from the Boot BOM |
| springdoc-openapi | 2.8.17 | explicit; 2.6.0 predates Spring Framework 6.2 |
| Testcontainers | 1.21.4 | test scope; docker-java API version pinned in `src/test/resources/docker-java.properties` |
| AssertJ / Awaitility | 3.27.7 / 4.2.2 | from the Boot BOM |
| Logstash logback encoder | 8.0 | explicit |

## Container images (pinned by digest)

| Image | Digest |
|---|---|
| `postgres:16.15` | `sha256:1a6ab3f5345eb6dbe04a1349529caabdb0ab09293a09590fad07b2246bfa4b54` |
| `apache/kafka:3.9.2` | `sha256:05b4616e0702ef2729327705d54ad6b50ea70b271c4b730fabd2320789fb7b02` |
| `eclipse-temurin:21-jdk` (build stage) | `sha256:4d06038800655fe1211760cd561de70ef2ed7a47f5d69255e9834414602b7026` |
| `eclipse-temurin:21-jre` (runtime stage) | `sha256:d7051a45dd955e4d5d1db4d3f4269fe13d1c6dff8cc6b7ef89fc8577b96c1982` |

Digests are manifest-list digests, so they resolve per architecture. The Kafka broker
version matches the Kafka client version managed by the Boot BOM (3.9.2). The app
image adds `curl` and the Ubuntu openssl security update on top of the pinned
`eclipse-temurin:21-jre` digest.

### Upstream stack image findings (not published by this project)

`postgres:16.15` and `apache/kafka:3.9.2` are official upstream images pulled by the
compose file; this project does not republish them. Trivy 0.58.1 reports HIGH/CRITICAL
findings inside those images (for example Debian utilities in the Postgres image,
and Alpine `gnutls` plus Kafka's bundled Jackson jars in the Kafka image). Those
findings are recorded in the acceptance evidence with an upgrade/reachability note
rather than silently ignored. The project's own release image scans clean.

## Analysis tooling

| Tool | Version | Used for |
|---|---|---|
| Trivy | `aquasec/trivy:0.58.1` (container image) | dependency and image vulnerability scan (G13) |
| Playwright | pinned in `package.json` at P5.3 | browser acceptance (G12) |

## Update policy

- Upgrades are accepted only for compatibility or vulnerability fixes.
- After changing any row above, re-run the affected gates and the 24-hour acceptance
  run; a report bound to an older candidate does not cover the new one.
