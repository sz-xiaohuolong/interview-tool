# 文档职责地图

- 最近审计：2026-10-18。
- 地图 owner：本文件；当前 Release：V1。

| 职责 | 实际路径 | 状态 | 定位依据与备注 |
|---|---|---|---|
| Agent 长期规则 | [AGENTS.md](AGENTS.md) | 已核验 | 中文输出、授权、验证及恢复入口 |
| 运行与使用 | [README.md](README.md) | 已核验 | 前后端依赖、启动、模型配置、测试命令 |
| 当前项目定位 | [PROJECT.md](docs/vibe/PROJECT.md) | 已登记 | 当前 Release、质量标准与范围入口 |
| 当前真实架构 | [TECH_DESIGN.md](docs/vibe/TECH_DESIGN.md) | 已核验 | Java/Vue 入口、数据库、HTTP 契约与模型客户端 |
| 当前界面视觉规范 | [DESIGN.md](DESIGN.md) | 已核验 | 当前生效的全局 Tokens、表面模式、公共组件与响应式规则；组件扩展由 `.impeccable/design.json` 承载，版本任务记录进入对应 Release |
| Impeccable 上下文适配入口 | [PRODUCT.md](PRODUCT.md) | 已登记 | 仅引用现有产品事实源，不替代项目定位与 V1 行为规范 |
| 当前执行进度 | [PROGRESS.md](docs/vibe/PROGRESS.md) | 已登记 | State、当前任务、代码检查点与下一步 |
| 项目决策与授权 | [DECISIONS.md](docs/vibe/DECISIONS.md) | 已核验 | 技术取舍、模型配置、公开仓库与文档整理授权 |
| 面试产品竞品与候选核心能力 | [调研汇总](docs/research/2026-10-15-interview-platforms.md) | 调研完成 | 2026-10-15 的 9 个代表产品、现有能力差距、建议顺序与 REA 静态证据；不构成新版本需求 |
| V1 背景、目标与范围 | [PROJECT_BRIEF.md](docs/vibe/releases/v1/PROJECT_BRIEF.md) | FROZEN | 用户初始冻结需求，未新增产品范围 |
| V1 完整产品行为与验收要求 | [SPEC.md](docs/vibe/releases/v1/SPEC.md) | FROZEN | REQ01～REQ06、AC01～AC11、状态、错误和测试接缝 |
| V1 实施计划 | [IMPLEMENTATION_PLAN.md](docs/vibe/releases/v1/IMPLEMENTATION_PLAN.md) | 已登记 | T0～T7、依赖与需求/验收映射 |
| V1 界面重构设计记录 | [UI_DESIGN.md](docs/vibe/releases/v1/UI_DESIGN.md) | 已归档 | 本次 UI 重构的范围、参考图取舍、页面方案与文件边界；视觉令牌引用根目录规范，实际结果引用 V1 验证记录 |
| V1 实际验证与证据 | [VERIFICATION.md](docs/vibe/releases/v1/VERIFICATION.md) | 已归档 | 验收结果、配置适用范围、合成样本与源文件摘要 |
| V2 已批准目标与最小范围 | [PROJECT_BRIEF.md](docs/vibe/releases/v2/PROJECT_BRIEF.md) | FROZEN / APPROVED | 五项冻结范围、实施顺序及已采纳 D01～D08 |
| V2 行为与验收 | [SPEC.md](docs/vibe/releases/v2/SPEC.md) | FROZEN / APPROVED | 原6组42项AC保留，追加已批准导航展示需求，共7组48项AC、状态和测试接缝 |
| V2 技术设计 | [TECH_DESIGN.md](docs/vibe/releases/v2/TECH_DESIGN.md) | APPROVED / VERIFYING | 批准方案、实际新增接入与保留边界；当前架构同步登记 |
| V2 界面设计记录 | [UI_DESIGN.md](docs/vibe/releases/v2/UI_DESIGN.md) | 已批准 / 已实施 | 导航方案2、四字入口、复盘C布局、前端改动边界与参考图取舍；引用根视觉规范 |
| V2 实施计划与进度 | [IMPLEMENTATION_PLAN.md](docs/vibe/releases/v2/IMPLEMENTATION_PLAN.md) | APPROVED / VERIFYING | 五项已实现；软件与六类固定任务真实校准证据、历史失败及复验；待人工验收 |
| V2 方案评审与修改建议 | [REVIEW.md](docs/vibe/releases/v2/REVIEW.md) | APPROVED / 历史评审 | 8 项工程建议已采纳；16 项任务、28 组测试分支为评审安排，不声称全部已实测 |
| V2 实际软件验证与缺口 | [VERIFICATION.md](docs/vibe/releases/v2/VERIFICATION.md) | PARTIAL | 本轮UI为103后端、62前端及构建，导航与响应式浏览器检查；原15项浏览器和19次模型调用保留适用范围 |
| V2 受限模型校准计划 | [MODEL_CALIBRATION_PLAN.md](docs/vibe/releases/v2/MODEL_CALIBRATION_PLAN.md) | AUTHORIZED / 本轮完成 | 原批及后续停止记录保留；修正后两条阶段复验及内置真实链路完成，无自动重试 |


| V3 冻结目标与范围 | [PROJECT_BRIEF.md](docs/vibe/releases/v3/PROJECT_BRIEF.md) | FROZEN / APPROVED | 个性化综合面试；追加两链路项目矩阵、缺口补练、语音输入及综合面试四阶段与计时 |
| V3 行为与验收 | [SPEC.md](docs/vibe/releases/v3/SPEC.md) | FROZEN / APPROVED | R1～R13、20项验收、新旧场次边界及HTTP扩展契约 |
| V3 技术设计 | [TECH_DESIGN.md](docs/vibe/releases/v3/TECH_DESIGN.md) | 已实施 / 待人工验收 | Spring AI、受控检索、共享矩阵与单题、语音及服务端计时 |
| V3 实施进度 | [IMPLEMENTATION_PLAN.md](docs/vibe/releases/v3/IMPLEMENTATION_PLAN.md) | VERIFYING | S0～S6历史与本轮E0～E6检查点 |
| V3 实际验证与限制 | [VERIFICATION.md](docs/vibe/releases/v3/VERIFICATION.md) | PARTIAL | 软件测试、公开来源、合成真实模型及浏览器证据分别记录 |
| V3 本机运行入口 | [README.md](v3/README.md) | 已接入日常dev | 日常5173/8080使用V3与原资料库；保留独立4173/18081入口 |
| V3 错题复习与原题重练修复 | [修复记录](docs/vibe/releases/v3/REVIEW_PRACTICE_FIX.md) | 局部软件验收通过 | 原题案例、零创建恢复、自动草稿/对比及本次前端验证；不代表V3整体验收 |

本文件仅作导航，正文事实由对应 owner 维护。后续版本按实际 Release 建立目录；V1 历史验收保留当时输入与配置边界。目标方案、前版变化和独立复盘按需创建，本次未建立空白文件。
