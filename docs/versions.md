# Runtime and dependency versions

This table records the versions pinned by the current source tree. The exact image digest and
artifact checksums for a published build are recorded in that release's manifest. These pins are
maintained for reproducible builds; they are not a feed of the newest upstream versions.

## Build and runtime

| Component | Pinned value | Notes |
|---|---|---|
| JDK | Eclipse Temurin / OpenJDK 21 (`[21,22)` enforced by maven-enforcer) | Releases are validated on 21 only |
| Maven wrapper | 3.9.9 (`.mvn/wrapper/maven-wrapper.properties`) | enforcer requires >= 3.9 |
| Spring Boot | 3.5.16 | manages Spring Framework, Spring Kafka, Micrometer, Testcontainers; upgraded from 3.3.5 to clear CVE-2026-22733 / CVE-2026-41731 |
| Spring Kafka | 3.3.16 | from the Boot BOM |
| Kafka clients / Streams | 4.3.2 | `kafka.version` override; retains Boot 3.5.16 / Spring Kafka 3.3.16; broker remains Apache Kafka 3.9.2 |
| LZ4 / Snappy | 1.11.4 / 1.1.10.11 | Kafka 4.3.2 runtime transitives; replace the manual CVE pins of 1.11.4 / 1.1.10.9 without downgrading |
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
| `eclipse-temurin:21-jdk` (build stage) | See [Dockerfile](../Dockerfile) |
| `eclipse-temurin:21-jre` (runtime stage) | See [Dockerfile](../Dockerfile) |

Digests are manifest-list digests, so they resolve per architecture. The Kafka 3.9.2
broker is retained in Compose and integration tests: it exceeds the Kafka 4.x client
minimum broker version of 2.1. Kafka 4.3.2 is selected instead of 4.4.0 because
4.4.0 declares older LZ4 (1.11.2) and Snappy (1.1.10.7) dependencies than the
removed security pins. The app
image adds `curl` for its health check and gets base OS security updates by
refreshing the pinned Temurin digest, not by upgrading all OS packages at build time.

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

- Dependabot checks Maven and npm weekly and the pinned Temurin base images daily.
- Review compatibility and vulnerability impact before changing a pin.
- Re-run the affected build, security, and integration checks after changing a pin. Release
  evidence applies only to the exact candidate recorded in its manifest.
