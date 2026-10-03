# Historical execution tables before finalization alignment

Archived on 2026-10-01. These superseded tables are retained verbatim as history; do not execute their old run/recovery commands. Current entry: [EXECUTION_STATE.md](EXECUTION_STATE.md).

## 当前入口

| 字段 | 当前值 |
|---|---|
| document_revision | 1.1 |
| handoff_date | 2026-10-01，本聊天接管，America/Los_Angeles |
| product_goal_status | RUNNING，P0-P6.1 完成，P6.2 进行中：候选 573154b9（适配器字段路径 + 320px 布局修复）上 UNIT/P2/P3/P4/P5/P5b/P5c/P6/load/package **全部 PASSED**，第九个 24 小时窗口正在运行 |
| current_phase | P6 |
| current_task | P6.2 |
| next_action | 监控 run `20261001T182403Z-soak24`（2h/8h/16h 三次计划故障，结束时 `./scripts/verify.sh soak-report --run-id 20261001T182403Z-soak24 --out .execution/verify/p7-soak` 判定 G16），通过后按「收尾程序」合并 PR #1、发布 Release/GHCR 并匿名核验 |
| local_baseline_sha | 84400133d9aab140e6e7d8bd34550c178c89a69a（历史本地基线） |
| remote_snapshot_sha | 082fd84d7fabee7d94e05b4dba842f0995a3775e，2026-09-30 执行时经 git fetch 重新核验 |
| execution_branch | codex/release-v1（已推送到 origin；PR #1 已开） |
| execution_base_sha | 082fd84d7fabee7d94e05b4dba842f0995a3775e |
| handoff_commit_sha | 0a2bb07b45fb44576a5a6e909fdf836e6557e14c（文档交接 rebase 到 origin/main） |
| original_worktree_backup_ref | backup/handoff-worktree-20260930 -> 1967034bda95b135a939bc34f4a9d7e3b5949b68（rebase 前的交接提交，含全部未提交变更） |
| candidate_sha | 573154b9（应用面；GitHub 适配器字段路径 + 320px 布局两处修复后冻结，全部受影响门禁已在其上重跑 PASSED） |
| source_tree_hash | bb6d14e7c7c02ed0f3aa26073c206f99976aa0ec493440b72a0d9a013b1483b7（见 `.execution/runs/p7-freeze/manifest.json`；204 文件） |
| config_hash | 7457349dd3f231585251cf832909aacebe71c3d4b9e6ccf08aa5b0d65ab9a659 |
| candidate_image_id / public_digest | 本地镜像 sha256:2901be88df52de693b892825700ab8101fd72efb7bb944ad639b6018ad77d0d3（未发布；`content_identity.jar_content_hash` = 1070909c890c03cb64031949ff500d1b107fae53147e82c5d8afd6016e7d4c8d；发布断言使用该值） |
| target_release | v1.0.0；2026-09-30 核验远端仅有 tag v0.1.0，无冲突 |
| docs_delivery_status | VERIFIED，本轮文档交付核验通过，且 rebase 后内容逐字节一致 |
| release_authorization | 用户已授权接手 Agent 提交、推送、合并自己的 PR、公开 Release/GHCR |
| application_changes_in_handoff | 无业务代码、依赖、配置、CI、迁移改动 |
| active_soak_run | **`20261001T182403Z-soak24`（RUNNING）**：2026-10-01T18:24:03Z 启动，PID 5002（`caffeinate -i -w 5002`），86400s，预计 **2026-10-02T18:24:03Z** 结束；`state.json` 记录 `git_sha=20f948be`、镜像 `sha256:2901be88…`（= 冻结候选），app 容器同一镜像且 healthy；故障计划 2h app-restart / 8h kafka-stop / 16h db-stop；启动后 bootstrap 轮已入库真实事件、readiness 200。其余历史 run（含 20261001T145553Z-soak24）均 FAILED，仅作证据保留 |
| external_blocker | 尚未实际阻塞。首次 GHCR 推送后需在 GitHub 包设置将包设为 Public，再核验匿名 digest 拉取；官方界面动作由本聊天接管。不存在 visibility PATCH API；无须为该无效操作申请 PAT。 |

> **接管规则**：旧 ZCode 自动化已暂停。本聊天 heartbeat 以执行计划和 release context 为准；同时验证 PID、创建时间、命令、采样更新、项目与镜像。不得根据旧投递文字或单个 RUNNING 状态启动第二个窗口。

> **陷阱警告（防止误记）**：旧 run `20261001T145553Z-soak24` 的 `faults.jsonl` 里**已有一条 completed 的 2h 故障**（它死于字段路径缺陷前的自身计划）。它是**旧窗口**的证据，**不能**用来给当前窗口做「重启恢复对比」。当前窗口的三次故障时间：2h → **2026-10-01T20:24:03Z**、8h → **2026-10-02T02:24:03Z**、16h → **2026-10-02T10:24:03Z**；只有在 `.execution/soak/20261001T182403Z-soak24/faults.jsonl` 里看到对应故障 `completed` 之后，才可用 `scripts/poller-state.sh --project dwt-soak --env-file .execution/soak.env` 与 `.execution/verify/p61-soak-prefault-baseline.json`（已更新为本窗口的故障前基线：BOOTSTRAP 1 / 96 事件 / inbox 96 / failures 0）做对比，并把**前后两组数字**写进状态文件。在 20:24:03Z 之前，当前窗口**没有任何**故障证据，不得记录任何「故障后」数字。

文档交付不等于 P0/P7 完成。接手 Agent 不要把本文件的历史审核结果移入新候选的 PASSED 门禁。


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
| P5.3 | Dashboard 操作与响应式 | PASSED | G12 PASSED（8/8 页面，0 console 错误、无溢出、2px focus、socket connected）；before `.execution/runs/p53-before/`、after `.execution/verify/p5c-gate6/` |
| P6.1 | 冻结候选、短门槛、负载 | PASSED | G13/G14/G15 PASSED；冻结 SHA 791c75f、镜像 sha256:4f064add…；100/s×1800s 全部指标达标；见 `.execution/runs/p61-freeze/`、`.execution/verify/p61-load2/`、`.execution/verify/p61-package2/` |
| P6.2 | 24 小时真实验收 | RUNNING | 第 7 个 run `20261001T145553Z-soak24` 已 FAILED（字段路径缺陷使检测证据无效，2h 故障与重启恢复对比有效并保留）；新候选 `e8315ac9` 的门禁复跑进行中，完成后自动启动 run 8 |
| P7.1 | 合并自己的重构 PR | NOT_STARTED | - |
| P7.2 | 公共 Release / GHCR | NOT_STARTED | - |
| P7.3 | 匿名安装及最终报告 | NOT_STARTED | - |

## 门禁状态

NOT_RUN不是PASSED。EXPECTED_FAILURE仅允许G01旧版本的已知回归；修复后G03必须通过。

| Gate | 状态 | 绑定 SHA / 配置 / 证据 |
|---|---|---|
| G00 基线 | PASSED（已随候选 791c75f 重跑） | Java 21.0.12.1 + Docker 29.5.3；165 tests / 0 fail / 0 err / 0 skip；`.execution/verify/p61-unit2/` |
| G01 红色回归复现 | EXPECTED_FAILURE | 3 个 5.4 用例在旧实现复现（null 全缺失 / 单事件基线突增 / 乱序覆盖窗口）；`g01-red-regression.log`、`g01-cases.json` |
| G02 Compose | NOT_STARTED | 历史 p61-compose10 属旧应用；G16 后冻结镜像全新项目/卷重跑 `.execution/verify/final-compose` |
| G03 检测正确性 | PASSED（已随候选 791c75f 重跑） | UNIT gate 165 tests / 0 fail / 0 err / 0 skip，含 DetectionContractTest 21、QualityStreamsTopologyTest 7；`.execution/verify/p61-unit2/` |
| G04 schema 反馈 | PASSED（已随候选 791c75f 重跑） | UNIT gate 165 tests / 0 fail / 0 err / 0 skip，SchemaTransactionIntegrationTest 4 项实际运行；`.execution/verify/p61-unit2/` |
| G05 摄取与幂等 | PASSED（最新 PHASE-P3 证据 7217ff67 已覆盖其用例） | SHA 45d18bce；PHASE-P3 gate：132 tests / 0 fail / 0 skip，IdempotencyIntegrationTest 5 + PreAckFailureTest 1 + KafkaIngestionIntegrationTest 1；`.execution/verify/p3-gate1/` |
| G06 故障与死信 | PASSED（最新 PHASE-P3 证据 7217ff67 已覆盖其用例） | SHA b5c10c0a；PHASE-P3 gate 141 tests / 0 fail / 0 skip（DeadLetterIntegrationTest 5、SinkRetryTest 4）；现场演练：60s 停机被重试吸收且无死信、200s 停机产生死信并在恢复后投影与重放（各一次副作用）；`.execution/runs/p32-drill/` |
| G07 升级兼容 | PASSED | SHA 7217ff67；PHASE-P3 gate 141 tests / 0 fail / 0 skip；演练：V1-V7 checksum 不变、5 条旧行保留且可查（legacy-db 身份）、2 条真实 backlog 桥接（稳定 legacy-kafka 身份）、仅回滚镜像被 Flyway 拒绝而「备份恢复 + 旧镜像」可用；`.execution/runs/p33-upgrade/` |
| G08 来源协议 | PASSED | SHA fbba4d05；PHASE-P4 gate 152 tests / 0 fail / 0 skip；GithubPollerIntegrationTest 11 项覆盖 304/403+Retry-After/429/401/404/500+恢复/超时/坏 JSON/跨页重叠/bootstrap 与 live 模式/重启恢复；`.execution/verify/p4-gate1/` |
| G09 官方真实源 | PASSED | 官方 api.github.com 无 token；bootstrap 199 条真实事件（mode=BOOTSTRAP、SKIPPED_MODE）、启动后新增 16162901734（mode=LIVE）经 inbox→outbox→Kafka→receipt→raw→API 全链路、重启后检查点保持不重摄；`.execution/runs/p42-smoke/` |
| G10 操作闭环 | PASSED | SHA 4b111417；PHASE-P5 gate 163 tests / 0 fail / 0 skip；incident 关联并发唯一、resolve 语义与自动解决、ack 幂等/409、静默 scheduler 单次转换、GET 无副作用；`.execution/verify/p5-gate3/` |
| G11 保留与恢复 | PASSED | SHA 6bf026de；PHASE-P5b gate 165 tests / 0 fail / 0 skip；pg_dump → 全新专用卷恢复计数一致（raw=4/alerts=1/schema=1/receipts=4）、保留策略保护未解决证据、指标面无身份标签；`.execution/runs/p52-drill/` |
| G12 浏览器与四断点 | PASSED（已随候选 791c75f 重跑） | SHA 791c75f；PHASE-P5c gate：320/768/1024/1440 × dark/light 共 8 张真实截图全部 200、0 console 错误、无页面级横向溢出、focus outline 2px、WebSocket state=connected（ticket 握手）；`.execution/verify/p61-browser2/`（after-report.json + g12-summary.json），P5.3 对照 `.execution/verify/p5c-gate6/`、before `.execution/runs/p53-before/` |
| G13 安全与漏洞 | PASSED | SHA 791c75f；Trivy 0.58.1，DB UpdatedAt 2026-10-01T01:24:14Z；依赖树与运行时镜像 0 HIGH/CRITICAL，镜像与 tracked 树 0 secret，镜像内无凭证文件；`.execution/verify/p61-g13c/` |
| G14 100/s、30分钟 | PASSED | SHA 791c75f（镜像 sha256:4f064add…）；180000/180000 offer 于 1800.0s 内发出（100.0/s），accepted 180000、failed 0；ack p95 12.4ms（≤1s，p99 43ms）、commit p95 134ms（≤5s，180000 样本，直方图差分）；账本 180100 accepted = 180100 processed = 180100 raw、0 未确认、0 DLT、0 孤儿；三组 consumer lag 归零用时 31.3s；资源曲线 263 点/容器（app 峰值 864MiB、pg 280MiB、kafka 1015MiB）；`.execution/verify/p61-load2/` |
| G15 制品安装 | RUNNING | 当前应用 p7-package2 有历史通过证据；收尾工具/文档更新后须重生成并验收 final-package 的原始 bundle 字节 |
| G16 24h | RUNNING | 有效 run 20261001T182403Z-soak24；正式报告 `.execution/verify/final-soak/soak-report.json` 尚未生成 |
| G17 公开独立安装 | NOT_STARTED | `.execution/verify/final-release`，必须使用明确 context、匿名附件及公开 digest |

## 长任务与恢复字段

24 小时 run 进行中（P6.2）。已作废的 run 保留为历史，不作证据。

| 字段 | 值 |
|---|---|
| run_id / run_dir | 20261001T145553Z-soak24 / `.execution/soak/20261001T145553Z-soak24/`（作废的候选 run：20261001T083508Z-soak24、20261001T093502Z-resume、20261001T093737Z-soak24、20261001T103023Z-soak24、20261001T114957Z-soak24、20261001T124728Z-soak24（遗留 runner，2026-10-01T14:49Z 已停止并标 FAILED）、20261001T134438Z-soak24（被 124728Z 的非计划 app 重启打断，2026-10-01T14:52Z 停止并标 FAILED）；`.execution/soak/p13-*` 与 20261001T03* 是 P1.3 runner 测试夹具，非验收 run，其中 p13-resume2 的 state 仍写 RUNNING 是当时故意 kill runner 的测试遗留，`ps` 已确认当前只有 1 个 acceptance.py runner 进程） |
| compose_project / volume 所有权 | dwt-soak（自有卷 dwt-soak_pgdata、dwt-soak_kafkadata、dwt-soak_streams-state） |
| env_file 路径 | `.execution/soak.env`（0600，仅路径，不含 secret 内容） |
| candidate_sha / image_id / config_hash | 573154b9（应用面）/ sha256:2901be88df52de69…（完整值见 `.execution/runs/p7-freeze/manifest.json`）/ 7457349d… |
| started_at_utc / expected_end_at_utc | 2026-10-01T14:55:53Z / 2026-10-02T14:55:53Z |
| runner_pid / process_start / lock | PID 42061（runner.pid 记录进程创建时间；`runner_alive` 校验命令行与创建时间；`caffeinate -i -w 42061` 绑定其生命周期防休眠） |
| last_heartbeat_utc / checkpoint | samples.jsonl 每 30s 一行；checkpoint.json 每 5 分钟原子写 |
| live_unique_events / new_after_bootstrap | 待结束后由 `soak-report` 从 raw_events(origin=GITHUB) 统计 |
| source_poll 状态 / outbox / DLT / lag | 待结束后统计（要求全部归零） |
| planned_faults / completed_faults | 3 个计划（2h app-restart、8h kafka-stop、16h db-stop），已完成 0 |
| monitor_gap / continuity_valid | 待判定（上限 120s） |
| exact_resume_command | `./scripts/verify.sh soak-status --run-id 20261001T145553Z-soak24`；runner 失联时 `./scripts/verify.sh soak-resume --run-id 20261001T145553Z-soak24`（标记旧 run FAILED 并以全新 24 小时重启，沿用原 project/env-file/fault-plan；重启后记得重新 `caffeinate -i -w <新 PID>`） |
| last_failure / required_external_action | 2026-10-01T14:47:55Z 遗留 runner（20261001T124728Z-soak24，旧候选）向本窗口注入非计划 app 重启 → 已停止该 runner 并把两个 run 标 FAILED、重开完整窗口；无外部阻塞。等待到 2026-10-02T14:55:53Z |

恢复顺序：读状态 -> 核对 checkout/SHA -> 查原 runner 锁和进程身份 -> 验证采样连续性 -> 继续现有任务或保留失败记录并新建 run。不能看到 PID 就启动第二套。

