# 当前技术架构与接口约定

2026-10-21更新。应用使用一个Maven后端、一个Vue应用和一份默认Vite配置。v1/v2/v3/v4/v5用于需求与验证文档的迭代归档，不用于内部包、类、文件或运行实现分区。只有用户明确要求灰度或并行版本时才引入相应架构。产品范围见各Release的SPEC，实际结果见对应VERIFICATION。

## 1. 统一职责与目录

后端根包 `cn.interview` 仅保留 `Application`。沿用 `controller`、`service`、`client`、`mapper`、`validation`、`dto`、`entity`、`config`、`exception`、`common` 职责层；面试领域规则归入 `domain/interview`，固定任务执行组件归入 `task`。较大的职责层按实际业务分包，测试跟随被测职责，不存在版本包或版本前缀类。

| 目录 | 内容 |
|---|---|
| `controller/{interview,training,material,model}` | 面试、训练、资料及模型配置请求入口 |
| `service/{interview,training,material,knowledge,model,source}` | 对应业务用例；输入清单和幂等操作属于训练业务 |
| `domain/interview` | `InterviewFlowPolicy`、`InterviewProgress`、`InterviewInputBudget`，不承担 Spring 服务编排或数据库访问 |
| `task` | `ModelTaskRunner`、`ModelTaskHandler`、`ModelCapacity`，负责固定任务执行、处理回调及容量约束 |
| `client/{interview,training,source}` | 业务提示词和模型协议适配，`ModelTask` 随训练协议组织 |
| `client/transport` | 共用模型传输与公开知识下载边界 |
| `client/model`、`client/mock` | 主动连接探测及显式 Mock；Mock 保留在主源码中供已标识的本机测试模式使用 |
| `common/ApiResponses` | 保留原创建响应规则，供训练控制器复用 |

| 主要实现 | 职责 |
|---|---|
| `InterviewController` / `InterviewService` / `InterviewFlowPolicy` | 快速练习与综合面试共用准备、回答、幂等和查证复盘；业务流程策略区分自动首问与四阶段确认开考 |
| `AgentPracticePlanner` | 在原面试Service内安排Agent考点，依据实际展示记录选题、预检复习池及生成覆盖回顾，不继承跨场经历或诊断 |
| `FocusedInterviewController` / `FocusedInterviewService` | 项目深挖、简历单题、关联训练及原格式场次的兼容读写；共用现有项目矩阵和语音组件 |
| `ReviewService`、`PracticeService`、`JdService`、`StoryService`、`DesignPracticeService` | 复习、单题、JD、故事、设计训练各自业务；不是不同应用版本 |
| `OperationService` / `ModelTaskRunner` | 训练操作的幂等、修订号、执行代次、失败恢复与固定模型任务 |
| `OpenAiJsonClient` | 业务模型任务唯一OpenAI结构化请求传输，复用已接入Spring AI 1.1.8；请求限时、无隐式重试、响应大小限制及安全错误处理 |
| `InterviewModelClient`、`FocusedInterviewModelClient`、`TrainingModelClient` | 各业务的提示词、模型参数和输出协议适配；共用上述网络实现，不另建HTTP模型客户端 |
| `InterviewOutputValidator` | 综合面试的结构、阶段、当前快照引用与证据校验；原结构校验和业务校验已合并，共用主题表 |
| `FocusedInterviewOutputValidator` / `TrainingOutputValidator` | 项目/单题报告及训练评估各自的协议校验 |
| `KnowledgeService` / `FixedHttpTransport` / `KnowledgeTransport` | 受控公开文档检索、正文缓存和固定域名下载；下载接口归client层，不反向依赖业务Service |
| `SeedQuestionCatalog` | 校验独立JavaBetter原题索引；快速练习先选能力，再按职级匹配未用素材；具体契约见[V5设计第6节](releases/v5/TECH_DESIGN.md#6-独立题单与技术资料获取2026-10-21) |
| `AgentTopicCatalog` | 加载并校验本地Agent考点目录与参考来源身份；目录用于确定考查范围，不替代官方正文依据 |
| `SqliteStore` / `TrainingStore` / `InterviewSessionStore` | SQLite连接与共用资料、训练业务记录、综合面试原子JSON快照；存储格式不同，职责明确，不按版本选择实现 |
| `ApiAdvice` / `ApiException` / `RecoveryException` | 同一异常响应出口，恢复字段在需要时追加；不再维护两套Advice |

没有需要多实现切换的业务服务，保留具体Service类，不增加空ServiceImpl或VO层。Controller接收参数并返回响应，业务规则在Service，SQL在Mapper。共用事务、模型配置及项目证据接口继续复用。

前端只有 `src/App.vue` 和 `src/main.ts`。`pages/` 按 `home`、`history`、`interview`、`training`、`material` 组织；`components/` 按 `common`、`layout`、`interview`、`training`、`material`、`model` 组织，测试与对应页面、组件同目录。跨业务页面继续复用资料选择、项目矩阵和语音组件，不创建第二套实现。业务客户端在 `api/`，结构定义在 `models/`，业务路由注册在 `routing/`。`api.ts` 统一处理同源请求、JSON、上传、204、状态和恢复字段；`api/training.ts` 仅增加业务路径及幂等写入，`api/interviews.ts` 绑定快速与综合动作。所有路由由同一router注册，原URL与别名不变；API边界检查随目录更新，并断言页面和组件均被实际加载。

## 2. 项目与单题面试

`FocusedInterviewService` 维护已有三种场次模式和原JSON契约，支持项目资料快照、简历押题后单题模拟、原快速场次及关联训练。创建先保存CREATED和待规划动作，规划成功后进入IN_PROGRESS；回答先持久化，再按题单和追问约束继续；完成进入COMPLETED并生成报告。

模型失败时保存FAILED或INTERRUPTED，回答和请求ID保留，显式重试读取原快照。启动恢复不会自动重新付费调用。所选资料、项目矩阵、原答和报告继续沿用已确认规则，旧历史不重算。

关联训练逻辑使用 `ObjectProvider<TrainingLinkService>` 按需要取得来源服务。`service/training/TrainingLinkService` 原子预留场次与来源关系，再经 `FocusedInterviewService.startLinkedSession` 复用同一规划逻辑；公开视图和模型模式通过明确方法取得，不直接访问服务内部字段。重放读取与返回字段保持原行为。已删除通过BeanDefinition替换子类的增量接线和重复Service类。

## 3. 数据与资源

| 存储或资源 | 当前用途及兼容边界 |
|---|---|
| `DATA_DIR/interview.db` | 资料、附件索引、项目/单题场次、押题、报告及专项训练表；原五表、`v2_*`表名与数据格式保留 |
| `DATA_DIR/uploads/` | UUID命名的上传资料，按既有边界提取文本 |
| `DATA_DIR/v3/sessions/` | 综合面试与快速练习JSON快照；路径仅是既有存储契约，不代表版本模块 |
| `DATA_DIR/v3/knowledge/` | 公开技术文档缓存，不含私有资料搜索请求 |
| `DATA_DIR/model-config.json` | 全站模型连接，原子保存、权限0600，不公开密钥 |
| `prompts/focused/`、`prompts/training/`、`prompts/interview/` | 分别用于项目与单题面试、训练固定任务、综合面试与快速练习；此前目录整理保留原文，本轮面试PLAN／FOLLOWUP／REVIEW已接入具体考点与覆盖信息 |
| `knowledge/sources.json` | 受控公开来源清单；此前路径整理未改变内容，本轮已补充Agent官方／一手来源及适用边界 |
| `knowledge/questions/javabetter-agent.json` | 60道核对原题及稳定身份、31项能力映射和本地难度，不含网站答案；未映射29项继续动态出题 |
| `knowledge/agent-interview-topics.json` | 六类各10项、共60个稳定身份的原创Agent考点及参考来源；不作为技术结论的直接证据 |
| `training/skill-catalog.json` | 能力目录，内容和已保存ID保留 |
| `db/migrations/001-training.sql` | 原训练增量结构，内容逐字保留以继续匹配已登记的SHA256校验和 |

此前工程整理没有Schema或个人库迁移。2026-10-20源码能力已在日常库启用，只补建空的 `project_repositories` 关联表，原19张表及资料文件不变。启动前检查允许旧库暂缺这一新增表，已有表、约束、未知结构及训练迁移校验继续核验；维护脚本采用相同边界。已保存的来源标签、模型评估协议版本、错误码和浏览器草稿键保持兼容；这些标识是数据契约，不生成另一套版本实现。

## 4. HTTP与错误契约

API只监听回环地址，经前端同源代理访问。写请求继续受Origin边界保护。时间使用ISO-8601；错误基础字段为 `message`、`code`，有恢复上下文时追加 `currentRevision`、`nextAction`、`resourceId`、`operationId`、`retryable` 等字段。

| 业务 | 当前入口 | 兼容入口与边界 |
|---|---|---|
| 综合准备、列表、详情、动作与删除 | `/api/interviews`、`/api/interviews/status` | `/api/v3/sessions`、`/api/v3/status`直接映射同一Controller和Service，不重复创建、不回退旧面试接口 |
| Agent复习池预检 | `POST /api/interviews/agent-practice-options` | 同一InterviewController／Service按REVIEW计算，只返回数量与开始条件，不创建场次或调用模型 |
| 专项训练、单题案例、JD、故事、设计训练及操作恢复 | `/api/training` | `/api/v2`直接映射相同业务控制器，维持状态、幂等与恢复协议 |
| 项目/单题及原格式场次 | `/api/sessions` | 原请求和报告路径保留，由FocusedInterviewController处理，不按ID猜测综合来源 |
| 共用资料、健康与模型配置 | `/api/materials`、`/api/health`、`/api/model-config` | 全站共用，不因历史编号停用 |

综合和原场次删除仍为逻辑删除并返回204；故事删除沿用幂等和修订校验，返回200与JSON结果。列表摘要不等于完整场次，前端按真实返回的字段处理。历史记录身份包含来源与ID，相同ID不串接口。

## 5. 模型配置与验证

`ModelConfigurationService` 提供单次请求的不可变连接快照，三类业务适配器直接注入共用配置与同一传输Bean，训练客户端不依赖其他业务客户端；保存不会调用模型，列表和连接探测由用户主动触发。新的追问配置使用 `INTERVIEW_FOLLOWUP_MODEL` 和 `INTERVIEW_FOLLOWUP_REASONING_EFFORT`，旧 `V3_FAST_*` 变量作兼容读取，新配置优先。

`OpenAiJsonClient` 使用Spring AI构造请求，单次最多一次尝试，传输层不自动执行工具；源码任务的独立受控执行见第10节。响应读取中限制2000000字节，拒绝上游重定向，超时和认证/限流错误明确返回且不泄露响应正文。兼容服务未声明响应类型时按JSON解析，结束原因统一为小写；正文仍须通过完整JSON及业务校验。实际用量先报告，再校验正文，避免格式错误掩盖已发生的调用。Mock必须显式配置，缺密钥不降级。

训练协议保留提示词哈希、参数、请求/返回模型与执行元信息，变化后拒绝混组。综合面试保留阶段输出限额和实际用量。只有项目/单题历史协议需要时才接受JSON代码围栏；训练和综合输出继续要求严格JSON。结构通过不证明技术判断正确。

## 6. 专项训练与资料库维护

训练业务共用 `TrainingStore`、`OperationService`、`InputPreparationService`、`ModelTaskRunner` 和固定任务枚举。短事务记录revision、requestId、generation及输入，网络等待在事务外。失败保留operation/resource指针，重启转INTERRUPTED，只有显式重试才再次调用；旧代次成功或失败均不能写回新状态。

`TRAINING_ENABLED` / `app.training.enabled` 表示所选资料库是否已准备好训练结构，不选择不同代码版本；旧 `V2_ENABLED` / `app.v2.enabled` 作为兼容配置。`TrainingSchemaPreflight` 在SqliteStore创建、WAL及恢复写入前只读校验结构；未准备、未知结构或校验和异常时拒绝不安全启动。维护命令为 `scripts/prepare-training.py`，必须显式提供数据、备份目录及维护确认。不会对个人库自动迁移。

App查询训练可用性决定导航和报告工具是否显示，不选择另一套App；综合面试不依赖该探测成功。训练、语音、项目矩阵和原报告功能以原需求与对应验收记录为准。

## 7. 综合面试与入口边界

综合面试是默认能力，不再依赖 `app.v3.enabled` 或版本Profile。`InterviewService` 使用准备/复盘及追问两个有界执行器；revision拒绝过期提交，generation拒绝迟到结果。正常追问结束也更新代次，旧定时器不能影响下一轮；重启保存中断，等待显式恢复，不自动调用模型。

综合新场次保留四阶段、计划8/12题、不限时训练、20/30/45分钟计时实战、跳过阶段、语音待校对文本、末答保护、项目证据矩阵、原答定位和定向补练。每场最多128次模型尝试、累计400000声明输出上限，为复盘保留最后1次与6500声明输出额度；界面显示服务实际返回的输入/输出Token，缺失部分不估算。旧场次按已保存标识读取，不补算、不增加调用。

知识检索仍仅以受控主题选择固定公开HTTPS来源，不传个人原文，限制DNS、响应大小、重定向和单请求10秒/并行整组11秒获取期限；缓存48小时并可显式刷新。来源ID与正文哈希、各阶段实际片段绑定；逐字引用不等于语义正确，缺少有效官方依据时降为待核实。

当前前端入口为 `/interviews`、`/interviews/:id` 和 `/history/interviews/:id/replay`。旧 `/interview-v3` 及旧综合回放地址作为同一路由的alias保留；`/configure/quick` 进入快速配置，选择岗位与3/5/8道主问题后直接准备并开考；带trainingLinkId的关联训练仍走原业务配置。项目、简历、旧报告与回放继续可用。旧来源锚点可定位当前知识条目，本机草稿键保留历史格式。

共用首页和历史同时读取两种业务列表，按来源与ID处理继续、报告、回放和删除。现代列表新增sessionType与真实answerCount；首页统计合并成功读取的来源，缺失回答次数单列未知，列表失败明确提示统计不完整。

标准验证入口为 `./scripts/test.sh` 与 `bash scripts/e2e.sh`。接口客户端契约、API边界、History路由、新旧别名、幂等与模型异常使用运行测试验证；源码检查只补充命名和职责审查。当前原始验证及缺口见 [V4记录](releases/v4/VERIFICATION.md)，正式版本与真实模型质量结论不因重构改变。

## 8. 快速流程与专业诊断

`sessionType` 缺省为 COMPREHENSIVE，新快速场次为 QUICK。`InterviewFlowPolicy` 负责配置与自动开始约束，`InterviewProgress` 只对综合场次启用四阶段和计时；共用原 Service、Controller、模型客户端与存储。快速不接收非空个人资料/JD，以岗位构造通用上下文并直接调用PLAN，精确题量后自动首问，无ANALYZE及READY确认。无数据库迁移。

新场次 `strategyVersion=1` 在原FOLLOWUP响应内增加有界diagnosis，不增加调用；服务端核对分类、长度、当前原答连续短引及新追问目标。诊断带问题/回答ID、模型动作和实际动作，在有效generation及ANSWERING状态下与推进一并落盘；公共详情只在REPORT_READY展示。后续追问与复盘可用历史观察，但须重新对照原答及官方依据。原FOLLOWUP输出上限800、REVIEW上限10000保持不变。 实时追问默认HTTP等待30秒、整轮31秒截止；`INTERVIEW_FOLLOWUP_DEADLINE_MS`优先于旧`app.v3.fast-deadline-ms`，整轮上限121秒，原异步确认、取消和迟到保护保留。

固定评测资产位于 `evals/interview-agent/`，原策略Prompt与校验器引用快照固定92261c9。评测的公共机械检查、生产行为测试和人工专业评分分别记录，固定上下文对照不代表多轮动态Agent全部质量。受限真实评测及人工停止点见V4计划和验证记录。

## 9. Agent考点与跨场练习

本轮继续扩展同一`InterviewService`、模型客户端、公开知识边界和JSON快照，不增加依赖、数据库、模型阶段或版本工程。`service/knowledge/AgentTopicCatalog`加载本地目录，已整理架构与规划、上下文与记忆、工具与执行控制、RAG、评测与质量、框架实践／模型接入六类各10项。每项包含稳定ID、能力目标、原创场景、追问方向、评判边界及来源；60项是本轮建设目标，条目齐备不代表语义独立或模型专业性已经通过。

`service/interview/AgentPracticePlanner`在Agent岗位启用；快速配置提供折叠的主题多选和未练优先／复习偏好，空选自动综合。综合面试复用相同目录与练习记录，但只给技术阶段绑定考点，继续受ANALYZE及本场真实资料约束；其他阶段和非Agent岗位保持原流程。PLAN必须返回所选考点的有效、不同`pointId`及对应主题；FOLLOWUP在原调用内接收当前`assessmentPoint`。公开知识检索优先采用所选考点的参考来源，仍按最多6个来源及既有下载边界执行；拿不到正文或相关片段时保留待核实，目录评判边界不充当技术证据。

快速与综合只共享同Agent方向、同职级的考点练习记录。最近10场有效场次用于降低近期重复，全部有效历史用于判断新练／复习及构建复习池；只计实际展示的考点，已问未答计入，计划未问、删除、Mock和无可靠标识旧消息排除。跨场不继承回答、个人经历、诊断或能力画像，不猜测补算旧数据。未练优先池不足时补最久未练并提示，近期没练过不能写成从未练过。

复习预检仅接收岗位、职级、题量和主题，服务端明确按`REVIEW`计算。空池阻止创建；复习数量不足时展示补新数，再由用户明确点击，并在创建中同时提交`acceptReviewFill=true`和`expectedNewSupplementCount`。配置改变或创建被拒绝后撤销确认；创建和准备均独立复验，补新数量变化则重新预检。预检不返回未来考点，默认未练优先不增加确认步骤。

内部`agentPractice`快照保存选题及评判信息，根问题与追问继承同一考点身份；问答公开视图剥离具体考点元数据、未来题目及评判提示。复盘的`topicCoverage`只统计实际展示的不同考点，显示主题、具体点、新练／复习及是否作答，通过`questionId`定位原问；覆盖不等于掌握，旧报告不回填。

考点扩展的独立28次协议见[验收入口](../../evals/agent-topic-coverage/README.md)：先六主题各1次PLAN，用户人工确认后才可执行后续两场、最多22次。它与原24例／96次评测及[跨轮12次发现](../../evals/interview-agent/multiturn/README.md)分别保留输入身份、账本、结果和人工关卡；任何一批结构通过均不替代其他批次或人工专业性判断。

## 10. GitHub 公开仓库源码面试

项目资料增加可选公开仓库关联，存入 `project_repositories`；项目深挖与综合面试共用 `service/source/CodeExplorationService`、`client/source`。准备时锁定默认分支当前 SHA 并持久化，重试、刷新、解除资料关联及复盘不改变本场版本。源码不是候选人个人贡献的证据。

GitMCP 只提供本仓库路径线索；GitHub REST 在固定 SHA 下读取目录/blob，核验摘要及 UTF-8，缓存按仓库与 Commit 隔离。源码探索使用 Spring AI 原生工具，最多4轮模型、8次工具、6文件/片段；`OpenAiJsonClient.exchangeTools` 共用原传输，调用方受控执行工具，`exchange` 严格JSON契约保持。无隐式重试，不执行远程代码。

ANALYZE 不接收源码；PLAN、相关 FOLLOWUP 和 REVIEW 复用本场证据。综合只有项目与相关技术阶段可以引用，备用题同样受限。完全不可用时显式重试或 `POST /api/{sessions|interviews}/{id}/continue-without-source`；部分可用保留证据及警告。未关联仓库的旧行为保持。

进行中只公开仓库、版本、状态和有无源码依据，复盘才展示核验片段与固定行号链接。模型题干由服务端检查已知源码/路径/目标泄漏。源码用量单独保存于 `sourceCode.modelUsage`，未并入原主流程界面合计；可通过 `SOURCE_ENABLED=false` 关闭后续探索。详细限额、文件职责和状态见[V5技术设计](releases/v5/TECH_DESIGN.md)，实际证据见[V5验证](releases/v5/VERIFICATION.md)。

2026-10-21 快速练习追加可选素材身份及参考文档获取详情，接口地址不变；数据、失败状态与前端调用说明见[V5设计第6节](releases/v5/TECH_DESIGN.md#6-独立题单与技术资料获取2026-10-21)。考点与原题ID分别记录，旧数据不回填。

2026-10-21 GitHub版本查询改用轻量分支摘要获取Commit，避免大提交详情触发256KiB限额；共用HTTP读取器保留响应超限原因，GitHub接口区分超限与JSON格式错误。范围、恢复与真实验证见[V5修复记录](releases/v5/VERIFICATION.md#github大提交导致仓库关联失败修复2026-10-21)。
