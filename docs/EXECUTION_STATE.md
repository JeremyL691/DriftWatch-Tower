# DriftWatch Tower 执行状态

本文件只记录事实，不另行定义范围。[执行指南](PROJECT_EXECUTION_GUIDE.md)是唯一规范，[启动提示词](AGENT_REFACTOR_PROMPT.md)交给接手 Agent。

## 当前入口

| 字段 | 当前值 |
|---|---|
| document_revision | 1.0 |
| handoff_date | 2026-09-30，America/Los_Angeles |
| product_goal_status | RUNNING，P0-P2 完成，P3 进行中 |
| current_phase | P5 |
| current_task | P5.3 |
| next_action | P5.3：Dashboard 完整操作状态、四断点、暗/亮、键盘与真实 before/after 截图（G12） |
| local_baseline_sha | 84400133d9aab140e6e7d8bd34550c178c89a69a（历史本地基线） |
| remote_snapshot_sha | 082fd84d7fabee7d94e05b4dba842f0995a3775e，2026-09-30 执行时经 git fetch 重新核验 |
| execution_branch | codex/release-v1（本地；尚未推送） |
| execution_base_sha | 082fd84d7fabee7d94e05b4dba842f0995a3775e |
| handoff_commit_sha | 0a2bb07b45fb44576a5a6e909fdf836e6557e14c（文档交接 rebase 到 origin/main） |
| original_worktree_backup_ref | backup/handoff-worktree-20260930 -> 1967034bda95b135a939bc34f4a9d7e3b5949b68（rebase 前的交接提交，含全部未提交变更） |
| candidate_sha | 7d36c61（P5.3 进行中；P5.3 实现/P6/P7 未完成，仍不能作为发布候选） |
| source_tree_hash | 8c8797ad9fe505c8e12d0f797264e4cb15de7fc5c00ffddce25c61cdd023570c（src+pom+Dockerfile+compose） |
| candidate_image_id / public_digest | 本地镜像 sha256:1a419f6a…（仅本地验证，未发布） |
| target_release | v1.0.0；2026-09-30 核验远端仅有 tag v0.1.0，无冲突 |
| docs_delivery_status | VERIFIED，本轮文档交付核验通过，且 rebase 后内容逐字节一致 |
| release_authorization | 用户已授权接手 Agent 提交、推送、合并自己的 PR、公开 Release/GHCR |
| application_changes_in_handoff | 无业务代码、依赖、配置、CI、迁移改动 |
| active_soak_run | 无；P1.3 已验证 runner 行为（150s 短run PASSED），正式 24 小时待在 P6.2 启动 |
| external_blocker | 无 |

文档交付不等于 P0/P7 完成。接手 Agent 不要把本文件的历史审核结果移入新候选的 PASSED 门禁。

## 历史审核快照

以下来自本次会话前面的 2026-09-30 审核，尚未作为新重构版本重新执行：

- 本地既有套件：53 项通过，0 失败/错误/跳过；远端快照既有套件：57 项通过，0 失败/错误/跳过。
- 环境：审核机器 Java 25，Docker API/Byte Buddy 使用过命令级兼容参数。新发布验收使用 Java 21。
- 连续字段缺失没有 NULL_SPIKE，以及低基线突增没有 ANOMALY_SPIKE，在本地/远端均复现。输入在指南第 5.4 节。
- 本地 healthy -> STALE 漏报，在远端快照的新增回归测试已修复。
- Compose Kafka 镜像曾返回 not found；自动 incident 未接入；source health 无独立 scheduler。
- 无 token 的 apache/kafka 公共事件请求曾 HTTP 200，有实际事件、ETag 及 poll header。
- 原始 [GitHub CI](https://github.com/JeremyL691/DriftWatch-Tower/actions/runs/33464634754)属于远端快照，不属于未来候选。
- 可移植的复现输入与判断已写进指南；不得依赖当前机器 /private/tmp 里的文件才能执行。

## 任务状态

允许状态：NOT_STARTED / RUNNING / PASSED / FAILED / BLOCKED。任务从以下列表更新，不再另建竞争路线图。

| Task | 内容 | 状态 | 证据 / 说明 |
|---|---|---|---|
| P0.1 | 保护交接、对齐远端 | PASSED | codex/release-v1 @ 0a2bb07，base 082fd84；见 2026-09-30 运行记录 |
| P0.2 | 基线与红色回归 | PASSED | 60/0/0/0 真实容器；G01 三例 EXPECTED_FAILURE 已绑定 SHA |
| P1.1 | 可重复部署、固定依赖 | PASSED | G02 通过；apache/kafka:3.9.2、postgres:16.15、摘要锁定、SCA 应用镜像 0 High/Critical |
| P1.2 | 配置和认证基础 | PASSED | 86/0/0/0；live 19/19；弱/缺生产凭证 fail-fast；见 `.execution/runs/p12/` |
| P1.3 | 验证脚本及后台 runner | PASSED | preflight/unit/sca/compose 入口全部 PASSED；selfhost init/up/status/down/backup/restore 实测；soak runner 采样/checkpoint/连续性/恢复实测 |
| P2.1 | scope / 窗口 / 漏报修复 | PASSED | G03 PASSED（101/0/0/0，DetectionContractTest 18 项）；G01 三例已转绿 |
| P2.2 | 规则与配置覆盖 | PASSED | 规则边界/哈希规范/数组契约/OpenAPI 契约测试；126/0/0/0 |
| P2.3 | schema 事务及基线反馈 | PASSED | G04 PASSED；ACTIVE 唯一、advisory lock、outbox 补发、激活 API；126/0/0/0 |
| P3.1 | 摄取确认、envelope、幂等 | PASSED | G05 PASSED（132/0/0/0）；见 `.execution/verify/p3-gate1/` |
| P3.2 | retry / DLT / replay | PASSED | G06 PASSED（141/0/0/0 + 现场停机演练）；见 `.execution/verify/p3-gate3/`、`.execution/runs/p32-drill/` |
| P3.3 | 历史升级与回滚演练 | PASSED | G07 PASSED；真实旧版本镜像 + 升级/桥接/回滚演练；`.execution/runs/p33-upgrade/` |
| P4.1 | GitHub 持久 poller | PASSED | G08 PASSED（152/0/0/0，GithubPollerIntegrationTest 11 项）；`.execution/verify/p4-gate1/` |
| P4.2 | 官方真实数据全链路 | PASSED | G09 PASSED；官方源无 token，bootstrap 199 事件 + 启动后新增 LIVE 事件 + 重启检查点保持；`.execution/runs/p42-smoke/` |
| P5.1 | incident / scheduler | PASSED | G10 PASSED（163/0/0/0；IncidentLifecycle 6、Scheduler 2、CollectorStatus 3）；`.execution/verify/p5-gate3/` |
| P5.2 | 指标、保留、备份恢复 | PASSED | G11 PASSED（165/0/0/0 + 备份/新卷恢复演练）；`.execution/verify/p5b-gate3/`、`.execution/runs/p52-drill/` |
| P5.3 | Dashboard 操作与响应式 | RUNNING | before 截图与缺陷清单已产出（`.execution/runs/p53-before/`）；实现与 after 截图待做 |
| P6.1 | 冻结候选、短门槛、负载 | NOT_STARTED | - |
| P6.2 | 24 小时真实验收 | NOT_STARTED | - |
| P7.1 | 合并自己的重构 PR | NOT_STARTED | - |
| P7.2 | 公共 Release / GHCR | NOT_STARTED | - |
| P7.3 | 匿名安装及最终报告 | NOT_STARTED | - |

## 门禁状态

NOT_RUN不是PASSED。EXPECTED_FAILURE仅允许G01旧版本的已知回归；修复后G03必须通过。

| Gate | 状态 | 绑定 SHA / 配置 / 证据 |
|---|---|---|
| G00 基线 | PASSED（入口证据绑定 P1.3 SHA，P6.1 冻结候选时重跑） | Java 21.0.12.1 + Docker 29.5.3；60 tests / 0 fail / 0 err / 0 skip；`.execution/runs/20261001T020542Z-p02-baseline/` |
| G01 红色回归复现 | EXPECTED_FAILURE | 3 个 5.4 用例在旧实现复现（null 全缺失 / 单事件基线突增 / 乱序覆盖窗口）；`g01-red-regression.log`、`g01-cases.json` |
| G02 全新 Compose | PASSED（入口证据绑定 P1.3 SHA，P6.1 冻结候选时重跑） | 无缓存拉取+构建；容器启动后 6–19s 内全部 healthy（≤120s）；3 分区 topic、Streams changelog、状态卷、仅回环暴露；`runs/20261001T022637Z-p11-g02/` |
| G03 检测正确性 | PASSED（PHASE-P2 入口证据绑定 653a7e3，P6.1 重跑） | 已随候选重新绑定：SHA 653a7e3（含 bb13ab9 应用代码）；PHASE-P2 gate 126 tests / 0 fail / 0 skip；`.execution/verify/p2-gate6/` |
| G04 schema 反馈 | PASSED | 已随候选重新绑定：SHA 653a7e3；PHASE-P2 gate 126 tests / 0 fail / 0 skip，SchemaTransactionIntegrationTest 4 项；`.execution/verify/p2-gate6/` |
| G05 摄取与幂等 | PASSED（最新 PHASE-P3 证据 7217ff67 已覆盖其用例） | SHA 45d18bce；PHASE-P3 gate：132 tests / 0 fail / 0 skip，IdempotencyIntegrationTest 5 + PreAckFailureTest 1 + KafkaIngestionIntegrationTest 1；`.execution/verify/p3-gate1/` |
| G06 故障与死信 | PASSED（最新 PHASE-P3 证据 7217ff67 已覆盖其用例） | SHA b5c10c0a；PHASE-P3 gate 141 tests / 0 fail / 0 skip（DeadLetterIntegrationTest 5、SinkRetryTest 4）；现场演练：60s 停机被重试吸收且无死信、200s 停机产生死信并在恢复后投影与重放（各一次副作用）；`.execution/runs/p32-drill/` |
| G07 升级兼容 | PASSED | SHA 7217ff67；PHASE-P3 gate 141 tests / 0 fail / 0 skip；演练：V1-V7 checksum 不变、5 条旧行保留且可查（legacy-db 身份）、2 条真实 backlog 桥接（稳定 legacy-kafka 身份）、仅回滚镜像被 Flyway 拒绝而「备份恢复 + 旧镜像」可用；`.execution/runs/p33-upgrade/` |
| G08 来源协议 | PASSED | SHA fbba4d05；PHASE-P4 gate 152 tests / 0 fail / 0 skip；GithubPollerIntegrationTest 11 项覆盖 304/403+Retry-After/429/401/404/500+恢复/超时/坏 JSON/跨页重叠/bootstrap 与 live 模式/重启恢复；`.execution/verify/p4-gate1/` |
| G09 官方真实源 | PASSED | 官方 api.github.com 无 token；bootstrap 199 条真实事件（mode=BOOTSTRAP、SKIPPED_MODE）、启动后新增 16162901734（mode=LIVE）经 inbox→outbox→Kafka→receipt→raw→API 全链路、重启后检查点保持不重摄；`.execution/runs/p42-smoke/` |
| G10 操作闭环 | PASSED | SHA 4b111417；PHASE-P5 gate 163 tests / 0 fail / 0 skip；incident 关联并发唯一、resolve 语义与自动解决、ack 幂等/409、静默 scheduler 单次转换、GET 无副作用；`.execution/verify/p5-gate3/` |
| G11 保留与恢复 | PASSED | SHA 6bf026de；PHASE-P5b gate 165 tests / 0 fail / 0 skip；pg_dump → 全新专用卷恢复计数一致（raw=4/alerts=1/schema=1/receipts=4）、保留策略保护未解决证据、指标面无身份标签；`.execution/runs/p52-drill/` |
| G12 浏览器与四断点 | NOT_RUN | - |
| G13 安全与漏洞 | NOT_RUN | - |
| G14 100/s、30分钟 | NOT_RUN | - |
| G15 候选包安装 | NOT_RUN | - |
| G16 连续24小时 | NOT_RUN | - |
| G17 公开发布/匿名安装 | NOT_RUN | - |

## 长任务与恢复字段

当前无长任务。启动后填写以下信息，并保留旧失败 run 的记录：

| 字段 | 值 |
|---|---|
| run_id / run_dir | 未启动 |
| compose_project / volume 所有权 | 未分配 |
| env_file 路径 | 未生成；不得填 secret 内容 |
| candidate_sha / image_id / config_hash | 未冻结 |
| started_at_utc / expected_end_at_utc | 未启动 |
| runner_pid / process_start / lock | 无 |
| last_heartbeat_utc / checkpoint | 无 |
| live_unique_events / new_after_bootstrap | 未测量 |
| source_poll 状态 / outbox / DLT / lag | 未测量 |
| planned_faults / completed_faults | 尚未执行 |
| monitor_gap / continuity_valid | 未测量 |
| exact_resume_command | P1.3 脚本实现后填 |
| last_failure / required_external_action | 无 |

恢复顺序：读状态 -> 核对 checkout/SHA -> 查原 runner 锁和进程身份 -> 验证采样连续性 -> 继续现有任务或保留失败记录并新建 run。不能看到 PID 就启动第二套。

## 运行记录与阻塞

### 2026-09-30 P0.1 保护交接并对齐远端（PASSED）

- 绑定 SHA：base 082fd84d7fabee7d94e05b4dba842f0995a3775e，交接提交 0a2bb07b45fb44576a5a6e909fdf836e6557e14c。
- 命令与结果：
  - `git status --short`：dirty 集合仅为批准文档交接（README、samples/events/README.md、SVG、5 个已批准删除、3 个新文档），无业务代码或用户其他变更。
  - `git fetch origin`：本地 main 落后 origin/main 3 个提交（9990316、5317f3f、082fd84），无领先提交。
  - 分支 `codex/release-v1` 自 8440013 创建，提交交接（1967034），`git rebase origin/main` 后冲突处理：README 采用交接版本；docs/README.md、docs/assets/dashboard-preview.svg、docs/sample-incident-report.md 维持删除；SVG 与远端一致自动合并。
  - `git diff --stat 1967034 0a2bb07 -- README.md docs samples`：空，交接文档逐字节一致。
  - 保护：`backup/handoff-worktree-20260930` -> 1967034（rebase 前完整工作树，可恢复）。
  - `git ls-remote origin refs/heads/main`：082fd84…；`git ls-remote --tags origin`：仅 v0.1.0，v1.0.0 无冲突。
- 未执行：推送、24 小时任务、发布。工作树 clean。
- 环境：Homebrew openjdk@21 已安装，供 P0.2 使用；Docker 29.5.3、Compose v5.1.4、Python 3.14.7、gh 2.101.0（ADMIN）。

### 2026-10-01 P0.2 基线与红色回归（PASSED）

运行目录：`.execution/runs/20261001T020542Z-p02-baseline/`（机器 11 CPU / 19.3 GiB RAM / 96 GiB 空闲，arm64，macOS）。

- 环境修复：Docker Engine 29.5.3 最低要求 API 1.40（实测 `/v1.32/info` 400、`/v1.40/info` 200），而 Testcontainers 1.19.8 内嵌 docker-java 默认 1.32，导致 4 个容器测试类被 `disabledWithoutDocker` 静默跳过。新增 `src/test/resources/docker-java.properties`（`api.version=1.40`，兼容 Engine 19.03–29）后真实容器全部运行。1.21.3 及环境变量方式均无效，故采用项目内显式配置而非临时参数。
- G00：`./mvnw clean test --batch-mode`（JAVA_HOME=OpenJDK 21.0.12.1）→ 60 tests / 0 failures / 0 errors / 0 skipped，19 个测试类，含 4 个真实 Kafka/PostgreSQL 容器类；与历史 57/0/0/0 的差异 = +3 个新增绿色回归用例（新增测试类其余 3 例），旧 57 项全部保留。
- G01：`./mvnw test -Dtest=Guide54DetectionRegressionTest -Dgroups=expected-failure -Dsurefire.excludedGroups= --batch-mode` → 3 run / 3 failures（EXPECTED_FAILURE）。失败原因（读源码确认，非猜测）：NULL/ANOMALY 处理器用“前一状态低于阈值且当前高于阈值”的跃迁判定，替代了契约要求的 per-window fired 标志；null 窗口状态 key 不含窗口，导致乱序旧窗口事件覆盖当前窗口计数。绿色锁定用例：旧 null [正常、缺失、缺失]、旧 anomaly [2、2、8]、重复 event_id 保留双输入。
- 已知红色用例以 JUnit tag `expected-failure` 排除出默认 surefire 运行（`surefire.excludedGroups` 属性可覆盖），P2 修复后必须移除该排除并转绿。
- Compose 镜像核验：`docker manifest inspect bitnami/kafka:3.7` → `no such manifest`；`apache/kafka:3.8.0` 与 `postgres:16` 存在。P1.1 必须替换镜像。
- 真实 GitHub 只读核验（2026-10-01T02:05Z）：HTTP 200、5 条真实事件（如 IssueCommentEvent 16151737466）、ETag、`x-poll-interval: 60`、`x-ratelimit-limit: 60`（remaining 52）；证据 `github-headers.txt`、`github-events-sample.json`。
- 已核验远端修复：`SourceHealthService` 的 healthy→STALE 单次转换告警已存在并有测试锁定，不重写。
- 未推送、未发布。

### 2026-10-01 P1.1 可重复部署与固定依赖（PASSED）

运行目录：`.execution/runs/20261001T022637Z-p11-g02/`。

- 镜像：`bitnami/kafka:3.7` 确认不存在（`no such manifest`），改用官方 `apache/kafka:3.9.2`（与 Boot BOM 的 kafka-clients 3.9.2 一致）；`postgres:16.15`；全部按 manifest-list digest 锁定（compose、Dockerfile、Testcontainers 同步）。Kafka listener 使用 `PLAINTEXT://kafka:29092`（容器内）+ `EXTERNAL://localhost:9092`（开发覆盖），健康检查用镜像自带 `kafka-broker-api-versions.sh`（该镜像无 curl）。
- 端口：postgres/kafka 不发布到宿主机；app 仅 `127.0.0.1:${DWT_APP_PORT}`；`docker-compose.dev.yml` 提供本地开发端口覆盖；Streams state 挂载 `streams-state` 卷并设 `driftwatch-streams-v1`。
- 依赖升级（仅漏洞修复）：Spring Boot 3.3.5 → 3.5.16（CVE-2026-22733 / CVE-2026-41731），PostgreSQL 驱动 42.7.13（CVE-2026-54291），jackson-bom 2.21.7（CVE-2026-68497/91776/91777），tomcat 10.1.59（CVE-2026-65182/65905/68525），springdoc 2.8.17。运行时镜像额外安装 Ubuntu 已发布的 openssl 修复（CVE-2026-84782）。
- SCA：Trivy 0.58.1（容器）扫描依赖树与应用镜像 → 0 HIGH/CRITICAL；postgres/kafka 上游镜像内的发现已保存（`sca/trivy-stack-images.txt`），在 docs/versions.md 声明为上游镜像、本项目不重新发布。
- 守护：maven-enforcer 强制 Java [21,22) 与 Maven ≥3.9；`docs/versions.md` 为版本 manifest；`ContainerIntegrationTest` 支持 `dwt.requireDocker=true`（验收时 Docker 缺失即失败而非跳过）。
- G02 两轮：全新拉取+构建后全部服务在 233s（含拉取与 Maven 构建）内 healthy；容器启动到 healthy 18–19s（第一轮）、13s（第二轮，app 6s）；均 ≤120s。topic `raw-events`/`quality-events` 3 分区；Streams changelog 4 个；POST 事件 HTTP 202 并落库。
- 观察记录（供后续任务）：应用重启后 Streams 重新开始处理前约有 24s 空窗，而当前 readiness 只反映 DB；P1.2/P3 必须让 readiness 反映 Kafka/Streams。
- 依赖变更后完整套件重跑：60 tests / 0 fail / 0 err / 0 skip，broker 3.9.2（`mvn-final-p11.log`）。
- 验收资源已清理：`docker compose -p dwt-g02 down -v`，无残留卷/网络。

### 2026-10-01 P1.2 配置与认证基础（PASSED）

- 配置集中：`DriftwatchProperties`（`driftwatch.*`）统一绑定 detector/metrics/streams/source-health/security/source；数值范围由 Bean Validation 强制，窗口/grace/retention、异常历史窗口、来源新鲜度顺序、field-range min<=max 在启动时校验并指明 key；错误信息不含 secret。新增 grace/future-tolerance/state-retention/github late 阈值键作为 P2 契约。
- 访问保护（§2.3）：Spring Security HTTP Basic 管理账号 + 独立 Bearer ingest token（SHA-256 常量时间比较）；浏览器修改请求经 CSRF Cookie 校验（`XSRF-TOKEN` 可读、`X-XSRF-TOKEN` 提交），摄取接口豁免 CSRF；匿名仅可见 `/actuator/health` 状态；metrics/prometheus/api-docs/dashboard 均需管理员；无账号数据库、不使用 localStorage。
- 凭据：`selfhost` profile 要求强口令（>=16 且非示例值），缺失或过弱启动失败（exit 1，指明 key，不回显值）；空白口令在所有 profile 都失败。`dev`/`test`/`load` profile 提供轻量本地凭据，真实 GitHub 采集在测试与负载 profile 关闭（selfhost 开启）。
- readiness 反映 DB + Kafka + Kafka Streams（`kafka`/`streams` 指标），liveness 仅 ping；应用重启后 Streams 未运行时不会误报健康。
- 验证：单元+容器套件 86 tests / 0 fail / 0 err / 0 skip（需 Docker，`-Ddwt.requireDocker=true`）；live 环境 19/19 检查通过（401/403、health 详情隔离、ingest 只能摄取、CSRF 流程、坏 JSON 400、事件落库）；弱/缺口令 fail-fast 各以独立容器复现。证据 `.execution/runs/p12/`（summary.md、junit-summary.json、live-check-output.txt）与 `.execution/runs/p12-live/`。
- 顺带修复：logback 只覆盖 prod/dev/default 导致 selfhost 无日志；坏 JSON 返回 500 改为 400；dashboard JS 增加 CSRF 头。
- 经验记录：测试套件与验收 compose 栈不可同时运行（vCPU/内存竞争会导致 readiness 与落库超时）；验收脚本必须串行（P1.3）。

### 2026-10-01 P1.3 自动执行入口（PASSED）

脚本位于 `scripts/`（verify.sh、selfhost.sh、acceptance.py、lib/common.sh）。命令与指南 §9.1 同名同参数；退出码 0 通过 / 1 验证失败 / 2 外部前提缺失或未实现；每个命令在 `--out` 写入 `gate.json`（含 id/status/command/时间/exit_code/git_sha/证据路径）。

实测（当前 SHA dfd79c2，source_tree_hash 8c8797ad…）：

- `verify.sh preflight` → PASSED，gate.json 记录 JDK21（脚本自动选择 21）、Docker 29.5.3、Compose v5.1.4、Python 3.14.7、11 CPU/19.3GiB、磁盘、端口（18080 空闲；8080/5432/9092 被用户进程占用时只提示不抢占）、gh 认证。
- `verify.sh unit` → PASSED，86 tests / 0 fail / 0 err / 0 skip（`-Ddwt.requireDocker=true`，Docker 不可用直接 exit 2）。
- `verify.sh sca` → PASSED，Trivy 0.58.1（DB UpdatedAt 2026-10-01T01:24:14Z），依赖+镜像 0 HIGH/CRITICAL。
- `verify.sh compose --project dwt-p13b` → PASSED，全新项目启动，容器 start→ready 11s（限 120s），raw/quality 各 3 分区，pg/kafka 无宿主端口，摄取 smoke 202 且落库。
- `verify.sh phase P2/P3/P4/P5`、`load`、`release` 在对应阶段实现前返回 exit 2 且 gate.json 为 NOT_IMPLEMENTED（不预标 PASS）。
- `selfhost.sh init` 生成随机凭证（0600）；`up/status/down` 实测；`down` 默认保留卷、`--volumes` 才删除；`backup` 生成 pg_dump custom 格式（23KB）；`restore --target-db driftwatch_restore` 恢复后 raw_events=1、flyway 迁移=7。
- 资源隔离修复：compose 卷/网络改为按项目名作用域（`<project>_pgdata` 等），验收项目不再可能读写或删除自托管安装的数据卷。
- `acceptance.py`：soak-start 异步（PID + 进程启动时间 + 锁文件），150s 验证 run 采样 5 点、连续性 max gap 32.2s（限 120s）、结果 PASSED 并写入镜像身份；重复 soak-start 返回既有状态且不启动第二个 runner；kill 掉 runner 后 `soak-resume` 将旧 run 标记 FAILED、给出原因并以原时长启动新 run（exit 1）。
- 脚本只操作自己的 compose 项目；用户容器（cpamp-*）与 8080/5432/9092 未被占用或清理。凭证只在 `.execution/*.env`（0600）与容器运行时，脚本与 gate.json 不含 secret。

### 2026-10-01 P2.1 检测契约与 scope/窗口（PASSED，G03）

- 内部类型：`RawEnvelope`（contract_version=1、ingestion_id、received_at、origin、mode、origin_reference、replay_of）、`WindowEvaluation`（scope/window_start/window_end/outcome/watermark/detail）、`ScopeKey`（规范 JSON 数组编码）、`RuleVersions`。拓扑输入改为 envelope；旧 raw-events 由 `EnvelopeAdapter` 过渡包装，P3.1 再切 topic。
- 窗口语义：null/anomaly 状态键包含 window_start，各自 fired；per-scope watermark + grace/future tolerance 决定 INCLUDED/EXPIRED/FUTURE；驱逐按 scope watermark（未来事件不会驱逐 grace 内窗口）；anomaly 基线含观测范围内零窗口，记录 WARMING_UP/BASELINE_ZERO；基线缺失时 `baseline_status=PENDING`（只跳过依赖基线的检查）。
- 投递身份：ENRICH 后、任何检测前做 ingestion_id + envelope 摘要校验；重投 REDELIVERY 跳过检测，同 id 不同内容 CONFLICT（不污染计数）。
- 去重按 scope 编码键，跨 source 同 event_id/payload 不再误报（契约 5.2）。
- G03：`./scripts/verify.sh phase P2` → PASSED，101 tests / 0 fail / 0 err / 0 skip（DetectionContractTest 18 项覆盖 5.4 全部输入、边界、watermark 隔离、重投/冲突、模式跳过、scope key 不可碰撞；QualityStreamsTopologyTest 7 项原用例保留）。原 `expected-failure` 标签与 surefire 排除已移除。
- 顺带修复：`MetricWindowProjector` 跳过非 INCLUDED 事件；`verify.sh unit/phase` 对容器启动失败做一次有记录的重试（首次日志保留为 attempt1）。
- 观察：容器启动偶发失败（ContainerLaunchException/exit 126）在重试后消失，属环境竞争；两次运行的日志都保留。

### 2026-10-01 P2.2 / P2.3 规则覆盖与 schema 事务（PASSED，G03+G04）

- P2.2：非法 regex 在启动时失败并指明 `driftwatch.detector.field-format.patterns`；数值字段的非数字值改为类型证据（NOT_A_NUMBER + value_type）而不是静默跳过；所有检测告警带 rule_version；新增测试锁定数组/嵌套 leaf 契约、哈希规范化（嵌套键序、数组顺序、unicode、null）、质量状态优先级与 exclusion coverage、OpenAPI 事件 schema 与 202/400 契约。
- P2.3：schema 观察与 drift 告警移入 sink 事务（拓扑不再访问 JPA），active leaf types 来自 Streams global store（compacted `schema-baselines-v1`）；V8 迁移增加 ACTIVE 部分唯一索引（升级时保留最早 ACTIVE、其余降级并写迁移记录）与 `baseline_outbox`；`SchemaObservationService` 用 advisory transaction lock，首个观察即 ACTIVE 并同事务写 outbox，NULL 不覆盖已确定的非空类型；`BaselineOutboxRelay` 在 broker ack 后才标 SENT，崩溃窗口内的 PENDING 行会被下一轮补发；`PUT /api/v1/schemas/{eventType}/baseline` 原子激活并降级旧 ACTIVE（不删除版本），响应报告 PUBLISHED/PENDING 而不是宣称 Streams 已应用。
- 期间修复：激活时先 flush 降级再提升（部分唯一索引要求）；测试清理按外键顺序删除；测试 profile 为每个上下文使用独立 Streams application id（消除并发 rebalance 造成的 readiness/落库抖动）。
- 证据：`.execution/verify/p2-gate5/`（126/0/0/0）。P3.1 管道切换后已在当前候选重跑：`.execution/verify/p2-gate6/` → PHASE-P2 PASSED，绑定 SHA 653a7e3（应用代码 = bb13ab9），126 tests / 0 fail / 0 err / 0 skip。

### 2026-10-01 P3.1 摄取身份与幂等投递（RUNNING，未过 G05）

已完成并全绿（126 tests / 0 fail / 0 err / 0 skip，SHA bb13ab9）：

- 管道切到 `raw-events-v1`（RawEnvelope）与 `quality-events-v1`（ProcessedEvent）；旧 topic 名保留仅用于 P3.3 bridge。
- `RawEventProducer` 以规范 scope key 发布并等待 broker ack（10s）；`IngestionService` 单条/批量摄取：Idempotency-Key receipt 先于发布在独立事务写入、同 key 不同内容 409、未确认返回 503 带重试提示、256KiB/100 条限制、逐条结果；`EventController` 保留 status/event_id 并追加 ingestion_id，OpenAPI 记录 202/400/409/503，page/size 有界。
- sink 消费 `quality-events-v1`，在同一事务内先查 processed receipt：同 ingestion_id 同摘要直接返回（不重复副作用），同 id 不同内容抛失败路径；告警写入 ingestion_id/detector_key/window_key（部分唯一约束）。
- V9 迁移：raw_events 增加 ingestion_id（旧行 legacy-db:<pk> 稳定身份）、origin/mode/window_evaluation/baseline_status、唯一索引；processed_receipts、ingestion_receipts、dead_letter_records（P3.2 用）。
- 期间修复：demo 场景与测试改走 envelope；生产者分区键断言改为规范 key（含防碰撞断言）；sink 单测 ObjectMapper 注册 JavaTimeModule。

G05 已通过（`.execution/verify/p3-gate1/`，SHA 45d18bce，132 tests / 0 fail / 0 skip），新增：
- `IdempotencyIntegrationTest`：丢失响应后同 key 重试得到同一身份且只有一个 raw row/receipt；同 key 改内容 409；两次业务重复保留两行并产生 DUPLICATE 证据；重投 ProcessedEvent 被 receipt 去重；并发同 key 得到单一身份。
- `PreAckFailureTest`：未确认发布返回 503 且保留身份，重试按保留身份重新发布并转 CONFIRMED。
- 测试暴露并修复：未确认 receipt 重试必须重新发布（原来直接返回"已接受"）；并发插入 receipt 冲突后改为读取获胜者继续；`ResponseStatusException` 保留 409/503 而不是被兜底成 500。
- `GET /api/v1/events/{ingestionId}` 返回该次摄取的 origin/mode、window_evaluation、baseline_status 与关联告警。

P3.1 之后仍需完成（下一动作）：

1. 新增 `IdempotencyIntegrationTest`（容器）覆盖 §11 的 G05 清单：确认前发布失败→503 且 receipt 保持未确认；确认后响应丢失→同 Idempotency-Key 重试得到同一 ingestion_id 且只有一次副作用；发布后重启→重放不重复；DB commit 后 offset 未 commit 的重投→receipt 去重且计数不变；并发同 key 请求→单一身份；两次业务重复（不同 ingestion_id 同 event_id）→保留两条并产生 DUPLICATE 证据。
2. 补 `GET /api/v1/events/{ingestionId}`（§4.5）返回该次摄取的 event、检测结果与 window_evaluation，并在 API 契约测试中断言。
3. 批量 4MiB 总大小限制与「重试只发送未确认项」的显式用例；随后运行 `verify.sh phase P3`（阶段脚本尚未创建）并把 G05 置为 PASSED。
4. 24 小时验收、P4-P7 均未开始；G05-G17 仍为 NOT_RUN。

### 2026-10-01 P3.2 重试、死信与运维重放（PASSED，G06）

- bytes 入口：`raw-events-v1` 以 bytes 消费并显式解析；坏记录或 `contract_version` 不受支持时进入 `dead-letter-events-v1`，携带原 topic/partition/offset 与稳定 diagnostic id，后续正常记录不受影响；解析边界使用全新 headers，避免外来 `__TypeId__` 污染死信 topic。
- 有限 sink 重试：立即、2s、10s、30s 共 4 次；持久失败后发布 DLT，只有 broker ack 后才跳过原记录；DLT 发布失败则抛错让 offset 不推进。成功计数与 WebSocket 广播移到提交之后；持久化拆到 `SinkPersistenceService` 使每次重试都是新事务。
- DLT 投影与管理：`dead_letter_records` 按 diagnostic id 幂等写入；`GET /api/v1/dead-letters`、`GET /{id}`、`POST /{id}/replay` 分别支持过滤、详情+恢复历史与重放；重放按失败阶段重新进入（SINK→quality-events-v1 保留原 ProcessedEvent；STREAM/SOURCE→raw-events-v1 保留原 envelope），每次返回新的 replay_attempt_id。
- 测试与现场证据：SinkRetryTest（重试/死信/DLT 发布失败/重复 receipt）与 DeadLetterIntegrationTest（坏记录、版本不受支持、幂等投影、SINK 与 STREAM 重放）；`verify.sh phase P3` PASSED（141/0/0/0，SHA b5c10c0a）。现场演练 `.execution/runs/p32-drill/`：60s Postgres 停机被重试吸收（raw=1、无死信、无半提交）；200s 停机后产生 1 条 SINK 死信，恢复后投影成功，重放后 raw=1、receipt=1、DLT=REPLAYED、open=0，重复重放不增加副作用（attempts=2）。
- 期间修复的真实缺陷：① 预约 Idempotency-Key 使用 merge 语义，并发下会覆盖获胜者的 receipt 并发放两个身份（改为严格 persist+flush，G05 并发用例由偶发失败转为稳定通过）；② Spring Kafka 默认错误处理在重试耗尽后跳过记录，会让 DB 不可用期间的事件与死信被静默丢弃（改为固定退避无限重试，Kafka 保持为持久恢复来源）；③ ERROR dispatch 被授权规则拒绝，导致真实错误以 401 呈现（改为放行 ERROR dispatch）。
- 说明：Hikari 连接超时 30s 使一次尝试本身可耗时约 30s，因此重试的实际覆盖窗口约 2 分钟；60s 级停机由重试吸收，更长停机走死信路径（两者均有实测）。

### 2026-10-01 P3.3 历史升级、桥接与回滚演练（PASSED，G07）

演练脚本 `.execution/scripts/p33-upgrade-drill.sh`，只使用验收项目 `dwt-p37` 与其自有卷、端口 18081/18082；证据目录 `.execution/runs/p33-upgrade/`。

- 旧版本夹具：从 base commit `082fd84` 构建 `driftwatch-tower:legacy`（真实重构前镜像），在旧 API 上摄取 5 条 legacy 事件；记录 `raw-events` 每分区 end offset（0:5,1:0,2:0）与 `flyway_schema_history` 中 V1-V7 的 checksum。
- 备份与升级：`pg_dump -Fc` 备份旧库；新镜像挂同一库/卷启动 → 应用 V8-V10（migrations=10），V1-V7 checksum 与升级前完全一致（旧迁移未被改动）。
- 历史保留：升级后 raw_events 仍为 5 行，全部带 `legacy-db:<pk>` 稳定身份，新 API `GET /api/v1/events/recent` 仍可查询；未 purge 任何旧数据或告警。
- 真实 backlog 桥接：停机期间向旧 `raw-events` 追加 2 条记录（offset 0:5-6），运行一次性 `LegacyBridge`（配置门控、报告写盘）→ 2 条以 `legacy-kafka:raw-events:0:5/6` 派生稳定身份进入新管道；重复运行不会重复副作用（同一 offset 恒等同一 ingestion_id）。
- 回滚演练：① 仅回滚镜像（旧镜像对新 schema）→ Flyway 报错 4 条，证明「只回滚镜像」被禁止；② 把备份恢复到新数据库并启动旧镜像 → readiness 正常、旧数据可查（restored_rows=5）。演练卷已清理，未触碰用户卷。

### 2026-10-01 P4.1 / P4.2 GitHub 真实来源（PASSED，G08 + G09）

- P4.1 实现（SHA fbba4d05）：`GithubEventsClient` 使用契约头（Accept、固定 X-GitHub-Api-Version、User-Agent、可选 token、If-None-Match）、5s 连接/可配请求超时，区分 200/304/401/403/404/429/5xx/超时/坏 JSON；`GithubEventConverter` 只保留 repository/type/actor/public 与类型专属结构字段、UNKNOWN 保留、缺字段显式 null、保留原记录摘要哈希；`GithubPoller` 单租约 + READY/FETCHING/STAGED/PUBLISHING/APPLIED（BACKOFF/ERROR）、每轮单事务写 inbox+ingestion_id+outbox、relay 20 条一批等 broker ack、candidate ETag 只在所有记录有 receipt 或终态死信后才提升为 applied、缺口记录（截断/失去重叠/停机超出可见范围/预算耗尽，缺失数量记 unknown）、304 只更新 poll 健康且不推进游标、403/429 遵守 Retry-After 或 rate-limit reset、5xx/超时 5s–5min 指数退避；V11 新增 5 张状态表；非官方 base URL 在生产配置下启动失败。
- G08（`.execution/verify/p4-gate1/`，152 tests / 0 fail / 0 err / 0 skip）：本地 stub 覆盖 304 游标不动、403+Retry-After 退避并记 BUDGET_EXHAUSTED 缺口、429 指数退避、401/404 进入 ERROR 且不回显 token、500 后退避并在下一轮恢复、8s 慢响应超时、坏 JSON 失败且下一轮可用、跨页重叠不重复入库、重复轮询不新增、BOOTSTRAP→LIVE 模式记录、重启恢复（失败轮不推进检查点）。
- G09（`.execution/runs/p42-smoke/`）：官方 `api.github.com` 无 token 只读证据（ETag、x-poll-interval、rate-limit 头）；bootstrap 轮 mode=BOOTSTRAP 摄取 199 条真实事件（窗口评估 SKIPPED_MODE），outbox 199 条全部 SENT；抽样事件 16156955211 全链路可查（raw origin=GITHUB、receipt、`GET /api/v1/events/{ingestionId}`）；重启后 inbox 仍 199、etag_applied 保持、无重复摄取；随后轮询发现启动后新增事件 16162901734（created 06:02:59Z，mode=LIVE，run #4 LIVE records_seen=199/new=1 APPLIED），raw 行 source=github:apache/kafka；QUIET 轮（304）证明无变化时不推进游标。速率预算按 PT2M 轮询（约 30 次/小时）低于未认证 60 次/小时上限；生产默认仍为 5 分钟。
- 观察：BOOTSTRAP 事件按契约不参与实时窗口，因此其 baseline_status 为空（未执行基线检查），LIVE 事件才带 APPLIED/PENDING。

### 2026-10-01 P5.1 incident、定时健康与采集器状态（PASSED，G10）

- incident 关联（SHA 4b111417）：`AlertIncidentService` 只对非 INFO 告警按 source/event_type 关联最近 5 分钟内的 OPEN incident，使用 PostgreSQL advisory transaction lock 串行化同 scope 关联（并发告警只产生一个 incident、每条告警只关联一次）；INFO 告警只留在 alerts 以免重复提示制造 incident 洪水；sink 在同一事务内关联它持久化的告警。
- 生命周期：`resolveIncident` 事务内解决其全部未解决告警；`resolveAlert` 在最后一条告警解决时自动解决 incident；`acknowledgeAlert` 幂等（重复保持原时间），对已解决告警 acknowledge 返回 409；控制器改走服务层。
- 定时健康：`SourceHealthService.list()/get()` 恢复为纯读（GET 不再刷新、不再产生告警）；新增 30s `SourceHealthScheduler`（可注入 Clock）执行刷新，静默来源在没有 Dashboard 流量时仍产生 STALE 转换；sink 只刷新受影响来源（不再每次事件全表扫描）；转换告警每次失联仅一次、恢复后可再次告警（单元测试覆盖）。
- 采集器状态：`GET /api/v1/sources/collectors` 返回 last_poll_at/last_success_at/next_poll_at/last_event_at/upstream_lag_seconds/lost/backoff_until/etag_applied/pending_outbox/open_gaps/last_error；`lost` 仅在 last_poll_success 超过 max(15 分钟, 2×poll interval) 时为真，成功但无新事件为 QUIET（依据 last_event_at 与 last_poll_success 关系），BACKOFF/ERROR 单独呈现。
- 测试：`SourceHealthSchedulerTest`（每次失联一次告警、恢复后二次告警、读无副作用）、`IncidentLifecycleIntegrationTest`（关联、并发唯一、resolve/自动解决、ack 幂等与 409、health/dashboard/alerts 反复 GET 不新增行）、`CollectorStatusServiceTest`（QUIET/LOST 阈值/积压与缺口）；旧 `SourceHealthServiceTest` 更新为纯读契约。

### 2026-10-01 P5.2 指标、保留与备份恢复（PASSED，G11）

- 指标（§7.3，SHA 6bf026de）：`DriftwatchMetrics` 注册并接线到真实路径——摄取 ack 时长/失败、处理时长（received_at→commit）/失败、采集器 polls/failures/upstream lag、source outbox/dead-letter/baseline-outbox 积压、按 detector+severity 的告警计数、retention 清理行数与最后成功时间；标签只含 detector/severity/outcome/reason 等有界值，事件与摄取身份从不作为标签（集成测试与现场 scrape 双重断言 `event_id=`/`ingestion_id=` 不出现）。
- 保留（§7.4）：`RetentionService` 每日执行、每批 ≤1000 行；raw/metric 30 天、已解决告警/incident 与已完成死信 90 天、inbox 身份 35 天；未解决告警/incident、未恢复死信、pending source/baseline outbox、collector 状态与 schema 版本永不清理；processed receipt 只在其 raw 行已不存在时才清理（不会先删 receipt 再重放 raw）；`GET /api/v1/operations/retention` 暴露设置与受保护状态计数，`POST .../retention/run` 供演练使用。
- 备份/恢复演练（`.execution/runs/p52-drill/`）：真实数据（4 raw、1 alert、1 schema、4 receipts）→ `pg_dump -Fc`（57KB）→ 恢复到**全新专用卷**（独立项目与卷）→ 计数完全一致 `counts_match=YES`、关系校验 `receipts_without_raw=0`；随后在源库运行 retention（API + CSRF），`open_alerts_before=1 after=1` 证明受保护证据未被清理；Prometheus scrape 中 7 个必需指标全部存在且无身份标签。演练卷与网络已清理，未触碰用户卷。
- 测试：`OperationsIntegrationTest`（保留规则：受保护证据、orphan receipt、batch 设置、指标注册与标签基数）；`phase-P5b.sh` 门禁（165 tests / 0 fail / 0 err / 0 skip，OperationsIntegrationTest 2 + IncidentLifecycleIntegrationTest 6 + DeadLetterIntegrationTest 5 均实际运行）。
- 说明：Micrometer 的 Prometheus 注册表会去掉 gauge 名的 `_total` 后缀，指标名已按实际导出名统一为 `driftwatch_retention_rows_pruned`。

### 2026-10-01 P5.3 起步：真实 before 截图与缺陷清单（RUNNING）

- 工具：`package.json` 固定 `playwright-core`（浏览器来自本机 Playwright 缓存，无下载、无外部 CDN 依赖）；`scripts/p53-capture.mjs` 用显式 Basic 头驱动 headless Chromium，逐断点（320/768/1024/1440）截图并记录 console 错误、页面级横向溢出与键盘 focus 探针，保证 before/after 来自同一真实场景。
- 场景：compose `dwt-p53`（selfhost，18080）经摄取 API 与 mixed-incident 演示产生 227 events / 339 alerts；证据 `.execution/runs/p53-before/`（4 张 dark 截图 + `before-report.json` + `findings.md`）。
- 实测缺陷（after 必须修复，写入 `findings.md`）：① Dashboard 的 WebSocket 因无法携带 Basic 凭据而连接失败（浏览器不会为 WS 握手重放凭据）；② 字体来自 fonts.gstatic.com 外部 CDN；③ 页面内一次调用返回 403（CSRF 流程未走通）；④ 320/768 出现整页横向溢出；⑤ focus 仅浏览器默认 1px auto；⑥ 尚无浅色主题。
- 下一步：实现第 7.5 节页面与操作状态（loading/禁用重复提交/成功失败反馈/retry、401 可理解认证态、WS 退避重连并重新查询）、本地图标（Lucide 本地打包）与本地字体/系统 fallback、浅色 token、窄屏表格横向滚动容器、可见 focus，然后跑 `scripts/p53-capture.mjs --label after`（含 light）并对照 before 出 G12 证据。

后续每条保留：

- UTC 时间、任务、绑定 SHA、实际命令、退出码、结果、证据相对路径。
- 失败原因和下一动作；旧失败不覆盖成新通过。
- 外部阻塞需要说明失败动作/原始错误、可继续任务和精确恢复命令。
- 用户暂停或额度耗尽记录恢复状态，不改成产品完成。
- PR、Release、image@digest 和最终安装证据在真实出现后填写，不预填假 URL。

## 文档交付核验

2026-09-30 文档交付核验通过：

- 5 个活动 Markdown 文件中的 19 个本地文件链接均存在。
- 7 段 Bash 命令块通过 bash -n；代码围栏闭合，文件结尾和空白检查通过。
- 指南与状态中的 21 个任务、18 个 Gate 一一对应。
- 批准删除的 7 个目标均已删除，活动文档没有指向它们的链接或旧计划引用。
- 保留的架构 SVG 是有效 XML；它同步了经核对远端文档中的正确箭头和标签。
- git diff --check 通过；变更范围为 README、样例说明、docs 和批准删除的旧计划。业务代码、pom、Docker/Compose、CI、旧迁移没有变化。
- 本轮没有执行产品重构、运行24小时任务、提交/推送或发布；产品任务仍是 NOT_STARTED，门禁仍是 NOT_RUN。

这些结果只证明文档交付，不证明未来产品门槛通过。后续运行结果在新的任务记录中追加，不改写这份交接核验。
