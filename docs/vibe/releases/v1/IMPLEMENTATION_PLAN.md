# V1 实施计划

目标：交付本机可日常使用的 AI 技术面试助手。需求背景见 [PROJECT_BRIEF.md](PROJECT_BRIEF.md)，行为与验收见 [SPEC.md](SPEC.md)，当前技术契约见 [TECH_DESIGN.md](../../TECH_DESIGN.md)。

本计划记录 V1 当时完成的任务及先后关系。当前进度和代码位置见 [PROGRESS.md](../../PROGRESS.md)，实际测试结果见 [VERIFICATION.md](VERIFICATION.md)。当前主代理统一负责执行安排。

编号说明：T 是任务，REQ 是需求，AC 是验收标准，SEAM 是可从页面、接口或数据库观察结果的验证入口。任务完成不等于任意配置下的模型质量都已验证，结论以验证记录为准。

## 任务及依赖

以下任务全部已完成。

| 任务 | 完成内容 | 前置条件与验证重点 |
|---|---|---|
| T0 初始准备 | 检查空仓库，冻结需求，建立需求、架构、计划、决策文档和本地配置模板 | 首个任务 |
| T1 后端基础 | 数据访问层 Repository，以及 schema.sql、Store.java、Models.java、Application.java | 依赖 T0；用真实 SQLite 验证读写和重启 |
| T2 资料管理 | MaterialService、MaterialController 和文件解析 | 依赖 T1；先测试空白、类型、上限及增查改删（CRUD），再实现；验证 PDF/DOCX/文本和删除一致性 |
| T3 面试引擎 | LlmClient、InterviewService、InterviewController 和 prompts | 依赖 T1 及资料上下文约定；先测试规划、追问上限、防重复处理（幂等）、失败重试、结束和报告，再实现 |
| T4 前端页面 | App、Dashboard、Configure、Materials、Session、History、Report、api/types/style | 依赖已验证 API；覆盖空态、错误、加载、刷新和多行回答 |
| T5 审查与修复 | 针对性独立审查并修复问题 | 依赖 T2～T4；核对资料删除、未回答报告、跨源写入、内部信息泄露、重启恢复和格式错误 |
| T6 完整验收 | 实际启动、浏览器完整流程、构建及全套测试；按 AC 记录证据 | 依赖 T5；执行真实模型三条训练路径及项目深挖语义验收 |
| T7 本地交付 | README、启动脚本和验收脚本，说明已知限制 | 依赖 T6；后续 GitHub 同步按单独授权执行 |

## 需求、任务与验证映射

| 任务 | 关联需求 / 验收 | 验证编号 | 验证方式 |
|---|---|---|---|
| T0、T1、T7 | REQ05、REQ06；AC01、AC06、AC11 | SEAM01、SEAM04 | 启动、SQLite 重新打开、JAR 重启、README 和脚本 |
| T2 | REQ02、REQ03、REQ05；AC03、AC04、AC09 | SEAM03 | ApiIntegrationTest、资料页测试、HTTP 文件验收 |
| T3 | REQ01～REQ04、REQ06；AC02～AC08、AC10 | SEAM02～SEAM05 | API、模型校验、可控上游错误、恢复边界与缺密钥测试 |
| T4 | REQ01～REQ06；AC01～AC07、AC09、AC10 | SEAM01～SEAM05 | 前端单测、浏览器三路径、刷新和窄屏交互 |
| T5、T6 | 全部 REQ；AC01～AC11 | 全部验证入口 | 针对性审查、回归、构建、HTTP/浏览器、真实模型样本判读 |

各验证入口要观察什么，由 SPEC 说明；实际执行的命令、结果和适用配置，由 VERIFICATION 记录。

## 审查重点

1. 模型失败后已经保存的回答是否重复；重复 requestId 和旧问题 ID 是否分别处理。
2. 结束、刷新与进程重启时 pendingAction 的恢复；报告失败不能伪造完成。
3. 用户资料中的指令不会覆盖系统规则；模型未提供依据时清楚标记资料不足。
4. 删除原始资料不影响历史事实；上传路径隔离，文件和数据库保持一致。
5. 真实模型与 Mock 标识贯穿页面、会话与报告；未配置密钥不静默降级。
