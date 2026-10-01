# DriftWatch Tower 执行状态

本文件只记录事实，不另行定义范围。[执行指南](PROJECT_EXECUTION_GUIDE.md)是唯一规范，[启动提示词](AGENT_REFACTOR_PROMPT.md)交给接手 Agent。

## 当前入口

| 字段 | 当前值 |
|---|---|
| document_revision | 1.0 |
| handoff_date | 2026-09-30，America/Los_Angeles |
| product_goal_status | RUNNING，P0 与 P1.1 完成，P1.2 进行中 |
| current_phase | P1 |
| current_task | P1.2 |
| next_action | 集中 @ConfigurationProperties、凭据/访问保护（Spring Security 基础）、生产与测试 profile |
| local_baseline_sha | 84400133d9aab140e6e7d8bd34550c178c89a69a（历史本地基线） |
| remote_snapshot_sha | 082fd84d7fabee7d94e05b4dba842f0995a3775e，2026-09-30 执行时经 git fetch 重新核验，即为 execution_base_sha |
| execution_branch | codex/release-v1（本地；尚未推送） |
| execution_base_sha | 082fd84d7fabee7d94e05b4dba842f0995a3775e |
| handoff_commit_sha | 0a2bb07b45fb44576a5a6e909fdf836e6557e14c（文档交接 rebase 到 origin/main） |
| original_worktree_backup_ref | backup/handoff-worktree-20260930 -> 1967034bda95b135a939bc34f4a9d7e3b5949b68（rebase 前的交接提交，含全部未提交变更） |
| candidate_sha / source_tree_hash | 未冻结；开发分支推进中（P0.2/P1.1 已提交） |
| candidate_image_id / public_digest | 未冻结；P1.1 本地镜像 sha256:1d84b418…（仅本地验证） |
| target_release | v1.0.0；2026-09-30 核验远端仅有 tag v0.1.0，无冲突 |
| docs_delivery_status | VERIFIED，本轮文档交付核验通过，且 rebase 后内容逐字节一致 |
| release_authorization | 用户已授权接手 Agent 提交、推送、合并自己的 PR、公开 Release/GHCR |
| application_changes_in_handoff | 无业务代码、依赖、配置、CI、迁移改动 |
| active_soak_run | 无；24 小时验收尚未启动 |
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
| P1.2 | 配置和认证基础 | RUNNING | - |
| P1.3 | 验证脚本及后台 runner | NOT_STARTED | 指南命令目前待实现 |
| P2.1 | scope / 窗口 / 漏报修复 | NOT_STARTED | - |
| P2.2 | 规则与配置覆盖 | NOT_STARTED | - |
| P2.3 | schema 事务及基线反馈 | NOT_STARTED | - |
| P3.1 | 摄取确认、envelope、幂等 | NOT_STARTED | - |
| P3.2 | retry / DLT / replay | NOT_STARTED | - |
| P3.3 | 历史升级与回滚演练 | NOT_STARTED | - |
| P4.1 | GitHub 持久 poller | NOT_STARTED | - |
| P4.2 | 官方真实数据全链路 | NOT_STARTED | - |
| P5.1 | incident / scheduler | NOT_STARTED | - |
| P5.2 | 指标、保留、备份恢复 | NOT_STARTED | - |
| P5.3 | Dashboard 操作与响应式 | NOT_STARTED | 设计 3/2/8 已确认 |
| P6.1 | 冻结候选、短门槛、负载 | NOT_STARTED | - |
| P6.2 | 24 小时真实验收 | NOT_STARTED | - |
| P7.1 | 合并自己的重构 PR | NOT_STARTED | - |
| P7.2 | 公共 Release / GHCR | NOT_STARTED | - |
| P7.3 | 匿名安装及最终报告 | NOT_STARTED | - |

## 门禁状态

NOT_RUN不是PASSED。EXPECTED_FAILURE仅允许G01旧版本的已知回归；修复后G03必须通过。

| Gate | 状态 | 绑定 SHA / 配置 / 证据 |
|---|---|---|
| G00 基线 | PASSED | Java 21.0.12.1 + Docker 29.5.3；60 tests / 0 fail / 0 err / 0 skip；`.execution/runs/20261001T020542Z-p02-baseline/` |
| G01 红色回归复现 | EXPECTED_FAILURE | 3 个 5.4 用例在旧实现复现（null 全缺失 / 单事件基线突增 / 乱序覆盖窗口）；`g01-red-regression.log`、`g01-cases.json` |
| G02 全新 Compose | PASSED | 无缓存拉取+构建；容器启动后 6–19s 内全部 healthy（≤120s）；3 分区 topic、Streams changelog、状态卷、仅回环暴露；`runs/20261001T022637Z-p11-g02/` |
| G03 检测正确性 | NOT_RUN | - |
| G04 schema 反馈 | NOT_RUN | - |
| G05 摄取与幂等 | NOT_RUN | - |
| G06 故障与死信 | NOT_RUN | - |
| G07 升级兼容 | NOT_RUN | - |
| G08 来源协议 | NOT_RUN | - |
| G09 官方真实源 | NOT_RUN | - |
| G10 操作闭环 | NOT_RUN | - |
| G11 保留与恢复 | NOT_RUN | - |
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
