# 文档职责地图

- 最近审计：2026-10-22。
- 地图 owner：本文件；当前 Release：V1。

| 职责 | 实际路径 | 状态 | 定位依据与备注 |
|---|---|---|---|
| Agent 长期规则 | [AGENTS.md](AGENTS.md) | 已核验 | 中文输出、授权、验证及恢复入口 |
| 运行与使用 | [README.md](README.md) | 已核验 | 前后端依赖、启动、模型配置、测试命令 |
| 截图与演示入口 | [使用说明](README.md#截图与演示专用入口) · [数据隔离设计](docs/vibe/TECH_DESIGN.md#本机演示空间2026-10-22) · [验证与进度](docs/vibe/PROGRESS.md#演示专用入口2026-10-22已完成) | 已本机启用 | 5174/8081 与独立虚构数据，模型配置单独保存，复用单一工程 |
| 当前项目定位 | [PROJECT.md](docs/vibe/PROJECT.md) | 已登记 | 当前 Release、质量标准与范围入口 |
| 当前真实架构 | [TECH_DESIGN.md](docs/vibe/TECH_DESIGN.md) | 已核验 | Java/Vue 入口、数据库、HTTP 契约与模型客户端 |
| 当前界面视觉规范 | [DESIGN.md](DESIGN.md) | 已核验 | 当前生效的全局 Tokens、表面模式、公共组件与响应式规则；组件扩展由 `.impeccable/design.json` 承载，版本任务记录进入对应 Release |
| 资料库界面设计 | [已确认方案与Tokens](DESIGN.md#资料库页面设计2026-10-22已实现已验证) · [合成布局草图](docs/vibe/releases/v5/materials-layout.html) | 已本机启用 / 限定软件验证通过 | 等规格卡片、分类搜索、宽详情抽屉、确认后上传；327前端、类型／构建、15浏览器通过，日常重启及原数据核对通过，详见[V5验证](docs/vibe/releases/v5/VERIFICATION.md#资料库界面实施2026-10-22) |
| Impeccable 上下文适配入口 | [PRODUCT.md](PRODUCT.md) | 已登记 | 仅引用现有产品事实源，不替代项目定位与 V1 行为规范 |
| 当前执行进度 | [PROGRESS.md](docs/vibe/PROGRESS.md) | 已登记 | State、当前任务、代码检查点与下一步 |
| 项目决策与授权 | [DECISIONS.md](docs/vibe/DECISIONS.md) | 已核验 | 技术取舍、模型配置、公开仓库与文档整理授权 |
| 面试产品竞品与候选核心能力 | [调研汇总](docs/research/2026-10-15-interview-platforms.md) | 调研完成 | 2026-10-15 的 9 个代表产品、现有能力差距、建议顺序与 REA 静态证据；不构成新版本需求 |
| 五款指定产品浏览器实查与后续候选 | [浏览器补充调研](docs/research/2026-10-16-five-interview-products-browser-review.md) | 登录补查完成，范围有限 | 四站已登录；快面一场合成文字模拟及报告、牛客项目改写与纠正；对照已实现 V2 更新五项候选和文档阶段提示词，不构成实施授权 |
| V6 投递管理 MVP | [需求](docs/vibe/releases/v6/SPEC.md) · [方案](docs/vibe/releases/v6/TECH_DESIGN.md) · [计划](docs/vibe/releases/v6/IMPLEMENTATION_PLAN.md) · [验证](docs/vibe/releases/v6/VERIFICATION.md) | 已日常本机启用 / 软件闭环 VERIFIED | 六组冻结功能与十二项验收通过；420后端／8跳过、339前端及构建、8投递浏览器及16原业务回归；真实重启与原数据摘要一致。不含岗位搜索或 AI 联动 |
| V6 国内岗位搜索聚合（长期路线） | [SPEC.md](docs/vibe/releases/v6/SPEC.md) · [RESEARCH.md](docs/vibe/releases/v6/RESEARCH.md) · [岗位搜索设计方案](DESIGN.md#岗位搜索页面设计方案2026-10-22待最终确认) | 长期需求保留 / 视觉方案待最终确认 / 接入待验证 / 未开发 | 国内技术岗；首版目标为 BOSS、猎聘、智联。单组关键词＋单城市、后端搜索、分批结果、四项筛选、明确同岗折叠、收藏与 JD 保存；开发者维护访问配置，用户无感。三站真实搜索、翻页和详情通过后才能进入完整开发；正式 Release 仍 V1 |
| V1 背景、目标与范围 | [PROJECT_BRIEF.md](docs/vibe/releases/v1/PROJECT_BRIEF.md) | FROZEN | 用户初始冻结需求，未新增产品范围 |
| V1 完整产品行为与验收要求 | [SPEC.md](docs/vibe/releases/v1/SPEC.md) | FROZEN | REQ01～REQ06、AC01～AC11、状态、错误和测试接缝 |
| V1 实施计划 | [IMPLEMENTATION_PLAN.md](docs/vibe/releases/v1/IMPLEMENTATION_PLAN.md) | 已登记 | T0～T7、依赖与需求/验收映射 |
| V1 界面重构设计记录 | [UI_DESIGN.md](docs/vibe/releases/v1/UI_DESIGN.md) | 已归档 | 界面重构历史与2026-10-21全站工作台迭代；视觉令牌引用根目录规范，实际结果引用V1验证记录 |
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
| V3 行为与验收 | [SPEC.md](docs/vibe/releases/v3/SPEC.md) | FROZEN / APPROVED | R1～R17、32项验收、新旧场次边界及HTTP扩展契约；8/12题灵活规划、新版复盘补练、显式阶段跳过、实际Token与全站模型配置 |
| V3 技术设计 | [TECH_DESIGN.md](docs/vibe/releases/v3/TECH_DESIGN.md) | 已实施 / 待人工验收 | Spring AI、受控检索、共享矩阵、分组复盘与单题补练、语音及服务端计时 |
| V3 实施进度 | [IMPLEMENTATION_PLAN.md](docs/vibe/releases/v3/IMPLEMENTATION_PLAN.md) | VERIFYING | S0～S6历史与本轮E0～E6检查点 |
| V3 实际验证与限制 | [VERIFICATION.md](docs/vibe/releases/v3/VERIFICATION.md) | PARTIAL | 软件测试、公开来源、合成真实模型及浏览器证据分别记录 |
| 全站本机运行入口 | [README.md](README.md#综合面试) | 已统一接线 | 业务模块共用backend、App和5173/8080；版本专用脚本已删除，旧HTTP地址作为同一实现的兼容别名 |
| V3 错题复习与原题重练修复 | [修复记录](docs/vibe/releases/v3/REVIEW_PRACTICE_FIX.md) | 局部软件验收通过 | 原题案例、零创建恢复、自动草稿/对比及本次前端验证；不代表V3整体验收 |

| V4 专业Agent与快速练习 | [需求](docs/vibe/releases/v4/PROJECT_BRIEF.md) · [行为](docs/vibe/releases/v4/SPEC.md) · [设计](docs/vibe/releases/v4/TECH_DESIGN.md) · [计划](docs/vibe/releases/v4/IMPLEMENTATION_PLAN.md) · [验证](docs/vibe/releases/v4/VERIFICATION.md) | VERIFYING / WAITING_HUMAN / PARTIAL | 追加六类60考点及跨场练习已实现；最新标准后端350通过／7跳过、282前端及构建，续跑工具21项通过；原12项浏览器证据仍适用。首批18题已获人工总体通过，续跑第一场复盘上游408后停批；原24例完整基线、人工评分及新策略对照仍未完成 |
| Agent考点目录与独立验收 | [60项目录及逐项引用](backend/src/main/resources/knowledge/agent-interview-topics.json) · [28次协议与实际结果](evals/agent-topic-coverage/README.md) | 17/28次已尝试 / STOPPED_ON_FAILURE | 首批6次PLAN及18题获人工总体通过；新增1 PLAN、9 FOLLOWUP通过，第一场复盘HTTP 408，第二场未启动，剩余11次未发送。已知107230 Token、失败用量未知；原24例及独立12轮账本保持独立 |
| 跨轮发现与追问优先级改进 | [目标、验收与执行结果](evals/interview-agent/multiturn/README.md#12-未解风险和矛盾追问优先级) · [剧本与逐例验收表](evals/interview-agent/multiturn/CASES.md) | 最小实现完成 / 固定历史质量PARTIAL | 未加记忆；18份真实配对中C02、Q03改善，原C04未通过，两项明确同路径控制另行通过。Token增加20.9%、平均耗时增加1355毫秒；标准后端352通过／7跳过、前端282及构建通过。原发现及V4账本独立，完整场次未验收 |

| GitHub 公开仓库源码面试 | [冻结范围](docs/vibe/releases/v5/SPEC.md) · [实现设计](docs/vibe/releases/v5/TECH_DESIGN.md) · [实施记录](docs/vibe/releases/v5/IMPLEMENTATION_PLAN.md) · [验证](docs/vibe/releases/v5/VERIFICATION.md) · [大提交关联修复](docs/vibe/releases/v5/VERIFICATION.md#github大提交导致仓库关联失败修复2026-10-21) | 已本机启用 / 限定证据VERIFIED | 单项目固定Commit、两条面试及复盘引用；后端376通过／8跳过、前端289及构建、浏览器46项；真实两种规划8道源码主问核对通过，完整真实问答复盘未测；持续授权同步origin/main，正式Release仍V1 |

| V5 独立题单与获取详情优化 | [需求Q1～Q4](docs/vibe/releases/v5/SPEC.md#独立题单映射与覆盖验证2026-10-21) · [设计与接口](docs/vibe/releases/v5/TECH_DESIGN.md#6-独立题单与技术资料获取2026-10-21) · [映射与缺口](backend/src/main/resources/knowledge/questions/README.md) · [本轮验证](docs/vibe/releases/v5/VERIFICATION.md#独立题单映射与覆盖验证2026-10-21) | 有限闭环VERIFIED | 原60考点不改，60道素材覆盖31项、29项动态回退；386后端通过／8跳过、294前端及构建、8浏览器；真实1次5题规划核对通过，完整真实问答/复盘未测，日常重启与原数据核对通过 |

| 专项训练收敛与错题重练 | [需求W1～W8](docs/vibe/releases/v5/SPEC.md#错题重练极简流程2026-10-22) · [设计及接口](docs/vibe/releases/v5/TECH_DESIGN.md#7-错题重练工作台与删除2026-10-21) · [验证](docs/vibe/releases/v5/VERIFICATION.md#错题重练极简流程2026-10-22) | 已本机启用 / 软件闭环VERIFIED | 三种练习状态及题目提示／作答／原位反馈，移除确认与补证界面；旧地址、草稿、来源隔离、追问和删除保留。311项前端及构建、17项浏览器通过，最终4项复验通过；日常重启与原数据核对通过，无真实模型调用。 |
| 项目经历编辑与新建草稿保护 | [需求ST1～ST2](docs/vibe/releases/v5/SPEC.md#项目经历编辑与新建草稿保护2026-10-22) · [实现与接口](docs/vibe/releases/v5/TECH_DESIGN.md#8-项目经历编辑与新建草稿保护2026-10-22) · [验证](docs/vibe/releases/v5/VERIFICATION.md#项目经历编辑与新建草稿保护2026-10-22) | 已本机启用 / 软件闭环VERIFIED | 引用修改直接保存为用户编辑，原引用与历史保留；两种新建模板分别暂存，切换／刷新恢复。后端398通过／8跳过，前端319及构建、18项浏览器及4项复验通过；日常数据核对一致，无真实模型调用。 |

本文件仅作导航，正文事实由对应 owner 维护。后续版本按实际 Release 建立目录；V1 历史验收保留当时输入与配置边界。目标方案、前版变化和独立复盘按需创建，本次未建立空白文件。
