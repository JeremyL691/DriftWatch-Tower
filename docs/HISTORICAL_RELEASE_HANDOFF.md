# Historical release instructions (superseded 2026-10-01)

This is retained evidence, not an execution entry point. Current steps are in RELEASE_FINISH_PLAN.md.

## 收尾程序（G16 之后，按序执行，勿跳步）

以下值取自 `.execution/runs/p61-freeze/manifest.json` 与当前 run，勿手抄：

- candidate（应用面）: `573154b9b7db0cb78a6db1ad10bd09c8df12fe57`（字段路径 + 320px 布局修复）
- 镜像（本地，未发布）: `sha256:2901be88df52de693b892825700ab8101fd72efb7bb944ad639b6018ad77d0d3`
- `content_identity.jar_content_hash`: `1070909c890c03cb64031949ff500d1b107fae53147e82c5d8afd6016e7d4c8d`
- 当前 24 小时 run: run 8（由 `.execution/p7-gate-chain.sh` 在新候选上自动启动；第 7 个 run 20261001T145553Z-soak24 因字段路径缺陷作废，见事件记录）
- 最终制品目录: `.execution/verify/p7-package/artifacts`（新候选；旧 `p61-package-final3` 属已废弃的 8a798a6e）

1. `./scripts/verify.sh soak-report --run-id <run 8 的 run-id> --out .execution/verify/p7-soak` → 判定 G16 并写出 `gate.json`(SOAK)。有问题就记录并修复后重开完整 24 小时，不拼接。
1b. 判定完成后释放验收资源（指南 §12 第 7 条的所有权清理，必须在打包前做，打包会起自己的 compose 项目）：`docker compose -p dwt-soak --env-file .execution/soak.env down -v`（报告在判定前已读完数据库，证据已落盘到 `.execution/`，卷是该验收项目自有的测试卷）；`caffeinate -i -w <runner pid>` 随 runner 结束自动退出；确认 `docker ps` 只剩用户自己的栈、`docker volume ls` 只剩 `dwt-trivy-cache`（保留给收尾期的 G13 复跑）。
2. 通过后更新本文件（G16/P6.2 PASSED、实测数字与证据路径），提交并推送 `codex/release-v1`。
3. 阶段门禁已在新候选上复跑（后台链，输出 `p7-P2`…`p7-P5b`、`p7-P5c`、`p7-P6`、`p7-load`、`p7-package`）；若需重跑：`for ph in P2 P3 P4 P5 P5b; do ./scripts/verify.sh phase $ph --project dwt-soak --env-file .execution/soak.env --out .execution/verify/p7-$ph; done`（每个脚本跑一次完整套件，约 35 分钟；只依赖 Docker，不需要运行中的栈）。若某个门禁失败且**唯一**失败用例是 `DeadLetterIntegrationTest.malformedRecordIsDeadLetteredAndDoesNotBlockLaterRecords`（已知隔离竞态），该门禁可**重跑一次**并记为已知 flaky（附断言原文）；其它原因的失败都是真失败，必须记录并修复或如实上报，不得跳过。理由：指南 §11.2 要求发布验证「同一候选的 manifest/gate 证据」并拒绝「过期于代码变更」的报告，而这五个门禁的记录仍绑定在更早的 SHA 上（实测：G03/G04=653a7e39、G05-G07=7217ff67、G08/G09=fbba4d05、G10=4b111417、G11=6bf026de，应用面与发布面不同）；`verify.sh release` 会检查每个 gate id 的最新记录，重跑后整套证据都绑定到冻结候选。**`release-check.sh` 现在会强制这一点**：它取 tag 指向的提交，对每个 gate 用 `git diff --quiet <gate_sha> <released_sha> -- src pom.xml Dockerfile docker-compose.yml docker-compose.dev.yml .mvn` 断言应用面一致，不一致即 RELEASE 门禁 FAILED（此前只记录 git_sha、不校验，等于没有牙齿）。
4. 合并 PR：`gh pr view 1 --json state,headRefOid,mergeable` 确认 head 为 `573154b9`（或其后代）、MERGEABLE、五个 CI job 全绿，再 `gh pr merge 1 --merge`（不绕过必需检查）；合并后确认 main 含该候选树：`git fetch origin main` 后 `git diff <main-sha> 573154b9 -- src pom.xml Dockerfile docker-compose.yml docker-compose.dev.yml .mvn` 为空。**已知不稳定测试**：`DeadLetterIntegrationTest.malformedRecordIsDeadLetteredAndDoesNotBlockLaterRecords` 有测试隔离竞态（详见事件记录），CI 与 P3 门禁都可能偶发在此红；遇到时**重跑一次**并注明「已知 flaky（隔离竞态）+ 失败断言」，仍要求全绿后才合并，不得跳过或绕过。
5. 发布：`release.yml` 只有在默认分支上才会注册，所以合并后再用**合并后 main 的 SHA** 作为 candidate_sha（它含冻结应用面，是真正被发布的修订；比 573154b9 更准确）：`gh workflow run release.yml --ref main -f version=v1.0.0 -f candidate_sha=$(git rev-parse origin/main) -f content_identity=1070909c890c03cb64031949ff500d1b107fae53147e82c5d8afd6016e7d4c8d`，然后 `gh run watch`。该 job 用同一锁定输入重建并比对 content identity（不一致即拒绝推送；已在原生 amd64 CI 上核验与冻结值逐字节一致；**未提供 content_identity 时直接拒绝发布**，包括 tag push 触发），推送 `v1.0.0` 与 `sha-<candidate_sha 前 12 位>`、尝试把 package 设为 public、在干净 Docker config 中匿名拉取 digest、创建指向该 SHA 的 Release（正文含 image@digest，供第 7 步取用）。匿名拉取失败即 package 非 public：记录确切错误与恢复动作（GitHub UI 或具 `write:packages` 的 token），不得当作成功。
6. 附件：先 `./scripts/verify.sh package --out .execution/verify/p7-package --image driftwatch-tower:local --version v1.0.0 --manifest .execution/runs/p7-freeze/manifest.json`（必须等 soak 栈结束、无验收栈运行时再做，打包会起自己的 compose 项目）；再 `./scripts/evidence-pack.sh --out .execution/evidence --run-id p7-release`；然后 `./scripts/upload-release-assets.sh --version v1.0.0 --artifacts .execution/verify/p7-package/artifacts --evidence .execution/evidence --digest sha256:...`（digest 取自 Release 正文）。
7. 匿名核验：`./scripts/verify.sh release --out .execution/verify/p7-release --version v1.0.0 --pr 1`。
8. 最终报告写回本文件：Release URL、image@digest、源码 SHA、证据路径、性能条件与已知限制；同步 `docs/RELEASE_NOTES.md` 的 digest 行。

