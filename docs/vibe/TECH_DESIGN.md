# 当前技术架构与接口约定

2026-10-19更新。应用使用一个Maven后端、一个Vue应用和一份默认Vite配置。v1/v2/v3用于需求与验证文档的迭代归档，不用于内部包、类、文件或运行实现分区。只有用户明确要求灰度或并行版本时才引入相应架构。产品范围见各Release的SPEC，实际结果见对应VERIFICATION。

## 1. 统一职责与目录

后端根包 `cn.interview` 仅保留 `Application`；所有业务均按 `controller`、`service`、`client`、`mapper`、`validation`、`dto`、`entity`、`config`、`exception`、`common` 分层。测试跟随被测职责，不存在版本包或版本前缀类。

| 主要实现 | 职责 |
|---|---|
| `InterviewController` / `InterviewService` | 综合面试的后台准备、纠偏、开考、阶段、计时、追问与查证复盘 |
| `FocusedInterviewController` / `FocusedInterviewService` | 项目深挖、简历单题、关联训练及原格式场次的兼容读写；共用现有项目矩阵和语音组件 |
| `ReviewService`、`PracticeService`、`JdService`、`StoryService`、`DesignPracticeService` | 复习、单题、JD、故事、设计训练各自业务；不是不同应用版本 |
| `OperationService` / `ModelTaskRunner` | 训练操作的幂等、修订号、执行代次、失败恢复与固定模型任务 |
| `OpenAiJsonClient` | 业务模型任务唯一OpenAI结构化请求传输，复用已接入Spring AI 1.1.8；请求限时、无隐式重试、响应大小限制及安全错误处理 |
| `InterviewModelClient`、`FocusedInterviewModelClient`、`TrainingModelClient` | 各业务的提示词、模型参数和输出协议适配；共用上述网络实现，不另建HTTP模型客户端 |
| `InterviewOutputValidator` | 综合面试的结构、阶段、当前快照引用与证据校验；原结构校验和业务校验已合并，共用主题表 |
| `FocusedInterviewOutputValidator` / `TrainingOutputValidator` | 项目/单题报告及训练评估各自的协议校验 |
| `KnowledgeService` / `FixedHttpTransport` / `KnowledgeTransport` | 受控公开文档检索、正文缓存和固定域名下载；下载接口归client层，不反向依赖业务Service |
| `SqliteStore` / `TrainingStore` / `InterviewSessionStore` | SQLite连接与共用资料、训练业务记录、综合面试原子JSON快照；存储格式不同，职责明确，不按版本选择实现 |
| `ApiAdvice` / `ApiException` / `RecoveryException` | 同一异常响应出口，恢复字段在需要时追加；不再维护两套Advice |

没有需要多实现切换的业务服务，保留具体Service类，不增加空ServiceImpl或VO层。Controller接收参数并返回响应，业务规则在Service，SQL在Mapper。共用事务、模型配置及项目证据接口继续复用。

前端只有 `src/App.vue` 和 `src/main.ts`。页面在 `pages/`，展示与操作组件在 `components/`，业务客户端在 `api/`，结构定义在 `models/`，业务路由注册在 `routing/`。`api.ts` 统一处理同源请求、JSON、上传、204、状态和恢复字段；`api/training.ts` 仅增加业务路径及幂等写入，`api/interviews.ts` 绑定综合动作。所有路由由同一router注册，不再切换版本App。

## 2. 项目与单题面试

`FocusedInterviewService` 维护已有三种场次模式和原JSON契约，支持项目资料快照、简历押题后单题模拟、原快速场次及关联训练。创建先保存CREATED和待规划动作，规划成功后进入IN_PROGRESS；回答先持久化，再按题单和追问约束继续；完成进入COMPLETED并生成报告。

模型失败时保存FAILED或INTERRUPTED，回答和请求ID保留，显式重试读取原快照。启动恢复不会自动重新付费调用。所选资料、项目矩阵、原答和报告继续沿用已确认规则，旧历史不重算。

关联训练逻辑已直接合入该Service，使用 `ObjectProvider<TrainingLinkService>` 按需要取得来源服务。`TrainingLinkService` 原子预留场次与来源关系，再复用同一规划逻辑；已删除通过BeanDefinition替换子类的增量接线和重复Service类。

## 3. 数据与资源

| 存储或资源 | 当前用途及兼容边界 |
|---|---|
| `DATA_DIR/interview.db` | 资料、附件索引、项目/单题场次、押题、报告及专项训练表；原五表、`v2_*`表名与数据格式保留 |
| `DATA_DIR/uploads/` | UUID命名的上传资料，按既有边界提取文本 |
| `DATA_DIR/v3/sessions/` | 综合面试JSON快照；路径仅是既有存储契约，不代表版本模块 |
| `DATA_DIR/v3/knowledge/` | 公开技术文档缓存，不含私有资料搜索请求 |
| `DATA_DIR/model-config.json` | 全站模型连接，原子保存、权限0600，不公开密钥 |
| `prompts/` | 项目与单题模型提示词；`prompts/training/`为固定训练任务，`prompts/interview/`为综合面试阶段 |
| `knowledge/sources.json` | 受控公开来源清单，内容未因路径整理改变 |
| `training/skill-catalog.json` | 能力目录，内容和已保存ID保留 |
| `db/migrations/001-training.sql` | 原训练增量结构，内容逐字保留以继续匹配已登记的SHA256校验和 |

本次没有Schema或个人库迁移。已保存的来源标签、模型评估协议版本、错误码和浏览器草稿键保持兼容；这些标识是数据契约，不生成另一套版本实现。

## 4. HTTP与错误契约

API只监听回环地址，经前端同源代理访问。写请求继续受Origin边界保护。时间使用ISO-8601；错误基础字段为 `message`、`code`，有恢复上下文时追加 `currentRevision`、`nextAction`、`resourceId`、`operationId`、`retryable` 等字段。

| 业务 | 当前入口 | 兼容入口与边界 |
|---|---|---|
| 综合准备、列表、详情、动作与删除 | `/api/interviews`、`/api/interviews/status` | `/api/v3/sessions`、`/api/v3/status`直接映射同一Controller和Service，不重复创建、不回退旧面试接口 |
| 专项训练、单题案例、JD、故事、设计训练及操作恢复 | `/api/training` | `/api/v2`直接映射相同业务控制器，维持状态、幂等与恢复协议 |
| 项目/单题及原格式场次 | `/api/sessions` | 原请求和报告路径保留，由FocusedInterviewController处理，不按ID猜测综合来源 |
| 共用资料、健康与模型配置 | `/api/materials`、`/api/health`、`/api/model-config` | 全站共用，不因历史编号停用 |

综合和原场次删除仍为逻辑删除并返回204；故事删除沿用幂等和修订校验，返回200与JSON结果。列表摘要不等于完整场次，前端按真实返回的字段处理。历史记录身份包含来源与ID，相同ID不串接口。

## 5. 模型配置与验证

`ModelConfigurationService` 提供单次请求的不可变连接快照，三类业务适配器直接注入共用配置与同一传输Bean，训练客户端不依赖其他业务客户端；保存不会调用模型，列表和连接探测由用户主动触发。新的追问配置使用 `INTERVIEW_FOLLOWUP_MODEL` 和 `INTERVIEW_FOLLOWUP_REASONING_EFFORT`，旧 `V3_FAST_*` 变量作兼容读取，新配置优先。

`OpenAiJsonClient` 使用Spring AI构造请求，单次最多一次尝试，不执行工具；响应读取中限制2000000字节，拒绝上游重定向，超时和认证/限流错误明确返回且不泄露响应正文。兼容服务未声明响应类型时按JSON解析，结束原因统一为小写；正文仍须通过完整JSON及业务校验。实际用量先报告，再校验正文，避免格式错误掩盖已发生的调用。Mock必须显式配置，缺密钥不降级。

训练协议保留提示词哈希、参数、请求/返回模型与执行元信息，变化后拒绝混组。综合面试保留阶段输出限额和实际用量。只有项目/单题历史协议需要时才接受JSON代码围栏；训练和综合输出继续要求严格JSON。结构通过不证明技术判断正确。

## 6. 专项训练与资料库维护

训练业务共用 `TrainingStore`、`OperationService`、`InputPreparationService`、`ModelTaskRunner` 和固定任务枚举。短事务记录revision、requestId、generation及输入，网络等待在事务外。失败保留operation/resource指针，重启转INTERRUPTED，只有显式重试才再次调用；旧代次成功或失败均不能写回新状态。

`TRAINING_ENABLED` / `app.training.enabled` 表示所选资料库是否已准备好训练结构，不选择不同代码版本；旧 `V2_ENABLED` / `app.v2.enabled` 作为兼容配置。`TrainingSchemaPreflight` 在SqliteStore创建、WAL及恢复写入前只读校验结构；未准备、未知结构或校验和异常时拒绝不安全启动。维护命令为 `scripts/prepare-training.py`，必须显式提供数据、备份目录及维护确认。不会对个人库自动迁移。

App查询训练可用性决定导航和报告工具是否显示，不选择另一套App；综合面试不依赖该探测成功。训练、语音、项目矩阵和原报告功能以原需求与对应验收记录为准。

## 7. 综合面试与入口边界

综合面试是默认能力，不再依赖 `app.v3.enabled` 或版本Profile。`InterviewService` 使用准备/复盘及追问两个有界执行器；revision拒绝过期提交，generation拒绝迟到结果。正常追问结束也更新代次，旧定时器不能影响下一轮；重启保存中断，等待显式恢复，不自动调用模型。

新场次保留四阶段、计划8/12题、不限时训练、20/30/45分钟计时实战、跳过阶段、语音待校对文本、末答保护、项目证据矩阵、原答定位和定向补练。每场最多128次模型尝试、累计400000声明输出上限，为复盘保留最后1次与6500声明输出额度；界面显示服务实际返回的输入/输出Token，缺失部分不估算。旧场次按已保存标识读取，不补算、不增加调用。

知识检索仍仅以受控主题选择固定公开HTTPS来源，不传个人原文，限制DNS、响应大小、重定向和3秒获取期限；缓存48小时并可显式刷新。来源ID与正文哈希、各阶段实际片段绑定；逐字引用不等于语义正确，缺少有效官方依据时降为待核实。

当前前端入口为 `/interviews`、`/interviews/:id` 和 `/history/interviews/:id/replay`。旧 `/interview-v3` 及旧综合回放地址作为同一路由的alias保留；普通 `/configure/quick` 保留参数和锚点进入综合准备，带trainingLinkId的关联训练仍走原业务配置。项目、简历、旧报告与回放继续可用。旧来源锚点可定位当前知识条目，本机草稿键保留历史格式。

共用历史同时读取两种业务列表，按来源与ID处理继续、报告、回放和删除。概览当前仍只统计原格式场次，本次没有改变统计范围，也不把综合摘要的缺失字段强行算成0。

标准验证入口为 `./scripts/test.sh` 与 `bash scripts/e2e.sh`。接口客户端契约、API边界、History路由、新旧别名、幂等与模型异常使用运行测试验证；源码检查只补充命名和职责审查。当前原始验证及缺口见 [本次记录](releases/v3/VERIFICATION.md)，正式版本与真实模型质量结论不因重构改变。
