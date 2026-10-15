# 文档职责地图

- 最近审计：2026-10-15。
- 地图 owner：本文件；当前 Release：V1。

| 职责 | 实际路径 | 状态 | 定位依据与备注 |
|---|---|---|---|
| Agent 长期规则 | [AGENTS.md](AGENTS.md) | 已核验 | 中文输出、授权、验证及恢复入口 |
| 运行与使用 | [README.md](README.md) | 已核验 | 前后端依赖、启动、模型配置、测试命令 |
| 当前项目定位 | [PROJECT.md](docs/vibe/PROJECT.md) | 已登记 | 当前 Release、质量标准与范围入口 |
| 当前真实架构 | [TECH_DESIGN.md](docs/vibe/TECH_DESIGN.md) | 已核验 | Java/Vue 入口、数据库、HTTP 契约与模型客户端 |
| 当前界面视觉规范 | [DESIGN.md](DESIGN.md) | 已核验 | 全局 Tokens、页面表面模式、表现层边界与响应式规则；组件扩展由 `.impeccable/design.json` 承载 |
| Impeccable 上下文适配入口 | [PRODUCT.md](PRODUCT.md) | 已登记 | 仅引用现有产品事实源，不替代项目定位与 V1 行为规范 |
| 当前执行进度 | [PROGRESS.md](docs/vibe/PROGRESS.md) | 已登记 | State、当前任务、代码检查点与下一步 |
| 项目决策与授权 | [DECISIONS.md](docs/vibe/DECISIONS.md) | 已核验 | 技术取舍、模型配置、公开仓库与文档整理授权 |
| V1 背景、目标与范围 | [PROJECT_BRIEF.md](docs/vibe/releases/v1/PROJECT_BRIEF.md) | FROZEN | 用户初始冻结需求，未新增产品范围 |
| V1 完整产品行为与验收要求 | [SPEC.md](docs/vibe/releases/v1/SPEC.md) | FROZEN | REQ01～REQ06、AC01～AC11、状态、错误和测试接缝 |
| V1 实施计划 | [IMPLEMENTATION_PLAN.md](docs/vibe/releases/v1/IMPLEMENTATION_PLAN.md) | 已登记 | T0～T7、依赖与需求/验收映射 |
| V1 实际验证与证据 | [VERIFICATION.md](docs/vibe/releases/v1/VERIFICATION.md) | 已归档 | 验收结果、配置适用范围、合成样本与源文件摘要 |

本文件仅作导航，正文事实由对应 owner 维护。后续版本按实际 Release 建立目录；V1 历史验收保留当时输入与配置边界。目标方案、前版变化和独立复盘按需创建，本次未建立空白文件。
