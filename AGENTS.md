# 开发约定

所有回复、文档、代码注释和用户提示使用简体中文；技术标识符保留其规范拼写。

本仓库采用 vibe-workflow，唯一执行 owner 是当前主代理。V1 冻结需求及文档位于 `docs/vibe/releases/v1/`。技术实现自主决定，不新增产品功能。提交、推送、部署和创建 PR 均须有用户明确授权。

## 恢复入口

先读根目录 `DOCUMENT_MAP.md`，按职责进入现有事实源；只维护这一份权威地图。当前 Release 为 V1，实施进度与验收证据分别由地图中登记的计划和验证记录维护。

启动：`./scripts/dev.sh`；工程检查：`./scripts/test.sh`；浏览器验收：`bash scripts/e2e.sh`。具体环境和参数见 `README.md`。

## 工程与验证

- Java 21、Spring Boot 3、Maven、SQLite；Vue 3、TypeScript、Vite、Tailwind、Vue Router。
- Mock 仅用于明确标识的测试；禁止没有密钥时静默退回 Mock。
- 密钥和本地资料不得提交；只监听回环地址，不开启跨源写入。
- 服务端负责状态约束、资料边界、模型输出验证与回答幂等。
- 行为先写失败测试，按实际风险验证。以真实命令结果记录证据，Mock 不能替代真实模型质量验证。
- `.runtime/`、`.evidence/`、`backend/test-results/` 保存本地原始记录，不提交；公开归档只包含已检查的合成样本、必要摘要和源文件摘要。
