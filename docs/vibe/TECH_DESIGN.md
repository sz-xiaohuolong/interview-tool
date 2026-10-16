# 当前技术架构与接口约定

本文说明当前代码中的 V1 模块与默认关闭的 V2 扩展。V1 基础见第 1～5 节，新增接入见第 6 节。产品应当如何工作，见 [产品行为规范](releases/v1/SPEC.md)；实际测过什么，见 [验证记录](releases/v1/VERIFICATION.md)。

## 1. 各模块负责什么

| 位置或模块 | 职责 |
|---|---|
| `frontend/` | Vue 页面、API 客户端和样式；页面业务状态以服务端数据为准 |
| `backend/` | 按 Controller → Service → Repository 分层：接收请求、处理业务、读写数据 |
| `MaterialService` | 提取并保存上传资料 |
| `InterviewService` | 管理面试会话、回答和状态变化 |
| `LlmClient` | 统一调用大语言模型（LLM） |
| 独立提示词 | 分别用于规划、提问、评估和押题 |
| SQLite 与本地文件 | 数据库保存资料和完整会话快照；上传文件以 UUID 文件名存入本地 data/uploads |

模型参数由后端环境变量加载。可选 `LLM_REASONING_EFFORT` 有值时，后端将其作为 `reasoning_effort` 传给规划、面试官、押题和评估四类任务；留空则使用模型服务默认值。前端弹窗使用 Reka UI。

## 2. 一场面试如何运行

1. 创建会话，保存待规划状态 CREATED。
2. 模型规划本场题目并生成首问，会话进入面试中状态 IN_PROGRESS。
3. 保存用户回答及待处理动作 pendingAction，再请求模型决定追问、切题或结束。服务端强制每道主问题最多追问两次。
4. 题目完成或用户提前结束后，进入已结束状态 COMPLETED，生成统一报告。

模型服务失败时，会话保存为 FAILED 或 INTERRUPTED，重试沿用已保存的快照。服务启动时，会把上次未完成的生成标记为中断，供用户恢复，避免页面一直显示加载中。

资料、岗位描述（JD）和回答均作为不可信的 JSON 数据放在用户消息中。系统提示明确要求：这些文本不能覆盖面试规则。服务端还会检查模型返回的数据结构、类型、数组范围、分数和依据引用。

会话 API 不返回模型内部决策和尚未进行的题单。会话使用开始时保存的资料副本，即“资料快照”；之后修改或删除原资料，不改变该场面试的记录。

## 3. 数据如何保存

| 表及字段 | 保存内容与关联规则 |
|---|---|
| `materials(id, kind, name, content, created_at, updated_at)` | 简历 RESUME、项目 PROJECT、岗位描述 JD |
| `documents(id, material_id, name, storage_name, content, truncated, created_at)` | 上传文档；删除所属资料时，按外键关联删除文档记录 |
| `sessions(id, payload, updated_at)` | 完整服务端会话 JSON：规划、资料快照、消息、防重复请求标识、阶段及报告状态 |
| `predictions(id, resume_id, payload, created_at)` | 简历押题快照；删除资料不破坏历史会话 |
| `reports(session_id, payload)` | 统一格式的评估报告 |

## 4. HTTP 接口约定

所有 API 以 `/api` 开头，使用 JSON。时间使用 ISO-8601 格式，错误格式为 `{message, code}`，正常删除返回 HTTP 204。下面的路径省略 `/api` 前缀。

会话创建、回答和重试有一项特殊约定：模型服务失败时，接口仍返回已保存的 Session，HTTP 状态为 200；调用方应检查 status，它会是 FAILED 或 INTERRUPTED，并包含 error/pendingAction。这样前端能保留会话 ID，继续恢复。

报告生成失败时保留 reportStatus=FAILED。用户主动重试报告仍失败时，接口返回明确的 HTTP 错误。

### 4.1 资料

Material 的字段：`{id,kind,name,content,createdAt,updatedAt,documents:[{id,name,text,truncated,createdAt}]}`。

- GET `/materials?kind=RESUME|PROJECT|JD`：返回 Material 数组；省略 kind 则返回全部。
- POST `/materials`：输入 `{kind,name,content}`，创建资料。
- PUT `/materials/{id}`：输入 `{name,content}`，修改资料。
- DELETE `/materials/{id}`：删除资料。
- POST `/materials/upload`：使用 multipart 表单 `kind,name(可选),file`，创建资料并保存文档。
- POST `/materials/{id}/documents`：使用 multipart 表单 `file`，为资料添加文档。
- DELETE `/materials/{id}/documents/{documentId}`：删除指定文档。

### 4.2 简历押题

- POST `/predictions`：输入 `{resumeId,role,jd}`，生成押题。
- GET `/predictions?resumeId=...`：按时间倒序返回 PredictionSet 数组。
- PredictionSet 的字段：`{id,resumeId,role,createdAt,mock,contextTruncated,questions:[{id,question,evidence,knowledgePoint,priority,answerOutline,followUps:[]}],risks:[]}`。

### 4.3 面试会话

- POST `/sessions`：输入 `{mode,role,level,durationMinutes,style,jd,materialId,predictionId,questionId,focus}`。mode 为 QUICK/RESUME/PROJECT，level 为 INTERN/JUNIOR/SENIOR，style 为 NORMAL/PRESSURE；其余字段可选。
- GET `/sessions`：返回 Session 数组；GET `/sessions/{id}`：返回指定 Session。
- POST `/sessions/{id}/answers`：输入 `{content,requestId,expectedMessageId}`，提交回答。
- POST `/sessions/{id}/retry`：重试未完成的规划或面试官输出。
- POST `/sessions/{id}/finish`：提前结束并生成报告。
- GET `/sessions/{id}/report`：读取 Report；POST `/sessions/{id}/report`：生成或重试报告。

主要返回对象：

| 对象 | 字段与说明 |
|---|---|
| Session | `{id,status,mode,role,level,durationMinutes,style,createdAt,updatedAt,endedAt,questionIndex,followUpCount,totalQuestions,currentTopic,messages,mock,error,pendingAction,reportStatus,contextTruncated}` |
| Message | `{id,role,content,questionIndex,followUp,createdAt}`；role 为 ASSISTANT/USER，主问题从 0 编号 |
| Report | `{sessionId,summary,dimensions:[{name,score,reason}],questions:[{questionIndex,question,correct:[],incorrect:[],missing:[],uncertain:[],answerOutline,knowledgeExplanation,evidence:[]}],weaknesses:[],suggestions:[],mock,createdAt}`；原始回答从 Session 消息读取，不由模型重写 |

### 4.4 配置状态

GET `/health` 返回 `{status,provider,configured,model}`，不包含密钥（Key）或模型服务地址（Base URL）。

## 5. 如何验证

集成测试使用真实 SQLite 和 HTTP Controller。外部模型使用可控测试替身，覆盖成功、非法 JSON、模型服务错误和延迟。

浏览器验收检查实际服务中的完整操作流程和刷新恢复。真实模型配置就绪后，还需单独检查提问、追问和复盘质量；测试替身通过不代表模型质量通过。

## 6. V2 新增接入（已实现，整体验证 PARTIAL）

V2 使用 `V2_ENABLED=true`（或 `app.v2.enabled=true`）启用，默认关闭。启动在 Store 实例化及 WAL/恢复写入前执行 `V2SchemaPreflight` 的只读检查；未迁移、未知结构或校验和异常拒绝启动。独立 `scripts/migrate-v2.py` 仅在明确维护参数和显式数据目录/备份目录下迁移，增加14张表而不批量回写旧记录。迁移只在合成临时目录演练，用户数据尚未迁移。

原有源码不改。`V2BootstrapConfiguration` 将唯一 interviewService 的 BeanDefinition 指向同包 `TrainingLinkedInterviewService` 子类；无 trainingLinkId 时直接调用原 super.create。有关联时 `TrainingLinkService` 原子预留旧会话与侧表，并复用原规划器。旧接口、Session schemaVersion=1、三模式及报告仍沿用原实现。

新增 Review/Practice/Jd/Story/DesignPractice 五个业务服务，共用 `V2Store`、`V2Operations`、`V2InputPreparations`、`V2ModelRunner`、`V2LlmClient` 和 `V2OutputValidator`。短事务保存 revision、requestId、generation 与输入快照，网络等待在事务外；失败明确保存 operation/resource 指针，重启转 INTERRUPTED，只有用户显式重试才再次调用。新代次执行期间，旧 HTTP 成功或失败均拒绝写回，并向旧请求返回 V2_OPERATION_SUPERSEDED。六个固定任务共用1个并发名额，读取中限制模型响应为2000000字节，单记录上限2 MiB。

新增接口位于 `/api/v2`，关闭时 `/status` 之外均拒绝；旧 `/api/sessions` 的来源分支也检查开关。状态接口与列表只返回必要摘要和指针，不泄漏完整个人正文或认证信息。各功能路径、请求字段、状态、数据模型详见 [V2 设计](releases/v2/TECH_DESIGN.md)。

前端新增 `vite.config.js` 合并原 `.ts` 配置，以 HTML 入口扩展加载 `src/v2/main.ts`。状态关闭、旧后端或离线时回原 main；开启时注册独立路由并使用 V2App 包装原 AppLayout 和旧页面。五项训练导航、报告工具和来源配置均为新增组件，原 CSS 与原页面保持字节不变，新增样式只作用于 V2 类名。

工程、恢复、合成迁移及浏览器证据见 [V2 验证记录](releases/v2/VERIFICATION.md)。Mock 仅验证软件流程；六类新增任务已有合成真实模型通过样本，故事和阶段编号两次历史结构失败经提示词澄清后复验，失败记录保留。KNOWLEDGE_AGENT 五阶段按记录版本核对，ORDER_SERVICE 建议质量未校准；内置浏览器另通过一条无资料 JD 真实链路。个人库尚未迁移，V1 既有真实证据不扩大为 V2 结论。
