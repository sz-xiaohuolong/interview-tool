# 项目决策与授权记录

## 已有授权

用户要求直接完成 V1，并将附件作为冻结范围；可初始化空目录、实现本地数据库与文件存储、安装项目依赖及运行本地测试。不自动部署、推送或创建 PR。用户随后授权代填指定模型服务配置并开展真实模型验收；密钥仅保存在已忽略、权限为 600 的 `.env.local`。

2026-10-15 后续授权：用户明确要求执行 `vibe-workflow init`、将文档整理到 V1 目录，并在自己的 GitHub 账户下新建公开仓库、提交和推送当前项目。本次沿用 `main`，仓库名称采用工作目录名 `interview-tool`；应用公网部署与创建 PR 不在本次范围。公开提交使用该 GitHub 账户的 noreply 邮箱，保留已配置的作者名称。

首次文档归档沿用已有 PRD、架构、计划和决策正文，迁入 `docs/vibe/releases/v1/`；验收内容从计划拆至 `VERIFICATION.md`，根目录 `DOCUMENT_MAP.md` 为唯一职责地图。当时未补建空白设计、复盘或独立进度文档。公开归档保留已检查的合成 JSON 样本和可读摘要，原始日志、本地资料、数据库与密钥保留在忽略目录。

公开仓库为 [sz-xiaohuolong/interview-tool](https://github.com/sz-xiaohuolong/interview-tool)，主分支为 `main`。仓库创建不改变应用仅本机运行的边界。

2026-10-15：根据服务的 `/models` 列表，将用户简称 `6.1sol` 规范为实际模型 ID `gpt-6.1-sol`。使用正常 HTTPS 验证连接；未修改证书校验。真实验收仅发送合成资料，数据库单独放在 `.runtime/real-qa-data/`；日常数据使用默认 `data/`。

真实项目深挖的五次回答完成后，复盘超过原 60 秒上限；本地配置调整为 180 秒，仅重试原会话报告，保留完整问答与失败记录。

2026-10-15：按用户要求，将当前本地思考强度设为 `high`。新增可选环境变量 `LLM_REASONING_EFFORT`，统一传入四类模型任务的 `reasoning_effort` 字段；留空沿用服务端默认值。后端31项测试与打包通过，真实服务接受携带 `high` 的合成配置测试请求并返回200；本地服务已重启生效。

## 文档职责纠正（2026-10-15）

用户指出首次归档与 skill 推荐结构不一致，要求按标准职责整理。本次将版本背景与范围拆为 [PROJECT_BRIEF.md](releases/v1/PROJECT_BRIEF.md)，完整产品行为与验收归 [SPEC.md](releases/v1/SPEC.md)；版本计划与验证继续独立保留。

当前项目定位、架构、进度分别归 [PROJECT.md](PROJECT.md)、[TECH_DESIGN.md](TECH_DESIGN.md)、[PROGRESS.md](PROGRESS.md)，本文件承接原决策和授权。根目录 [DOCUMENT_MAP.md](../../DOCUMENT_MAP.md) 仍是唯一权威地图；没有新增产品需求或改写历史验收。

`PROPOSED_DESIGN.md` 用于尚未实现的目标方案，V1 已完成，不倒填方案批准历史；首版没有相对前版的变更，无需 `CHANGE.md`；没有独立复盘材料，不创建空白 `RETRO.md`。后续存在相应内容时再创建。

## 技术决定

- 使用 Spring Boot 3.5、Java 21、JdbcTemplate + SQLite；不用 JPA，避免 SQLite 方言与迁移复杂度。首次启动创建表，现有数据不覆盖。
- 会话与报告采用有版本语义的 JSON 快照，核心资源独立表存储；事务保存状态与消息，材料删除不破坏历史快照。
- LLM 使用 Java HttpClient 连接 OpenAI 兼容 `chat/completions`，采用非流式 JSON 输出。规划器、面试官、评估器和押题提示词分离；模型只建议下一步，服务端强制次数与状态边界。
- 后端保留已提交回答，失败标记中断；显式重试恢复同一轮。回答带请求 ID 和最后问题 ID，防重复与旧页面提交。单进程串行变更满足个人使用范围。
- PDFBox 提取文本 PDF；Apache POI 提取 DOCX；UTF-8 读取 Markdown/TXT。不引入 OCR。单文件最大 5 MiB，提取文本最多 24000 字符，模型材料上下文最多 30000 字符，截断明确提示。
- Vue 3 + TypeScript + Vite + Tailwind 4 + Vue Router，采用原生语义控件与轻量 Vue 图标库；不引入全局状态库。页面数据以服务端为准。
- 绑定 127.0.0.1，Vite 同源代理 API；检查写请求 Origin，默认无跨源访问。密钥只在后端环境变量读取，不记录上游响应正文或密钥。
- Mock 必须显式配置，界面、会话与报告带标识；评分仅作测试示意，不表示真实能力。
- 目标时长不强制倒计时，提前结束后仅评估已有回答。无回答明确记录不可评价，不虚构表现。
