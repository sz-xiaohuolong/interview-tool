# V1 实施计划与进度

目标：交付本机可日常使用的 AI 技术面试助手。规格：[PRD.md](PRD.md)，契约：[ARCHITECTURE.md](ARCHITECTURE.md)。

Release：V1；Stage：Shipping；State：RELEASED（本地交付）；Operational Status：ACTIVE；Verification Status：VERIFIED；Shipping Authorization：本地交付已批准；本次用户已授权创建公开 GitHub 仓库并推送，应用公网部署未授权。

主代理唯一编排。采用原生逐项执行；独立只读审查可并行。Schema/API 先冻结并验证，再交接依赖模块。不额外生成第二套计划。

## 任务及依赖

- [x] T0：检查空仓库、冻结需求、建立四份文档和本地配置模板。
- [x] T1：后端基础与 Repository：schema.sql、Store.java、Models.java、Application.java；真实 SQLite 读写与重启测试。
- [x] T2：资料闭环：MaterialService、MaterialController、文件解析；先测试空白/类型/上限/CRUD，再实现；验证 PDF/DOCX/文本与删除一致性。依赖 T1。
- [x] T3：面试引擎：LlmClient、InterviewService、InterviewController、prompts；先测试规划、追问上限、幂等、失败重试、结束与报告，再实现。依赖 T1、资料上下文契约。
- [x] T4：前端完整页面：App、Dashboard、Configure、Materials、Session、History、Report、api/types/style；依赖已验证 API，覆盖空态/错误/加载、刷新与多行回答。
- [x] T5：针对性独立审查与修复；核对资料删除、未回答报告、跨源写入、内部信息泄露、重启恢复与格式错误。
- [x] T6：实际启动、浏览器闭环、构建与全套测试；按 AC 记录证据；执行真实模型三路径及项目深挖语义验收。
- [x] T7：README、启动脚本、验收脚本与最终状态；保留明确限制，不推送不部署。

## 审查重点

1. 模型失败后已经保存的回答是否重复；重复 requestId 和旧问题 ID 是否分别处理。
2. 结束、刷新与进程重启时 pendingAction 的恢复；报告失败不能伪造完成。
3. 用户资料中的指令不会覆盖系统规则；模型未提供依据时清楚标记资料不足。
4. 删除原始资料不影响历史事实；上传路径隔离，文件和数据库保持一致。
5. 真实模型与 Mock 标识贯穿所有页面、会话和报告；未配置密钥不静默降级。

## 验证与归档入口

[VERIFICATION.md](VERIFICATION.md) 统一维护 AC 矩阵、验证结果、限制与证据索引；本计划维护任务及当前交付进度。

## 文档接管与仓库同步

- 当前 Release：V1，功能本地交付已完成。
- 文档：已整理到 `docs/vibe/releases/v1/`，根目录 `DOCUMENT_MAP.md` 为唯一职责地图。
- 仓库：[sz-xiaohuolong/interview-tool](https://github.com/sz-xiaohuolong/interview-tool)，可见性为 public，同步分支为 `main`。
- Last Stable Commit：本次 V1 首次归档提交；通过 `git log main` 定位，已通过检查的源文件摘要见验证记录。
- 本次检查：业务代码未改动；文档链接与公开文件检查通过，已有后端31项、前端21项及浏览器4项证据按适用范围复用。
- Next Task：按用户后续请求维护；新增产品范围先按工作流确认。
- 运行注意：不要覆盖正在执行的 JAR；日常开发使用 `scripts/dev.sh`，打包运行时先停止再重建。
