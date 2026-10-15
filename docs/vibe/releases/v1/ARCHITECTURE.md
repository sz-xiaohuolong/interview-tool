# V1 架构与契约

## 结构与数据流

`frontend/`：Vue 页面、API 客户端与样式。`backend/`：Controller → Service → Repository。
`MaterialService` 提取并保存资料；`InterviewService` 管理会话；`LlmClient` 统一调用；独立提示词实现规划、提问、评估与押题。SQLite 保存结构化资料和完整会话快照，上传文件使用 UUID 文件名保存到本地 data/uploads。

模型参数由后端环境变量加载。可选 `LLM_REASONING_EFFORT` 非空时作为 `reasoning_effort` 传给规划、面试官、押题与评估四类任务；留空使用服务端默认值。前端弹窗使用 Reka UI，页面业务状态以服务端数据为准。

创建 → 保存 CREATED → 规划 → 首问 IN_PROGRESS → 保存回答及 pendingAction → 模型决策 → 强制最多两次追问 → 下一主问题或 COMPLETED → 统一报告。上游失败保存 FAILED/INTERRUPTED，重试使用已有快照；启动时未完成生成转中断，避免永久加载。

材料、JD、回答都是不可信 JSON 数据，放在用户消息内；系统提示声明不能覆盖规则。服务端验证模型结构、类型、数组边界、评分与依据引用。内部决策和后续题单不通过会话 API 输出。资料修改/删除不改变已开始会话的事实。

## 持久化

- `materials(id, kind, name, content, created_at, updated_at)`：RESUME/PROJECT/JD。
- `documents(id, material_id, name, storage_name, content, truncated, created_at)`：外键级联删除。
- `sessions(id, payload, updated_at)`：完整服务端会话 JSON，包含规划、材料快照、消息、幂等键、阶段、报告状态。
- `predictions(id, resume_id, payload, created_at)`：押题快照，材料删除不会破坏历史会话。
- `reports(session_id, payload)`：统一评估结构。

## HTTP 契约

所有 API 前缀 `/api`，JSON，错误 `{message, code}`。时间为 ISO-8601。正常删除 204。会话创建、回答和重试在上游失败时返回已保存的 Session（HTTP 200，status 为 FAILED/INTERRUPTED 且有 error/pendingAction），便于前端保留 ID 并恢复；报告生成失败保留 reportStatus=FAILED，主动重试失败返回明确 HTTP 错误。

### 资料

Material：`{id,kind,name,content,createdAt,updatedAt,documents:[{id,name,text,truncated,createdAt}]}`。

- GET `/materials?kind=RESUME|PROJECT|JD`：Material 数组，省略 kind 返回全部。
- POST `/materials`：`{kind,name,content}`；PUT `/materials/{id}`：`{name,content}`。
- DELETE `/materials/{id}`。
- POST `/materials/upload`：multipart `kind,name(可选),file`，创建资料并保存文档。
- POST `/materials/{id}/documents`：multipart `file`，添加文档。
- DELETE `/materials/{id}/documents/{documentId}`。

### 押题

- POST `/predictions`：`{resumeId,role,jd}`。
- GET `/predictions?resumeId=...`：按时间倒序的 PredictionSet 数组。
- PredictionSet：`{id,resumeId,role,createdAt,mock,contextTruncated,questions:[{id,question,evidence,knowledgePoint,priority,answerOutline,followUps:[]}],risks:[]}`。

### 会话

- POST `/sessions`：`{mode,role,level,durationMinutes,style,jd,materialId,predictionId,questionId,focus}`。mode 为 QUICK/RESUME/PROJECT；level 为 INTERN/JUNIOR/SENIOR；style 为 NORMAL/PRESSURE；其余可选。
- GET `/sessions`：Session 数组；GET `/sessions/{id}`：Session。
- POST `/sessions/{id}/answers`：`{content,requestId,expectedMessageId}`。
- POST `/sessions/{id}/retry`：重试未完成规划/面试官输出。
- POST `/sessions/{id}/finish`：提前结束，生成报告。
- GET `/sessions/{id}/report`：Report；POST `/sessions/{id}/report`：生成或重试报告。
- Session：`{id,status,mode,role,level,durationMinutes,style,createdAt,updatedAt,endedAt,questionIndex,followUpCount,totalQuestions,currentTopic,messages,mock,error,pendingAction,reportStatus,contextTruncated}`。
- Message：`{id,role,content,questionIndex,followUp,createdAt}`，role 为 ASSISTANT/USER，主问题从 0 编号。
- Report：`{sessionId,summary,dimensions:[{name,score,reason}],questions:[{questionIndex,question,correct:[],incorrect:[],missing:[],uncertain:[],answerOutline,knowledgeExplanation,evidence:[]}],weaknesses:[],suggestions:[],mock,createdAt}`；原始回答从 Session 消息读取，不由模型重写。

### 配置状态

GET `/health`：`{status,provider,configured,model}`，不包含 Key 或 Base URL。

## 验证边界

集成测试使用真实 SQLite 和 HTTP Controller，外部 LLM 由可控替身覆盖成功、非法 JSON、上游错误与延迟。浏览器验证真实服务上的完整工作流与刷新恢复。真实模型配置就绪后额外核验提问、追问和复盘质量。
