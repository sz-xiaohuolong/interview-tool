# 投递管理 MVP 实施计划

依据 [SPEC](SPEC.md) 的 JA-REQ-01～06 和 [技术方案](TECH_DESIGN.md)，按 M1→M2→M3→M4 推进。用户已授权设计、实施、测试；不依赖未来招聘平台接入，不新增业务范围。

## 1. 前提与检查入口

复用现有 Java 21、Maven、Node 与 SQLite；不新增密钥。以当前 main 为稳定基线，保留已有未跟踪目录。后端局部：`mvn -f backend/pom.xml -Dtest=JobApplicationIntegrationTest test`；前端局部：`npm --prefix frontend test -- JobApplications`；标准：`./scripts/test.sh`；真实浏览器：`bash scripts/e2e-job-applications.sh`。实际结果见 [验证记录](VERIFICATION.md)。

## 2. 文件映射

| 操作 | 范围 | 职责 |
|---|---|---|
| 新增 | backend 的 entity、mapper、service/job、controller/job | 独立记录 CRUD 和 CSV |
| 修改 | backend/src/main/resources/schema.sql、backend/pom.xml、TrainingSchemaPreflight.java、scripts/prepare-training.py | 追加表、兼容旧库预检查与 CSV 解析依赖 |
| 新增 | frontend/src/api/job-applications.ts、pages/jobs、components/jobs | API、表格、表单与预览 |
| 修改 | router.ts、navigation.ts、AppLayout.vue | 独立求职入口 |
| 新增／修改 | 后端集成、前端交互、导航及 Playwright 测试、scripts/e2e-job-applications.sh | 覆盖 JA-AC-01～12 |
| 修改／新增 | 本目录 SPEC／设计／计划／验证，根 DESIGN、地图，当前技术与进度、README | 唯一事实源增量维护，保留 RESEARCH |

## 3. 任务与依赖

| 任务 | 结果与步骤 | 验收 | 前置 |
|---|---|---|---|
| M1 | 写 HTTP＋SQLite 失败测试；实现模型、校验、CRUD、状态与归档；局部集成通过 | AC01～07、10、12 | 已冻结需求 |
| M2 | 写页面／导航失败测试；表格、筛选、排序、表单、详情、归档与确认删除；相关前端通过 | AC01～07、11 | M1 |
| M3 | 写 CSV 失败用例；完整导出、模板、预览、确认、重复／冲突、公式防护；前后端相关测试通过 | AC08～09 | M2 |
| M4 | 审查差异及安全；标准回归、真实端到端、进程重启、原数据摘要核对；修复后复验相关部分；同步文档与 Git | 全部 | M3 |

实施顺序为 M1～M4，每个里程碑先通过相关检查再继续。当前四项均已完成，实际结果与失败修复见 PROGRESS 和 VERIFICATION；不以本计划代替运行证据。

## 4. 验证矩阵

M1 集成测试验证真实 SQLite 持久化、参数过滤、七状态、边界；M2 前端测试验证真实组件的 HTTP 契约与错误恢复；M3 测试完整 CSV 往返、相同 ID 冲突、无 ID 重复、空／超大／无效文件、中文多行与公式保护。M4 浏览器通过真实后端验证完整录入和 CSV 恢复、375／768／1024／1440px、请求路径与旧入口；真实进程重启证据覆盖 AC10。标准工程与运行前后摘要覆盖 AC12。

## 5. 交付与恢复

适用检查完成后只提交本轮文件，推送 origin/main；重启日常后端，核对健康、资料、历史、数据库摘要。CSV 或迁移出现数据不一致立即停止交付并调查，不自动重置库。新增表不自动删除；旧版本可能因严格结构校验拒绝新表。若需降级，应先保存新增记录并明确完整备份恢复策略，不直接覆盖数据库。

## 6. 进度与偏差

实际状态、失败及证据在 [当前进度](../../PROGRESS.md) 与 [VERIFICATION](VERIFICATION.md) 维护，不以计划勾选代替真实结果。
