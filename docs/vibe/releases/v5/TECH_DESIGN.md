# 源码面试技术设计

2026-10-20。本文记录本轮已实现的接入方式；验收范围和结果见 [验证记录](VERIFICATION.md)。V5 仅为文档归档，应用仍是一个工程。

## 1. 接入选择

使用 GitMCP 查找文件路径，以 GitHub REST 核验公开仓库和固定版本的真实内容，再由 Spring AI 原生工具调用完成有界探索。原严格 JSON 面试任务保持独立。

隔离 POC 使用 Spring AI 1.1.8 对应的 MCP SDK，成功动态连接 Petclinic 与本项目。Petclinic 的类、方法查询能返回文件路径及 Commit 链接，但没有源码正文和行号；本项目的一次查询没有命中。因此搜索结果只能作为线索，不能直接登记为证据。只调用发现的 `search_*_code`，不开放服务提供的通用 URL 抓取工具。GitMCP 不可用或无命中时，明确记录警告，使用本场固定目录匹配。

## 2. 资料与版本

`MaterialController → MaterialService → SqliteStore` 沿用现有资料链路。项目可传入可选 `githubUrl`，规范化为一个公开 GitHub 仓库。关联保存在新增 `project_repositories` 表，删除资料时级联删除；原资料表和旧 JSON 字段保持兼容。启动前只读检查允许旧库缺少该新增表，之后由现有 `SqliteStore` 初始化补建；已经存在的关联表仍严格校验结构。专项训练维护脚本采用相同兼容边界。非法地址拒绝保存；合法地址暂时无法核实公开状态时保存失败关联，准备时可以再次核实。

新场准备时重新取得默认分支当前 SHA，立即写入本场快照。关联时取得的 SHA 不是新场的版本。此后显式重试、刷新、继续和复盘都读取该快照；解除资料关联也不修改旧场。综合场次已保存源码关联后，不能原地更换主项目。

## 3. 读取、工具和证据

| 实现 | 职责 |
|---|---|
| `GitHubCodeClient` | 规范地址、公开状态、默认分支 SHA、目录、blob 摘要与 UTF-8 核验、原子缓存 |
| `GitMcpSearchClient` | 动态连接固定 GitMCP 服务，只提供当前仓库路径线索 |
| `SourceToolModelClient` | 独立原生工具模型任务；手工校验名称、次数、结束原因，使用 `ToolCallingManager` 执行 |
| `CodeExplorationService` | 本场快照、工具限制、状态、证据身份、题干与引用校验、公开摘要与报告引用 |

工具仅有 `listFiles`、`searchCode`、`readSource`。模型不能传入仓库、SHA、外部地址或执行命令。目录条目必须属于本场固定 SHA；blob 用 Git SHA-1 对象摘要核验，缓存按仓库和 Commit 隔离，读缓存时再次核验。损坏缓存删除并明确报错，不把内容当作有效代码。

| 边界 | 实际限制 |
|---|---|
| GitHub 目录响应／单文件 | 2,000,000 字节／96 KiB |
| 允许目录项／单次列表 | 1500／150 个路径 |
| 单次准备探索 | 最多 4 轮模型请求、8 次工具调用、6 个不同文件、6 个证据片段 |
| 单片段 | 最多 80 行、2400 字符 |
| 单轮模型 | 声明输出最多 2500 Token、HTTP 等待 30 秒 |
| 工具时间 | 从工具对象建立起 100 秒内允许发起调用；已发起网络请求仍受各自超时约束，不声称整场硬性 100 秒截止 |
| GitHub／GitMCP | GitHub 每请求 10 秒；GitMCP 连接 3 秒，初始化及请求各 6 秒，累计文本最多 24000 字符 |

隐藏路径、依赖、构建产物、明显敏感文件和目录、非允许扩展名、符号链接及二进制不读取。正文只允许 UTF-8。构建文件和 Markdown 可作线索，但不登记成实现证据。方法声明行号是同一已核验文件的有限文本导航，不是 AST 分析，也不会把未读方法体登记成片段。取得三个片段不会自动结束探索，避免只读到版权头和 import。

证据身份包括仓库、SHA、路径、行范围；代码内容由工具登记，模型生成的引用只能指向当前证据。`goal` 是内部拟考查目标，不是代码事实。仓库、注释、文档、工具返回均是不可信资料，不得修改权限或推断个人贡献。

## 4. 两条面试链路

- 项目深挖使用 `/api/sessions → FocusedInterviewService → FocusedInterviewModelClient`，准备、出题、追问、复盘共用本场证据。
- 综合面试使用 `/api/interviews → InterviewService → InterviewModelClient`。源码准备先于 ANALYZE；ANALYZE 不接收源码，避免开考摘要泄露内部目标。PLAN 接收证据，仅 PROJECT 与相关 TECHNICAL 阶段允许引用，备用题同样受限；FOLLOWUP 仅复用本场证据，不再联网；REVIEW 不重新读取 HEAD。
- 两者均新增 `continue-without-source` 动作；综合旧别名映射同一实现，前端不切换旧接口。

源码状态为 PENDING、READING、READY、PARTIAL、FAILED、SKIPPED。没有任何有效片段时保存 FAILED，必须显式重试或选择仅用资料继续；有片段且发生部分失败时保留 PARTIAL，并显示限制。关闭 `SOURCE_ENABLED` 后，关联场次同样提供显式选择；没有关联时原流程不增加源码模型调用。

公开答题详情只返回仓库摘要及 `hasCodeEvidence`。服务端拦截代码围栏、源码链接、实现语句、已知路径及明确内部目标泄漏；即时追问、主问和备用题使用同一边界。该规则不能证明所有自然语言暗示都已消除，语义仍需人工核对。报告追加固定证据与实际展示过的问题引用，前端重新构造经过校验的固定 SHA/行号链接。

## 5. 用量与兼容

`OpenAiJsonClient.exchange` 继续使用严格 JSON；`exchangeTools` 走同一传输，但不设置 JSON response format。两者都关闭框架自动工具执行、没有隐式重试。只有源码任务的受控循环会执行允许工具。

源码尝试及返回用量保存在本场 `sourceCode.modelUsage`，与原面试 `usage` 独立；当前界面的主流程 Token 合计不含这部分。4 轮限制是每次准备探索的上限，用户显式重试会新增尝试，已有片段仍保留。真实验收独立记录完整账本，失败未返回用量时保留未知。后续如统一界面用量，应明确这两个账本的统计口径。

本轮没有重构 InterviewService、创建版本包或重算旧报告。2026-10-20日常启用时仅补建空关联表，原表、资料文件、历史和模型配置均保持。公开源码缓存属于本机数据，不进入版本控制。


## 6. 独立题单与技术资料获取（2026-10-21）

原 `AgentTopicCatalog` 及60项资源保持。新增 `SeedQuestionCatalog` 加载 `knowledge/questions/javabetter-agent.json`，启动时校验稳定题目ID、原题URL、难度、考点引用和唯一性；素材不包含答案。60道素材覆盖31个考点，逐项及29个缺口见[素材清单](../../../../backend/src/main/resources/knowledge/questions/README.md)。

快速练习由 `AgentPracticePlanner.plan` 先选择能力考点，再按职级优先参考匹配素材（实习基础、初级中等、中高级进阶，缺少相同难度时选最接近项）。本场素材不重复；读取同Agent岗位方向、同职级、非Mock、未删除历史中实际展示的素材，排除已用项；规划后尚未展示的不算历史。没有素材或已经耗尽时保留原动态出题。复习仍复习能力而不强制复用素材。历史相同题干在规划落盘前拦截，不能以此声称已自动判别全部语义重复。

PLAN输入在所选 `agentPractice.points[]` 中新增可选 `seedQuestion`（ID、题名、链接、难度和映射），要求模型参考题意生成且返回相同 `seedQuestionId`；服务端拒绝伪造或缺失身份，最终元数据只从服务端所选素材复制。综合面试保持原四阶段和考点绑定，此轮只在QUICK挑选原题素材。

### API输出与存储兼容

继续使用 `POST /api/interviews`、`GET /api/interviews/{id}`、`POST /api/interviews/{id}/answers` 与 `/finish`，无新增请求参数。旧 `/api/v3/sessions` 映射同一控制器；失败不改调 `/api/sessions`。

- 内部规划题及实际面试官消息追加可选 `seedQuestionId`、`seedQuestionTitle`、`seedQuestionUrl`、`seedQuestionDifficulty`；追问继承主问。`questionId`保持本场问答身份，`pointId`保持能力身份。答题期间公开视图移除全部内部考点及素材字段。
- `REPORT_READY` 的 `report.topicCoverage.items[]` 同时提供原 `pointId`、本场 `questionId/rootQuestionId` 和上述可选素材字段。例：`{"pointId":"workflow-autonomy-boundary","questionId":"本场问答ID","seedQuestionId":"jb-workflow-vs-agent","seedQuestionDifficulty":"MEDIUM"}`。只统计实际展示记录；没有素材字段时前端显示动态出题或历史未关联，不强制回填旧记录。
- 场次新增可选 `knowledgeRetrieval[]`：`sourceId/title/url/status/message/cacheUsed/stale/attemptedAt` 及可选 `httpStatus`，包括未取得正文的来源。准备与复盘在模型调用前保存当次状态，即使后续模型失败仍可回查，参考文档详情展示准确原因；`sources[].retrieval`保存该份正文对应的获取状态，只有有正文的来源才进入引用列表。
- 获取状态为 `FETCHED`、`CACHED`、`HTTP_ERROR`、`TIMEOUT`、`NETWORK_DISABLED`、`INVALID_CONTENT`、`LIMIT_EXCEEDED`、`NETWORK_ERROR`。例：`{"sourceId":"官方来源ID","status":"HTTP_ERROR","httpStatus":503,"message":"参考文档返回HTTP 503","cacheUsed":true,"stale":false}`，对应主提示“已使用本地缓存”。无正文时不伪造成功引用，主提示“本次未获取到参考文档”。

`KnowledgeService`继续受控域名、2MiB、48小时TTL和最多六篇资料；完整单请求10秒、并行整组11秒，无自动重试。有效缓存直接复用；失败时按真实有效性回退，过期状态仍阻止以旧资料直接判错。没有新增依赖、表、数据迁移或外部采集；状态保存在原JSON快照中，旧场继续读取，前端缺少新字段时保持兼容。
