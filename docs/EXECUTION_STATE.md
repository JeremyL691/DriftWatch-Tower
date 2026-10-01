# DriftWatch Tower 执行状态

本文件只记录事实，不另行定义范围。[执行指南](PROJECT_EXECUTION_GUIDE.md)是唯一规范，[启动提示词](AGENT_REFACTOR_PROMPT.md)交给接手 Agent。

## 当前入口

| 字段 | 当前值 |
|---|---|
| document_revision | 1.0 |
| handoff_date | 2026-09-30，America/Los_Angeles |
| product_goal_status | RUNNING，P0-P6.1 完成（G00-G15 在候选 8a798a6e 上全部 PASSED），P6.2 进行中 |
| current_phase | P6 |
| current_task | P6.2 |
| next_action | P6.2：等待 run 20261001T145553Z-soak24 结束（2026-10-02T14:55:53Z），然后按下方「收尾程序」执行 G16 判定与 P7 发布 |
| local_baseline_sha | 84400133d9aab140e6e7d8bd34550c178c89a69a（历史本地基线） |
| remote_snapshot_sha | 082fd84d7fabee7d94e05b4dba842f0995a3775e，2026-09-30 执行时经 git fetch 重新核验 |
| execution_branch | codex/release-v1（已推送到 origin；PR #1 已开） |
| execution_base_sha | 082fd84d7fabee7d94e05b4dba842f0995a3775e |
| handoff_commit_sha | 0a2bb07b45fb44576a5a6e909fdf836e6557e14c（文档交接 rebase 到 origin/main） |
| original_worktree_backup_ref | backup/handoff-worktree-20260930 -> 1967034bda95b135a939bc34f4a9d7e3b5949b68（rebase 前的交接提交，含全部未提交变更） |
| candidate_sha | 8a798a6e（应用面；P6.1 冻结并全部门禁通过） |
| source_tree_hash | d5ca0f55ab8689824e5a4e50919ca7f1ca90fae123d9f96f56cb935023fd7370（完整值见 `.execution/runs/p61-freeze/manifest.json`；src+pom+Dockerfile+compose+.mvn，工具链单独记 tooling_tree_hash）。2026-10-01T15:00Z 用同一算法在当前工作树重算一致（203 文件），且 `git diff 8a798a6e..HEAD -- src pom.xml Dockerfile docker-compose.yml docker-compose.dev.yml .mvn` 为空——冻结后所有提交只动 `.github/workflows`、`docs`、`scripts` |
| config_hash | 7457349dd3f231585251cf832909aacebe71c3d4b9e6ccf08aa5b0d65ab9a659 |
| candidate_image_id / public_digest | 本地镜像 sha256:aeb1c9f9ccd68ca352bd2a6cb93751203e3cbd9f78c14db15e175b87c178ce83（未发布；`content_identity.jar_content_hash` = e574ffcfddbf3e3dd75728b4181b8e5f1eb0f952a36b6e5d6dff86ee424c9d4a，已实测可由同源重建复现） |
| target_release | v1.0.0；2026-09-30 核验远端仅有 tag v0.1.0，无冲突 |
| docs_delivery_status | VERIFIED，本轮文档交付核验通过，且 rebase 后内容逐字节一致 |
| release_authorization | 用户已授权接手 Agent 提交、推送、合并自己的 PR、公开 Release/GHCR |
| application_changes_in_handoff | 无业务代码、依赖、配置、CI、迁移改动 |
| active_soak_run | 20261001T145553Z-soak24（RUNNING，PID 42061，dwt-soak，18087，86400s，2026-10-01T14:55:53Z 起，预计 2026-10-02T14:55:53Z 结束；`caffeinate -i -w 42061` 持有防休眠断言）。这是第 7 个 run：第 6 个 run 20261001T134438Z-soak24 因环境干扰在第 1.06 小时作废（见下方 2026-10-01 事件记录），未拼接 |
| external_blocker | 无 |

文档交付不等于 P0/P7 完成。接手 Agent 不要把本文件的历史审核结果移入新候选的 PASSED 门禁。

## 收尾程序（G16 之后，按序执行，勿跳步）

以下值取自 `.execution/runs/p61-freeze/manifest.json` 与当前 run，勿手抄：

- candidate（应用面）: `8a798a6e7396f2a267cc26e1519bf987c7f13ff3`
- 镜像（本地，未发布）: `sha256:aeb1c9f9ccd68ca352bd2a6cb93751203e3cbd9f78c14db15e175b87c178ce83`
- `content_identity.jar_content_hash`: `e574ffcfddbf3e3dd75728b4181b8e5f1eb0f952a36b6e5d6dff86ee424c9d4a`
- 当前 24 小时 run: `20261001T145553Z-soak24`（2026-10-01T14:55:53Z 起，预计 2026-10-02T14:55:53Z 结束；第 6 个 run 20261001T134438Z-soak24 因环境干扰作废，见事件记录）
- 最终制品目录: `.execution/verify/p61-package-final3/artifacts`

1. `./scripts/verify.sh soak-report --run-id 20261001T145553Z-soak24 --out .execution/verify/p61-soak` → 判定 G16 并写出 `gate.json`(SOAK)。有问题就记录并修复后重开完整 24 小时，不拼接。
2. 通过后更新本文件（G16/P6.2 PASSED、实测数字与证据路径），提交并推送 `codex/release-v1`。
3. 把阶段门禁重新绑定到冻结候选：`for ph in P2 P3 P4 P5 P5b; do ./scripts/verify.sh phase $ph --project dwt-soak --env-file .execution/soak.env --out .execution/verify/p7-$ph; done`（每个脚本跑一次完整套件，约 35 分钟；只依赖 Docker，不需要运行中的栈）。理由：指南 §11.2 要求发布验证「同一候选的 manifest/gate 证据」并拒绝「过期于代码变更」的报告，而这五个门禁的记录仍绑定在更早的 SHA 上；`verify.sh release` 会检查每个 gate id 的最新记录，重跑后整套证据都绑定到冻结候选。
4. 合并 PR：`gh pr view 1 --json state,headRefOid,mergeable` 确认 head 为 `8a798a6e`（或其后代，当前为 215dc7c7）、MERGEABLE、五个 CI job 全绿，再 `gh pr merge 1 --merge`（不绕过必需检查）；合并后确认 main 含该候选树：`git fetch origin main` 后 `git diff <main-sha> 8a798a6e -- src pom.xml Dockerfile docker-compose.yml docker-compose.dev.yml .mvn` 为空。
5. 发布：`release.yml` 只有在默认分支上才会注册，所以合并后再用**合并后 main 的 SHA** 作为 candidate_sha（它含冻结应用面，是真正被发布的修订；比 8a798a6e 更准确）：`gh workflow run release.yml --ref main -f version=v1.0.0 -f candidate_sha=$(git rev-parse origin/main) -f content_identity=e574ffcfddbf3e3dd75728b4181b8e5f1eb0f952a36b6e5d6dff86ee424c9d4a`，然后 `gh run watch`。该 job 用同一锁定输入重建并比对 content identity（不一致即拒绝推送；已在原生 amd64 CI 上核验与冻结值逐字节一致）、推送 `v1.0.0` 与 `sha-<candidate_sha>`、尝试把 package 设为 public、在干净 Docker config 中匿名拉取 digest、创建 Release。匿名拉取失败即 package 非 public：记录确切错误与恢复动作（GitHub UI 或具 `write:packages` 的 token），不得当作成功。
6. 附件：先 `./scripts/verify.sh package --out .execution/verify/p61-package-final3 --image driftwatch-tower:local --version v1.0.0 --manifest .execution/runs/p61-freeze/manifest.json`（必须等 soak 栈结束、无验收栈运行时再做，打包会起自己的 compose 项目）；再 `./scripts/evidence-pack.sh --out .execution/evidence --run-id p7-release`；然后 `./scripts/upload-release-assets.sh --version v1.0.0 --artifacts .execution/verify/p61-package-final3/artifacts --evidence .execution/evidence --digest sha256:...`（digest 取自 Release 正文）。
7. 匿名核验：`./scripts/verify.sh release --out .execution/verify/p7-release --version v1.0.0 --pr 1`。
8. 最终报告写回本文件：Release URL、image@digest、源码 SHA、证据路径、性能条件与已知限制；同步 `docs/RELEASE_NOTES.md` 的 digest 行。

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
| P5.3 | Dashboard 操作与响应式 | PASSED | G12 PASSED（8/8 页面，0 console 错误、无溢出、2px focus、socket connected）；before `.execution/runs/p53-before/`、after `.execution/verify/p5c-gate6/` |
| P6.1 | 冻结候选、短门槛、负载 | PASSED | G13/G14/G15 PASSED；冻结 SHA 791c75f、镜像 sha256:4f064add…；100/s×1800s 全部指标达标；见 `.execution/runs/p61-freeze/`、`.execution/verify/p61-load2/`、`.execution/verify/p61-package2/` |
| P6.2 | 24 小时真实验收 | RUNNING | run 20261001T145553Z-soak24（PID 42061，镜像 sha256:aeb1c9f9…，2026-10-01T14:55:53Z 起，预计 2026-10-02T14:55:53Z 结束）；2h/8h/16h 受控故障已排程；G16 待结束后用 `soak-report` 判定。前一个 run 20261001T134438Z-soak24 在第 1.06 小时被遗留 runner 干扰（非计划 app 重启），已留 FAILED 记录并重开完整窗口 |
| P7.1 | 合并自己的重构 PR | NOT_STARTED | - |
| P7.2 | 公共 Release / GHCR | NOT_STARTED | - |
| P7.3 | 匿名安装及最终报告 | NOT_STARTED | - |

## 门禁状态

NOT_RUN不是PASSED。EXPECTED_FAILURE仅允许G01旧版本的已知回归；修复后G03必须通过。

| Gate | 状态 | 绑定 SHA / 配置 / 证据 |
|---|---|---|
| G00 基线 | PASSED（已随候选 791c75f 重跑） | Java 21.0.12.1 + Docker 29.5.3；165 tests / 0 fail / 0 err / 0 skip；`.execution/verify/p61-unit2/` |
| G01 红色回归复现 | EXPECTED_FAILURE | 3 个 5.4 用例在旧实现复现（null 全缺失 / 单事件基线突增 / 乱序覆盖窗口）；`g01-red-regression.log`、`g01-cases.json` |
| G02 全新 Compose | PASSED（已随候选 791c75f 重跑） | 全新项目 dwt-p61 用冻结镜像启动；container-start-to-ready 11s（≤120s）；raw/quality 各 3 分区、pg/kafka 无宿主端口、摄取 smoke 202 且落库；`.execution/verify/p61-compose2/` |
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
| G15 候选包安装 | PASSED | SHA 791c75f；从制品安装（不构建）：镜像导出 tar sha256 校验一致、docker load 后 id 与冻结 id 相同、SBOM CycloneDX、checksums 覆盖全部制品；全新 project/volume/env（18082）启动后匿名 health 200、管理员 API 200、Bearer 摄取 202 且落库、重启后 readiness 恢复；`.execution/verify/p61-package2/` |
| G16 连续24小时 | RUNNING | run 20261001T145553Z-soak24 进行中（第 7 个 run；前 6 个中 5 个因规格审计发现的应用变更作废并留 FAILURE-NOTES，第 6 个 20261001T134438Z-soak24 因遗留 runner 在 14:47:55Z 注入非计划 app 重启而作废，见事件记录）；新窗口启动后 300 条真实 GitHub 事件已落库（bootstrap 轮）、readiness 200；`soak-report` 已按指南 11.1 实现，且内存判据已与指南口径（第 1-2 小时 vs 最后 1 小时）对齐 |
| G17 公开发布/匿名安装 | NOT_RUN | - |

## 长任务与恢复字段

24 小时 run 进行中（P6.2）。已作废的 run 保留为历史，不作证据。

| 字段 | 值 |
|---|---|
| run_id / run_dir | 20261001T145553Z-soak24 / `.execution/soak/20261001T145553Z-soak24/`（作废的候选 run：20261001T083508Z-soak24、20261001T093502Z-resume、20261001T093737Z-soak24、20261001T103023Z-soak24、20261001T114957Z-soak24、20261001T124728Z-soak24（遗留 runner，2026-10-01T14:49Z 已停止并标 FAILED）、20261001T134438Z-soak24（被 124728Z 的非计划 app 重启打断，2026-10-01T14:52Z 停止并标 FAILED）；`.execution/soak/p13-*` 与 20261001T03* 是 P1.3 runner 测试夹具，非验收 run，其中 p13-resume2 的 state 仍写 RUNNING 是当时故意 kill runner 的测试遗留，`ps` 已确认当前只有 1 个 acceptance.py runner 进程） |
| compose_project / volume 所有权 | dwt-soak（自有卷 dwt-soak_pgdata、dwt-soak_kafkadata、dwt-soak_streams-state） |
| env_file 路径 | `.execution/soak.env`（0600，仅路径，不含 secret 内容） |
| candidate_sha / image_id / config_hash | 8a798a6e（应用面）/ sha256:aeb1c9f9ccd68ca352b…（完整值见 freeze manifest）/ 7457349d… |
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

### 2026-10-01 P5.3 Dashboard 操作状态与响应式（PASSED，G12）

- 起步（SHA 7d36c61）：`scripts/p53-capture.mjs` 用显式 Basic 头驱动本机 Playwright 缓存的 headless Chromium（`playwright-core`，无下载、无外部 CDN 依赖），逐断点截图并记录 console 错误、页面级横向溢出与键盘 focus 探针。before 场景：compose `dwt-p53`（selfhost，18080）经摄取 API 与 mixed-incident 演示产生 227 events / 339 alerts；证据 `.execution/runs/p53-before/`（4 张 dark 截图 + `before-report.json` + `findings.md`），实测缺陷 6 项：WS 无法携带 Basic 凭据连接失败、字体来自 fonts.gstatic.com、页面内一次 403（CSRF 未走通）、320/768 整页横向溢出、focus 仅浏览器默认 1px auto、无浅色主题。
- 实现（SHA 4eab1da）：① WS 改走短期 HMAC ticket —— `GET /dashboard/api/ws-ticket` 签发 60s 一次性 ticket，`TicketHandshakeInterceptor` 在握手时校验，客户端按 1s→30s 指数退避重连且重连后重新查询数据；② 全部外部资源本地化 —— Lucide 图标集与 SockJS/STOMP 客户端 vendor 到 `/dashboard/vendor/`，字体改系统栈；③ 页面内 403 消除 —— `DashboardDataController.summary()` 不再有副作用式 `refreshAllAndPersist`，CSRF cookie 由 `CsrfCookieFilter` 确定性下发；④ 窄屏溢出 —— 表格包进 `.table-shell{overflow-x:auto}` 并对 shell/panel 设 `min-width:0`；⑤ 可见 focus —— `:focus,:focus-visible{outline:2px solid var(--gold-bright)}`；⑥ 浅色主题 —— `prefers-color-scheme` 与 `html[data-theme]` 双通道 token，切换按钮持久化到 cookie（只存偏好，不存凭据）。GSAP 动画依赖一并移除，改为等价的内建补间封装（保持 MOTION_INTENSITY=2，不引入 React）。
- G12（SHA e36bb3b，`.execution/verify/p5c-gate6/`）：`./scripts/verify.sh phase P5c --out … --base http://127.0.0.1:18080 --env-file .execution/p13.env` → PHASE-P5c PASSED。8/8 页面（320/768/1024/1440 × dark/light）status 200、console 错误 0、页面级横向溢出无、focus outline 2px、`#wsStatus` state=connected（label=Live）。
- 门禁工具修复：`verify.sh` 增加 `--base` 透传与 `P5b|P5c` 阶段别名（此前的编辑曾让脚本语法损坏，已重写并 `bash -n` 验证）；`check-dashboard.py` 原先用 label 子串匹配「connected」，而 UI 在断开时显示 "Disconnected"（同样含 connect）——改为读取 `#wsStatus` 的语义 class（connected/disconnected），capture 同步记录 `{label,state}`。
- 验收栈 `dwt-p53` 已在记录后按 `down -v` 清理；用户容器与 8080/5432/9092 未受影响。

### 2026-10-01 P6.1 冻结候选与完整验收（PASSED，G13+G14+G15）

冻结（`.execution/runs/p61-freeze/manifest.json`）：应用面 SHA 791c75f、source_tree_hash 5375a9d0…、config_hash 7457349d…、依赖锁哈希 8819d368…、镜像 sha256:4f064addf44b7febf4a8f1ae01efb0740695f14f7644b1c216ec7e9526d71248、JDK 21.0.12.1、机器 11 CPU / 18 GiB / 49 GiB 空闲。`source_tree_hash` 只覆盖应用面（src、pom、Dockerfile、compose、.mvn），工具链单独记 `tooling_tree_hash`：指南冻结的是应用/依赖/配置/迁移/规则/镜像，检查脚本的改动不能伪装成新版本，反之亦然。冻结还记录 `content_identity.jar_content_hash`（镜像内 app.jar 的 entry(name,size,CRC) 摘要），发布流水线在推送前用同一锁定输入重建并比对它，因此已发布镜像不可能悄悄换成另一份应用字节。

门禁（全部绑定 791c75f）：

- G00/G03/G04：`verify.sh unit` → 165 tests / 0 fail / 0 err / 0 skip（`.execution/verify/p61-unit2/`）。
- G02：全新项目 dwt-p61 用冻结镜像启动，container-start-to-ready 11s（`.execution/verify/p61-compose2/`）。
- G12：四断点 × 暗/亮 8/8 通过（`.execution/verify/p61-browser2/`）。
- G13：Trivy 0.58.1（DB UpdatedAt 2026-10-01T01:24:14Z）依赖树与镜像 0 HIGH/CRITICAL、镜像与 tracked 树 0 secret、镜像内无凭证文件（`.execution/verify/p61-g13c/`）。

G14 负载与真实缺陷修复：第一次 100/s × 1800s 运行失败并保留（`.execution/verify/p61-load/`，gate.json FAILED + FAILURE-NOTES.txt + 窗口末指标快照）：180000 次 offer 全部被接受（0 摄取失败），但窗口结束时只处理了 57839 条，consumer lag 122476 且不可能在界内清空。定位到的三个真实原因：① 每个事件都重算 source health，包含 4 条对滚动 1h/5m 窗口的 COUNT 查询——每事件 O(行数)，随表增长而恶化；② `findFirstBySourceOrderByEventTimestampDescIdDesc` 没有支撑索引，实测每事件 25ms 顺序扫描+排序；③ 监听器并发为 1，而持久化 topic 有 3 个分区。修复：source health 完全交给 30s scheduler（指南 7.2 的机制，G10 已覆盖），新增 V12 索引 `(source, event_timestamp DESC, id DESC)`（25ms → 0.034ms），监听器并发按分区数设为 3（scope 键固定映射到单一分区，因此每个 scope 的顺序性完全保留，独立 scope 才并行），负载生成器改为 6 种事件类型的真实混合而不是单 scope。修复后第二次运行 PASSED：180000/180000 offer 于 1800.0s（100.0/s）、accepted 180000 / failed 0、ack p95 12.4ms（限 1s）、commit p95 134ms（限 5s，180000 样本）、账本 180100 accepted = processed = raw、0 未确认、0 DLT、0 孤儿、三组 lag 31.3s 归零、资源曲线 263 点/容器（`.execution/verify/p61-load2/`）。同一次运行也暴露并修掉了 verify.sh 的 `--base`/阶段别名问题与 p53 采集的 networkidle 竞态。

G15：`verify.sh package` 从制品安装（不构建源码）——镜像导出 tar 的 sha256 与 manifest 一致、docker load 后 id 等于冻结 id、SBOM 为 CycloneDX、checksums 覆盖全部制品；在全新 project/volume/env（18082）启动后匿名 health 200、管理员 API 200、Bearer 摄取 202 且落库、重启后 readiness 恢复（`.execution/verify/p61-package2/`）。

期间还完成：CI 拆成 unit / integration / fault-migration / image(SCA+secret) / browser 五个 job，默认 `permissions: contents: read`；release 工作流（tag 或手动触发）在推送前断言 content identity，仅发布 job 拥有 contents/packages write；README 重写为发布版，明确来源、延迟、缺口与限流边界；`soak-report` 子命令按指南 11.1 逐条判定 G16。

### 2026-10-01 P6.1 补充：候选重建（favicon 与匿名图标）

首次冻结候选 791c75f 的全部门禁（G00/G02/G03/G04/G12/G13/G14/G15）通过后，CI 的 browser job 在 Linux 上失败，报每个页面 1 个 console 错误（403）。本地 macOS headless shell 不请求站点图标，因此本地 8/8 全绿而 CI 必红——这正是"CI 短门槛"要抓的东西。诊断：`/favicon.ico` 命中受保护根路径被拒（实测 401，Linux Chromium 记为 403），且仓库根本没有图标文件。

修复：新增 16×16 favicon.ico 与 32×32 apple-touch-icon.png（脚本生成，无外部依赖），页面声明 `<link rel="icon">`，SecurityConfig 把这两个公共资源加入匿名放行（`/api/**` 仍然需要管理员）。修复后本地 G02/G12 重新通过，CI 五个 job 全绿（run 36839799355，head f111aa6）。

候选随之重建：应用面 SHA f111aa6、source_tree_hash c1357517…、镜像 sha256:fcafddff…、content_identity.jar_content_hash c18b2a5c…（462 entries）。按指南"应用有变更必须重跑受影响门禁并重开 24 小时 run"：

- 20261001T083508Z-soak24 作废：runner 已停止（PID 23887），`.execution/soak/20261001T083508Z-soak24/FAILURE-NOTES.txt` 记录原因与时间；其 40 个采样只作历史，不作为 G16 证据，也不与新 run 拼接。
- 受影响门禁按新候选重跑：UNIT 165/0/0/0（`.execution/verify/p61-unit3/`）、G02（`.execution/verify/p61-compose3/`，11s ready）、G12（`.execution/verify/p61-browser3/`，8/8）、G13（`.execution/verify/p61-g13d/`，0 HIGH/CRITICAL、0 secret）、G14（`.execution/verify/p61-load3/`，同一 100/s×1800s 门槛）、G15（`.execution/verify/p61-package3/`，从制品安装且镜像 id 一致）。
- 同时把 npm lockfile 纳入版本控制：CI 需要 `npm ci` 在固定 Playwright 版本上运行，之前 lockfile 被 .gitignore 排除导致 browser job 无法安装浏览器。

### 2026-10-01 P6.2 24 小时真实验收启动（RUNNING）

候选 f111aa6 / 镜像 sha256:fcafddff… 的全部门禁通过后启动 run 20261001T093737Z-soak24（PID 48024，dwt-soak，18087，86400s，预计 2026-10-02T09:37:37Z 结束）。计划故障：2h app-restart、8h kafka-stop、16h db-stop；每 30s 采样 readiness/liveness/recent + 容器资源，每 5 分钟原子写 checkpoint。

启动过程中修掉了一个真实的恢复路径缺陷：`soak-resume` 用 CLI 默认值而不是原 run 的 placement 启动替代 run，导致监控进程采样的是错误的端口、且没有加载故障计划（新 run 的 image 与 planned_faults 为空、readiness 全为 error）。修复后 resume 会把 project/env-file/fault-plan/duration 从原状态带过去，`soak-start` 也会在 project 为空或 env 文件缺失时直接拒绝。作废的 20261001T093502Z-resume 与 20261001T083508Z-soak24 都保留 FAILURE-NOTES.txt 并标记 FAILED，不与新 run 拼接。

### 2026-10-01 P6.2 第三次启动（当前有效 run）：BOOTSTRAP 修复后的 24 小时

修复后全部受影响门禁在新候选 75e6a10 上通过（unit 166/0/0/0、G02、G12 8/8、G13、G14 180000@100.0/s + ack p95 6.7ms + commit p95 112ms + drain 29.5s、G15 镜像身份一致），CI 在 75e6a10/af88a40 全绿。全新 24 小时 run 20261001T103023Z-soak24（PID 85137，镜像 sha256:1f915abc…）于 2026-10-01T10:30:23Z 启动，预计 2026-10-02T10:30:23Z 结束；启动后摄取 299 条真实 GitHub 事件，`quality_alerts`=0、`alert_incidents`=0，证明回填不再制造告警洪水。

### 2026-10-01 P6.2 第二次作废与修复：BOOTSTRAP 不得触发实时检测

第二次 24 小时 run 运行到 15 分钟时，`soak-report` 的预演（对部分数据跑一遍判定逻辑，提前验证代码路径）暴露出一个真实的契约违背：299 条 BOOTSTRAP 事件产生了 298 条 LATE_EVENT(WARN) 与 203 条 DUPLICATE_EVENT(INFO)，而 WARN 会开 incident——新装实例一上线就有约 300 个 incident 和 500 条告警，全部来自回填历史数据。指南 4.1 明确「BOOTSTRAP 默认只持久化/观察 schema，不触发实时窗口」。

根因：`DetectorProcessor`（late/range/format）与 `DuplicateProcessor` 只检查 `skipDetection`（重投/冲突），没有检查 `liveWindowEligible()`；两个 spike 处理器此前已正确处理 SKIPPED_MODE。修复：两处都加上 mode 门控，只有 LIVE 参与实时检测；回填仍然持久化、仍然观察 schema、window_evaluation 仍记 SKIPPED_MODE。新增契约测试 `bootstrapBackfillTriggersNoRealtimeDetector`（旧事件 + 重复 payload + 越界值，全部以 BOOTSTRAP 注入，断言 3 条都保留、告警为空、状态 OK）。

验证：修复后新栈摄取 300 条真实 GitHub 事件 → `quality_alerts` 计数 0（修复前 503），dashboard summary `alertsLast24h=0`、`unhealthySources=0`。套件 166 tests / 0 fail / 0 err / 0 skip（`.execution/verify/p61-unit4/`）。

按指南重跑受影响门禁：G02（`.execution/verify/p61-compose4/`）、G12（`.execution/verify/p61-browser4/`）、G13（`.execution/verify/p61-g13e/`）、G14（`.execution/verify/p61-load4/`）、G15（`.execution/verify/p61-package4/`），随后以全新 24 小时 run 重开。作废的 20261001T093737Z-soak24 已标记 FAILED 并留 FAILURE-NOTES.txt。

### 2026-10-01 P6.2 受控故障预演（在等待期内完成）

24 小时 run 进行到约 5 分钟时，用一个独立的临时栈（dwt-faulttest，18088，同一冻结镜像）把三个计划故障各真实执行一次，走的是 `acceptance.py execute_fault` 的生产代码路径，避免在 run 的第 2/8/16 小时才发现时序或恢复问题：

| 故障 | outage | readiness 恢复 | 限值 |
|---|---|---|---|
| app-restart | 32.3s | 5.1s | ≤60s / ≤300s |
| kafka-stop | 31.3s | 8.5s | ≤60s / ≤300s |
| db-stop | 31.2s | 14.1s | ≤60s / ≤300s |

三者全部在门槛内，`.execution/plans/g16-faults.json` 的排程可用；证据 `.execution/runs/p62-faultpretest/fault-pretest.json`。故障期间的数据安全已由 P3.2 现场演练覆盖（60s 停机被重试吸收且无死信；200s 停机产生一条可恢复死信，重放后只有一次副作用）。临时栈与其卷已清理，未触碰 run 的 dwt-soak 栈。

### 2026-10-01 P6.2 等待期风险核对（保留期、内存、真实源）

- 保留期是否会打断账本：`RetentionService` 的 cron 是每天 03:30（`0 30 3 * * *`），落在 24 小时窗口内。逐条核对删除条件后确认它在本 run 中不会删任何行——raw_events 与 processed_receipts 的截止是 `received_at/processed_at < now-30d`（本 run 的行都是刚写入），metric_windows 是 `window_end < now-30d`，quality_alerts/alert_incidents 只删已解决且超过 90 天的（本 run 的告警都是 OPEN），source_inbox 是 35 天。因此 `processed_without_raw=0` 的账本判定不会被保留期破坏。03:30 之后可用 `GET /api/v1/operations/retention` 复核实际删除行数为 0。
- 内存曲线：`soak-report` 现在同时给出严格窗口（前 2 小时均值 vs 最后 1 小时）与 1–2 小时平台均值，以及冷启动值，避免把 JVM 预热误判为泄漏；当前冷启动 592MiB、前 10 分钟均值 679MiB。
- 真实源：3 次轮询，300 条不同事件（299 bootstrap + 1 条 bootstrap 之后新发现），0 缺口；告警 2 条均来自 LIVE 事件，incident 0、DLT 0、lag 0。轮询证据逐轮可查：poll1 BOOTSTRAP 299/299 APPLIED、poll2 LIVE 200/1 APPLIED（ETag 前进）、poll3 LIVE QUIET（304，游标不动），每轮都记录 `x_poll_interval=60`；间隔约 5.4 分钟，即 12 次/小时，低于未认证 60 次/小时预算。
- 宿主电源：`pmset -g` 显示系统 `sleep 1`（空闲 1 分钟即休眠），当时仅靠第三方应用（ChatGPT、Amphetamine、UURemote）持有断言才没睡——它们一旦退出，24 小时 run 会因监控空洞而失效。已用 `caffeinate -i -w 85137` 绑定 runner 进程持有 `PreventUserIdleSystemSleep`（PID 11469，runner 退出即自动释放，不改任何持久设置）。残余风险：显式休眠（合盖或菜单休眠）不受该断言保护，需要人在 24 小时内不要主动休眠。
- 容器与磁盘：G16 要求「无计划容器重启、磁盘未耗尽」，`soak-report` 现在读取三个容器的 `RestartCount` 与剩余磁盘（当前均为 0 次重启、50GiB 空闲）。已实测 `docker stop/start`（计划故障使用的路径）不会增加 `RestartCount`，因此该检查只会抓到真正的崩溃重启。

### 2026-10-01 P6.2 等待期：发布包内容缺口

对照指南第 12 节逐条核对 Release 附件时发现：`package-release.sh` 只把 `docker-compose.yml`、`.env.example`、`selfhost.sh`、`common.sh`、`PROJECT_EXECUTION_GUIDE.md` 与 `README.md` 放进 bundle，**没有放 `docs/RUNBOOK.md` 和 `docs/RELEASE_NOTES.md`**——而第 12 节第 5 条要求的「安装/升级/备份恢复说明和已知限制」正写在这两份文档里，只下载 Release 的安装者会拿不到。已修：bundle 现在包含这两份文档。收尾程序第 5 步相应改为先用最终脚本重跑 `verify.sh package` 再上传，且明确该步必须等 soak 栈结束后执行（打包会起自己的 compose 项目，指南禁止与验收栈并行以免资源竞争）。

### 2026-10-01 P6.2 等待期：CI action 固定到完整 SHA

按指南第 4 节「CI actions 固定完整 SHA」逐条核对两个 workflow，发现此前用的是浮动的 `@v4` 主版本标签——上游重新打标签会悄悄改变发布流水线实际执行的代码，正是该条要防的。已把 `actions/checkout`、`actions/setup-java`、`actions/setup-node`、`actions/upload-artifact` 全部固定到解析出的提交 SHA（版本号保留为行尾注释），两个 workflow 共 14 处 `uses:` 均已固定。推送后 CI 在新 head c5cf8d6 上全绿，证明固定的 SHA 可用。

### 2026-10-01 P6.2 第四次启动与逐条规格审计（当前有效 run）

等待期间按指南逐条审计，发现并修复了三类此前未满足的契约项，因此按规则重开 24 小时 run：

1. **evaluation coverage 缺失**（指南 5.3「另外展示 evaluation coverage，不把 OK 等同所有窗口都参与」，且第 551 行把它写进 G04 的完成条件）。新增 `GET /api/v1/events/coverage`（按 outcome 与 baseline 状态聚合，带 `included_ratio` 与说明文字）与 dashboard 面板；新增容器测试断言四种状态（INCLUDED+APPLIED、EXPIRED、SKIPPED_MODE+null、INCLUDED+PENDING）与 OpenAPI 契约断言。
2. **仪表盘缺少 incident / 指标窗口 / 死信详情与重放**（指南 7.5 的页面清单）。三个面板与 rail 图标补齐，接入既有 API；重放与 resolve 具备 loading、禁用重复提交、成功/失败反馈与 retry。
3. **没有自动化可访问性扫描**（指南 7.5）。本地打包 axe-core 4.13.0（不依赖 CDN）并在采集时执行 WCAG 2A/2AA 扫描，critical/serious 违规直接使 G12 失败。

扫描立刻查出三个真实缺陷（这正是该条要求存在的意义）：① 横向滚动容器不可键盘聚焦（`scrollable-region-focusable`）；② 强调色与语义色只按浅色页面定义，深色面板上 gold 仅 3.54:1、ok 仅 2.96:1，且 `[data-theme="dark"]` 只覆盖背景，系统偏好为浅色的用户切到深色会得到浅色系配色（2.9–3.5:1）；③ **浅色截图其实从未是浅色**——系统偏好已是浅色时点一次主题开关会翻成深色，所以此前 G12 的 "light" 证据实际测的是深色（修复后实测平均亮度：修复前 light=27.1 与 dark=26.6 几乎相同，修复后 light=232.4）。三处均已修复：滚动容器与证据块加 `tabindex="0" role="region"`；两套主题各自完整定义强调色/语义色并实测对比度（深色 gold 8.6–9.0、ok 8.3–9.7、err 6.1–7.2；浅色 gold 5.1–5.7、ok 4.9–5.4、err 6.2–7.9、muted 5.6–7.1）；采集脚本改为显式设置并校验 `data-theme`，不再点击开关。

另修：`verify.sh package` 的 bundle 现在包含 `docs/RUNBOOK.md` 与 `docs/RELEASE_NOTES.md`（第 12 节要求的安装/升级/备份恢复说明与版本说明此前不在包内）；两个 workflow 的 action 全部固定到完整 SHA（指南第 4 节）。

新候选 c1220eb 上重跑全部受影响门禁：UNIT 167/0/0/0（`.execution/verify/p61-unit5/`）、G02（`p61-compose8/`）、G12（`p61-browser8/`，8/8 且可访问性扫描 0 serious/critical、主题经属性校验）、G13（`p61-g13f/`）、G14（`p61-load5/`，180000@100.0/s、ack p95 5.1ms、commit p95 112ms、drain 29.2s）、G15（`p61-package-final/`，镜像身份一致、bundle 含三份文档）。第四个 24 小时 run 20261001T114957Z-soak24 于 2026-10-01T11:49:57Z 启动（PID 73433，镜像 sha256:1d10f24a…，`caffeinate` 防休眠），启动后 299 条真实事件、0 告警、0 incident。

### 2026-10-01 P6.2 第五次启动（当前有效 run）：规格审计的第二批修复

继续逐条审计，又发现三个契约项未满足，均已实现并用测试锁定，因此再次重开 24 小时 run：

1. **baseline version 未进入窗口 key 与证据**（指南 5.3「schema 活动版本变化后使用新 baseline version构建窗口 key……不能将两版字段集合的计数相加。processor emitted evidence带 baseline_version/rule_version」）。`BaselineMessage` 本就带 `versionId` 却未使用：null 窗口 key 现在包含活动 baseline 版本，证据新增 `baseline_version`；新增契约测试验证「版本 1 记 2 条、切换版本 2 后窗口从 0 重新计数到 3（而不是 5）」且证据写明版本号。
2. **origin_reference 缺少获取 URL**（指南 6.2 要求含原始ID、仓库、获取URL、poll run ID）。记录可以来自任意分页，因此没有采用"统一填第一页 URL"（那会是假声明），而是让每条记录携带其实际所在页的 URL：新增 `PagedRecord` 与逐页 URL 追踪，`origin_reference` 形如 `github:apache/kafka#7001@11|http://…/repos/apache/kafka/events?per_page=2`；新增测试断言四个要素齐全。轮询测试 12 项全绿。
3. **Kafka 保留期依赖 broker 默认**（指南 7.1「默认Kafka保留7天」）。raw/quality/DLT 三个持久 topic 现在显式设置 `retention.ms=604800000`；运行中的栈已实测 `kafka-configs --describe` 返回 `retention.ms=604800000`。

新候选 378d7cf 上重跑全部门禁：UNIT 169/0/0/0（`.execution/verify/p61-unit6/`）、G02（`p61-compose9/`）、G12（`p61-browser9/`，8/8、可访问性 0 serious/critical）、G13（`p61-g13g/`）、G14（`p61-load6/`，180000@100.0/s、ack p95 4.6ms、commit p95 112ms）、G15（`p61-package-final2/`，镜像身份一致）。第五个 24 小时 run 20261001T124728Z-soak24 于 2026-10-01T12:47:28Z 启动（PID 95382，镜像 sha256:a8fa82cb…，`caffeinate` 防休眠），启动后 299 条真实事件、1 条 LIVE 告警、0 incident。

### 2026-10-01 P6.2 第六次启动（当前有效 run）：批次收据缺陷

审计指南 4.2/4.3 时发现一个严重缺陷并用测试复现：收据主键是 (source, event_type, idempotency_key)，而批次里同一 source/event_type 往往有多条事件——旧实现为**每条**事件写一行收据，于是同批次内第二条同类型事件与第一条撞键，冲突校验比对的是**单条摘要**，整个批次直接返回 409。「一次批量提交多条同类型事件」这一最常见用法是坏的。

修复：一行收据代表该 (source, event_type) 在本批中的全部条目，逐条状态存 `batch_items`（指南 4.3 的列语义）；冲突摘要改用**批次摘要**（指南 4.2「batch Idempotency-Key 对应固定顺序的整批」）；行级 publish_state 由条目汇总（全部确认才 CONFIRMED，有失败即 FAILED），逐条真相仍在 batch_items；重试只重发未确认条目并保留其原身份；批次同时受 100 条与 4 MiB 双重限制（此前只限制条数与单条 256 KiB）。新增测试 `aRetriedBatchResendsOnlyTheUnconfirmedItemsAndCompletes` 与 `aBatchOverTheTotalSizeLimitIsRejectedWithoutPublishing`。

新候选 8a798a6e 上重跑全部门禁：UNIT 171/0/0/0（`.execution/verify/p61-unit7/`）、G02（`p61-compose10/`）、G12（`p61-browser10/`，0 问题）、G13（`p61-g13h/`）、G14（`p61-load7/`，180000@100.0/s、ack p95 5.5ms、commit p95 112ms）、G15（`p61-package-final3/`，镜像身份一致）。第六个 24 小时 run 20261001T134438Z-soak24 于 2026-10-01T13:44:38Z 启动（PID 18174，镜像 sha256:aeb1c9f9…，`caffeinate` 防休眠），启动后 299 条真实事件、1 条 LIVE 告警、0 incident。

审计收敛：本轮已逐条核对指南 §4.1-4.6、§5.1-5.4、§6.1-6.3、§7.1-7.5（§4.4 与实现一致；§5.2 各检测器证据字段与实现一致）。除出现新的具体证据外，不再为新增行为要求重开 run，让本次运行走完 24 小时。

### 2026-10-01 P6.2 等待期：跨架构内容身份核验（发布断言前置验证）

发布流水线在推送前会重建镜像并比对 `content_identity.jar_content_hash`，不一致就拒绝推送。此前只验证过「本机 arm64 两次构建一致」，而 CI 在 **amd64** 上构建——若两者字节不同，发布会在 24 小时 run 结束后才失败，代价是又一天。

本机用 QEMU 模拟 amd64 构建失败（`mvnw dependency:go-offline` 解 tar 失败，属模拟层问题，CI 原生 amd64 构建是成功的），因此改为让 CI 自己报告：在 `image` job 中新增一步，构建后从镜像内取出 `/app/app.jar` 计算同一算法（entry 的 name/size/CRC 摘要）并打印 + 上传为 `content-identity` 附件。

结果（run a53fcd3，head a53fcd36）：CI 原生 amd64 计算值 `e574ffcfddbf3e3dd75728b4181b8e5f1eb0f952a36b6e5d6dff86ee424c9d4a` 与冻结候选值**逐字节相同**。结论：跨架构重建可复现（Dockerfile 用 digest 固定 Temurin 21 构建阶段，javac 输出与宿主架构无关），发布流水线的身份断言会通过。

### 2026-10-01 P6.2 第六个 run 作废：遗留 runner 干扰（第 7 次启动）

等待期例行核对时发现异常：app 容器 `StartedAt=2026-10-01T14:48:27Z`，而 kafka/postgres 是 13:44:25Z（run 20261001T134438Z-soak24 的启动时刻）。`docker inspect` 显示 `RestartCount=0`、`ExitCode=0`、`FinishedAt=14:47:56Z`，`docker events` 显示 14:47:56 kill→stop→die、14:48:27 start——是一次优雅的 `compose stop/start`，不是崩溃。

根因：`ps` 发现**两个** acceptance.py runner。PID 95382 属于更早的 run `20261001T124728Z-soak24`（12:47:29Z 启动，绑定上一候选 378d7cf8 / 镜像 a8fa82cb），在冻结候选接管 compose 项目 `dwt-soak` 后**没有被停掉**；它的 2h 计划故障按自己的 elapsed 在 14:47:55Z 触发，对**共享的** app 容器执行了 app-restart（`faults.jsonl`：status completed、outage 32.5s、recovery 10.1s）。两个 runner 共用一个 compose 项目。

危险点：G16 的 `container_health` 只数 `RestartCount`（优雅 stop/start 不增加），所以这次干扰对判定**不可见**；而该 runner 的 8h kafka-stop（20:47Z）与 16h db-stop（04:47Z）还会继续打进新窗口。

处理（不掩盖、不拼接）：

1. 14:49Z 停止 PID 95382；`ps` 复核只剩 1 个 runner。
2. `20261001T124728Z-soak24` 标 FAILED（被取代且绑定旧候选）；`20261001T134438Z-soak24` 标 FAILED（环境干扰），samples/faults/checkpoint 全部保留作证据，`result.json` 记 measured 3900s / 86400s，不删除、不改造。
3. 工具修复（commit 57baf06，仅 `scripts/`）：`soak-start` 在存在其它存活 runner 时拒绝启动并列出 run_id/PID；`runner` 启动时与**每次注入故障前**都校验 app 容器镜像 id 是否等于本 run 记录的镜像，不等则记 `skipped-environment-changed` 并让 run FAILED——宁可失败也不去动别人的环境。
4. 启动前复核制品：镜像 `driftwatch-tower:local` = sha256:aeb1c9f9…，jar content identity `e574ffcf…` 与冻结值一致；当前工作树 source_tree_hash 重算 = `d5ca0f55…`（203 文件）与 manifest 一致；`git diff 8a798a6e..HEAD -- src pom.xml Dockerfile docker-compose.yml docker-compose.dev.yml .mvn` 为空（冻结后提交只动 `.github/workflows`、`docs`、`scripts`）。
5. 重建环境：`docker compose -p dwt-soak --env-file .execution/soak.env down -v`（仅该验收项目自有测试卷）再 `up -d --wait`，三容器 healthy、app `RestartCount=0`、readiness 200。
6. 启动第 7 个 run `20261001T145553Z-soak24`（PID 42061，86400s，预计 2026-10-02T14:55:53Z 结束，`caffeinate -i -w 42061`）。启动后约 1 分钟 bootstrap 轮已落库 300 条真实 GitHub 事件、readiness 200。run 记录的 `git_sha=1213d3b8`（启动时 HEAD），工具修复 57baf06 在启动后数分钟提交；应用面与冻结候选逐字节相同，因此该窗口仍覆盖冻结制品。
7. 两条守卫实测（不触碰环境）：`soak-start` 在有存活 runner 时拒绝并列出 run_id/PID（exit 2，未创建 run 目录）；`execute_fault(..., expected_image='sha256:deadbeef')` 返回 `skipped-environment-changed` 且 app 容器 `RestartCount` 仍 0、`StartedAt` 未变、readiness 200。测试中发现 `live_runners` 会被 `.execution/soak/` 下的非目录文件绊倒（`NotADirectoryError`），已加 `isdir` 过滤并复测通过（commit 见下）。

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
