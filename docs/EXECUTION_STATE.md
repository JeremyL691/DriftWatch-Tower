# DriftWatch Tower 执行状态

本文件只记录事实，不另行定义范围。[执行指南](PROJECT_EXECUTION_GUIDE.md)是唯一规范，[启动提示词](AGENT_REFACTOR_PROMPT.md)交给接手 Agent。

## 当前入口

| 字段 | 当前值 |
|---|---|
| document_revision | 1.0 |
| handoff_date | 2026-09-30，America/Los_Angeles |
| product_goal_status | READY，代码重构尚未开始 |
| current_phase | P0 |
| current_task | P0.1 |
| next_action | 保护这次文档交接和用户变更，查询远端并建立重构分支 |
| local_baseline_sha | 84400133d9aab140e6e7d8bd34550c178c89a69a |
| remote_snapshot_sha | 082fd84d7fabee7d94e05b4dba842f0995a3775e，历史快照，执行时重新核验 |
| execution_branch | 尚未创建；预定 codex/release-v1 |
| execution_base_sha | 未确定 |
| candidate_sha / source_tree_hash | 未确定 |
| candidate_image_id / public_digest | 未确定 |
| target_release | v1.0.0；执行时按指南核验 tag 冲突 |
| docs_delivery_status | VERIFIED，本轮文档交付核验通过 |
| release_authorization | 用户已授权接手 Agent 提交、推送、合并自己的 PR、公开 Release/GHCR |
| application_changes_in_handoff | 无业务代码、依赖、配置、CI、迁移改动 |
| active_soak_run | 无；24 小时验收尚未启动 |
| external_blocker | 当前文档交付没有；产品执行的前提须 P0/P1 重新检查 |

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
| P0.1 | 保护交接、对齐远端 | NOT_STARTED | 下一任务 |
| P0.2 | 基线与红色回归 | NOT_STARTED | 历史结果不能代替重跑 |
| P1.1 | 可重复部署、固定依赖 | NOT_STARTED | - |
| P1.2 | 配置和认证基础 | NOT_STARTED | - |
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
| G00 基线 | NOT_RUN | - |
| G01 红色回归复现 | NOT_RUN | - |
| G02 全新 Compose | NOT_RUN | - |
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

当前没有产品执行记录。后续每条保留：

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
