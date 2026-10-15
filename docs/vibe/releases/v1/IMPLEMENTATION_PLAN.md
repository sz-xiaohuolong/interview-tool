# V1 实施计划

目标：交付本机可日常使用的 AI 技术面试助手。需求背景见 [PROJECT_BRIEF.md](PROJECT_BRIEF.md)，行为与验收见 [SPEC.md](SPEC.md)，当前技术契约见 [TECH_DESIGN.md](../../TECH_DESIGN.md)。

本计划保留 V1 已执行任务及其依赖。当前执行状态、仓库检查点与下一步统一由项目级 [PROGRESS.md](../../PROGRESS.md) 维护；实际验证结论归 [VERIFICATION.md](VERIFICATION.md)。主代理为唯一执行编排 owner。

## 任务及依赖

- [x] T0：检查空仓库、冻结需求、建立初始需求、架构、计划、决策文档和本地配置模板。
- [x] T1：后端基础与 Repository：schema.sql、Store.java、Models.java、Application.java；真实 SQLite 读写与重启测试。依赖 T0。
- [x] T2：资料闭环：MaterialService、MaterialController、文件解析；先测试空白/类型/上限/CRUD，再实现；验证 PDF/DOCX/文本与删除一致性。依赖 T1。
- [x] T3：面试引擎：LlmClient、InterviewService、InterviewController、prompts；先测试规划、追问上限、幂等、失败重试、结束与报告，再实现。依赖 T1、资料上下文契约。
- [x] T4：前端完整页面：App、Dashboard、Configure、Materials、Session、History、Report、api/types/style；依赖已验证 API，覆盖空态/错误/加载、刷新与多行回答。
- [x] T5：针对性独立审查与修复；核对资料删除、未回答报告、跨源写入、内部信息泄露、重启恢复与格式错误。依赖 T2～T4。
- [x] T6：实际启动、浏览器闭环、构建与全套测试；按 AC 记录证据；执行真实模型三路径及项目深挖语义验收。依赖 T5。
- [x] T7：README、启动脚本、验收脚本与本地交付；保留明确限制。依赖 T6；后续 GitHub 同步按单独授权执行。

## 需求、任务与验证映射

| 任务 | 关联需求 / 验收 | 可观察接缝 | 验证方式 |
|---|---|---|---|
| T0、T1、T7 | REQ05、REQ06；AC01、AC06、AC11 | SEAM01、SEAM04 | 启动、SQLite 重新打开、JAR 重启、README 和脚本 |
| T2 | REQ02、REQ03、REQ05；AC03、AC04、AC09 | SEAM03 | ApiIntegrationTest、资料页测试、HTTP 文件验收 |
| T3 | REQ01～REQ04、REQ06；AC02～AC08、AC10 | SEAM02～SEAM05 | API、模型校验、可控上游错误、恢复边界与缺密钥测试 |
| T4 | REQ01～REQ06；AC01～AC07、AC09、AC10 | SEAM01～SEAM05 | 前端单测、浏览器三路径、刷新和窄屏交互 |
| T5、T6 | 全部 REQ；AC01～AC11 | 全部接缝 | 针对性审查、回归、构建、HTTP/浏览器、真实模型样本判读 |

接缝的外部判定标准由 SPEC 维护，命令、结果与配置适用范围由 VERIFICATION 维护。

## 审查重点

1. 模型失败后已经保存的回答是否重复；重复 requestId 和旧问题 ID 是否分别处理。
2. 结束、刷新与进程重启时 pendingAction 的恢复；报告失败不能伪造完成。
3. 用户资料中的指令不会覆盖系统规则；模型未提供依据时清楚标记资料不足。
4. 删除原始资料不影响历史事实；上传路径隔离，文件和数据库保持一致。
5. 真实模型与 Mock 标识贯穿页面、会话与报告；未配置密钥不静默降级。
