# V3 行为与验收

状态：FROZEN / APPROVED，2026-10-17。需求来源为本会话 Q1～Q17 及用户逐条修订，最新修订优先。

## 行为

R1 配置：岗位、职级必填；简历与 JD 可选，可选择资料库已有 RESUME/JD，也可输入 JD。无资料明确为通用基准摸底。每场保留开始时的资料快照、截断状态、版本摘要与更正，旧资料变动不改旧会话。

R2 准备：创建后立即返回任务；排队、分析简历、检索知识、准备题目分别显示。可离开或刷新恢复。模型只分析用户确实写出的业务场景、职责、选型、异常和实操边界，疑点列为待澄清，不推断为实际经历。失败保留快照，用户显式重试。服务重启标为中断，不自动付费续跑。

R3 纠偏与确认：准备好显示技术栈、个人职责、待澄清点、考查方向，可一次确认开考；不泄漏未问问题或参考答案。更正仅用于本场；更换岗位/JD/简历或更正会增加代次并重做受影响准备，旧结果不可覆盖新结果。

R4 知识：受控公开来源；题库用于发现考点，官方文档用于核对技术事实。固定主题键选择来源，不传个人原文作为网络 query；每条片段保存 URL、标题、技术版本、获取时间、摘要哈希、用途、节选。允许缓存、手动刷新及按需补查。来源不可达、过期、无相关片段或冲突均明确标记，不假冒查证成功，不增加收费搜索。

R5 对话：后台准备生成主问题和相关备用问题；每个问题绑定来源和简历/JD证据，通用题明确标记。面试中只向客户端返回已经问出的题。提交答案先保存再生成；0.5秒内思考状态，常态目标3～5秒，截止预算小于8秒，为轮询/渲染留余量。正常按答案的具体缺口追问；超时选相关且未问过的校验备用题并标记，否则 INTERRUPTED，用户可重试或下一题。每道主问题最多追问两次；同一 requestId 幂等，旧问题/旧代次提交拒绝。不能以重复请求增加调用或重复记录答案。

R6 复盘：主动结束后异步终审核实。逐题区分回答不足、事实错误、资料/依据不足与来源冲突；关键技术结论附具体来源片段。不得将题库答案当唯一方案；不得将未问/未答判错。来源缺失的技术判定降为待核实，实际经历只对用户快照核对。保留原始问答与重试入口。

R7 成本：阶段可选择同服务中的快模型/低思考强度，准备复盘保持深度。一次任务无隐藏自动重试。预算按任务与输出上限限制，不把token上限称为实际费用。每场至多32次模型尝试，达到上限明确停止；准备2次、每轮至多1次、复盘1次，显式重试也计数；输出声明上限累计不超过100000 tokens。未知费用标为未知。

## HTTP 契约

所有路径以 `/api/v3` 开始。错误沿用 `{code,message}`，写入沿用本机 Origin 限制。`GET /status` 返回 enabled、provider、configured、model，不返回 Key 或服务地址。

- `GET /sessions`：摘要数组，最近在前；`GET /sessions/{id}`：公共会话。
- `POST /sessions`：`{requestId,role,level,resumeId?,jdId?,jd?,durationMinutes?}`，立即返回持久化任务。
- `POST /sessions/{id}/corrections`：`{requestId,expectedRevision,correction?,role?,level?,resumeId?,jdId?,jd?,durationMinutes?}`；至少含有效更正或配置调整；只允许准备完成/失败/中断且尚未开考，返回重新准备的任务。
- `POST /sessions/{id}/confirm`：`{requestId,expectedRevision}`；READY时开考。
- `POST /sessions/{id}/answers`：`{requestId,expectedRevision,expectedQuestionId,content}`；立即保存并返回 ANSWERING，稍后读取结果。
- `POST /sessions/{id}/next`：中断时明确跳至下一道准备主问题。
- `POST /sessions/{id}/finish`：开始后台复盘；没有作答时返回样本不足，不编造评估。
- `POST /sessions/{id}/retry`：仅失败/中断任务，保留原输入，显式增加尝试；成功结果读取而不重调。
- `POST /sessions/{id}/cancel`：停止后续调用和写回，保留已提交内容；正在远程执行的请求不承诺费用退还。
- `POST /sessions/{id}/refresh`：开考前重新获取受控来源并准备，不改旧场来源。

公共会话包含 allowedActions（当前可执行动作数组）/id/status/phase/revision/role/level/resumeId/jdId/durationMinutes/mock/createdAt/updatedAt/summary/messages/sources/warnings/error/report/usage/metrics。summary 为 `{technicalStack:[],responsibilities:[],clarifications:[],focus:[]}`；messages 每条含 `{id,role,content,questionId,createdAt,fallback?}`。sources 每条含 `{id,title,url,version,fetchedAt,sha256,excerpt,kind,topics:[]}`。report 为 `{summary,findings:[{questionId,assessment,status,sourceIds:[],sourceQuote?,suggestion}],...}`。内部资料原文、未问题单、提示词不进入公共会话。

## 验收矩阵

| AC | 可观察要求 |
|---|---|
| 01 | 两岗位与职级必填，简历/JD可选，选择实际传入并快照 |
| 02 | 异步准备立即返回，真实阶段、离开刷新恢复、失败与重启可恢复 |
| 03 | 有简历的摘要和题目绑定实际职责/业务证据；无简历不虚构 |
| 04 | 摘要可纠偏、配置变更重准备、确认直通、旧代次不写回 |
| 05 | 公开来源受控且隐私不外发；版本、时间、片段、哈希可回查 |
| 06 | 准备预取/本地检索/复盘核实，缓存与刷新有效，失败明确降级 |
| 07 | 0.5秒反馈、常态3～5秒目标、8秒截止、相关备用与无备用恢复 |
| 08 | 回答幂等/过期拒绝、两次追问上限、取消和失败不丢已答 |
| 09 | 异步复盘逐题有据，缺失/冲突不强判错、不评未答题 |
| 10 | 模型次数/输出预算与阶段策略真实执行，无隐藏重试/Mock |
| 11 | 旧功能行为保持，保留任务开始前已有修改；原工程测试和V3测试通过（10月18日已批准修改相关代码） |
| 12 | 合成对照验证岗位与职责敏感性、真实模型质量及延迟有实测证据；缺口如实说明 |


## 2026-10-18 追加行为

R8 项目证据矩阵：新建项目深挖及 V3 综合面试确认一个本场主项目。七项为业务背景与指标、个人真实职责、架构与调用链路、关键技术选型依据、异常与故障处理、性能/压力证据、妥协与替代方案。准备可参考七点，面试不强求全覆盖。复盘逐项显示已讲清/待补强/未覆盖、简短理由、可核对的真实问题和回答证据。只评价已提交文字；未覆盖不能视为能力不足，技术来源可靠不能替代回答证据。无真实项目时明确假设场景且不生成真实经历矩阵。

R9 定向补练：缺口入口生成结合本场主项目的一道新问题，进入已有 `/practice/:id` 单题页面。保存来源场次/检查点/上下文快照，关联后续尝试与结果；不回写原矩阵或篡改原报告，不把补练当时的表现冒充原场表现。刷新恢复已有补练，防止重复创建和重复调用。

R10 语音输入：浏览器内置识别，点击开始/停止听写，最终识别文字进入答案框并可修改，必须用户手动提交；保留打字。应用仅保存提交文字，不存音频或下载模型，不实现全双工。浏览器可能在线处理当次语音，入口明确告知；不可用、拒绝授权或错误时显示原因并保留文本。离开页面/提交/计时到点停止识别，迟到事件不能覆盖草稿或提交。

R11 四阶段：V3 新场次固定顺序为开场介绍→项目深挖→岗位技术与场景→候选人反问，岗位影响内容。正常完成覆盖四阶段；接近尾声减少追问，给尚未完成阶段尤其反问预留空间。用户可提前结束，报告显示阶段完成情况，未完成不记为答错。训练保留 5/10/20 的短练习配置作为题数强度；阶段至少各有一次作答机会。

R12 计时：TRAINING 不限时，TIMED 提供 20/30/45 分钟，默认30。确认开考开始计时，阅读/思考/打字/听写和修改确认计时；准备/复盘/模型生成/停止听写后的识别等待/系统故障等待不计时。实战不提供主动暂停，刷新和切后台不重置。到点停止新题及听写并保留草稿，允许最后一答校对和手动提交后收尾；不自动替用户提交。阶段预算只提醒与引导收尾，不机械切断作答。

R13 历史兼容：创建时写入本轮功能版本；无标识的旧场次保留原状态、题单、历史报告和调用行为，禁止在读取时补矩阵、补阶段或自动重算。语音输入适用于两链路可作答的旧场次。

| AC | 可观察要求 |
|---|---|
| 13 | 两类新场次均能生成七项矩阵；状态与理由由实际回答支撑，引用必须属于本场且不能伪造 |
| 14 | 未问、未答和无真实项目诚实呈现；不得将来源状态混作回答充分程度 |
| 15 | 缺口一键进入结合主项目的单题练习；重入可恢复；补练结果可回查，原矩阵与问答不变 |
| 16 | 语音听写可编辑并手动提交，拒绝/错误/不支持时可继续打字；无录音保存及模型下载 |
| 17 | 新 V3 正常闭环按序经过四阶段；提前结束如实列未完成；接近尾声收敛追问 |
| 18 | 不限时和20/30/45实战配置正确；确认起计、系统等待不扣时、刷新和后台不重置 |
| 19 | 到点不出新题、不自动提交、停语音且保留草稿；最后一答可校对提交并收尾 |
| 20 | 无版本标识的旧历史不变、不重算、不增调用；原后端独立构建与原有单题链路保持可用 |

## 2026-10-18 HTTP 扩展契约

以下扩展只对新场次的`experienceVersion:1`生效，旧请求和历史读取得到原有行为。

- 创建和开考前纠偏请求增加`interviewMode:TRAINING|TIMED`（默认TRAINING）、`timeLimitMinutes:20|30|45`（默认30）、`projectId?`与`projectBrief?`（最多6000字符）。主项目依次采用所选项目与补充简述、简历中可逐字回查的主项目；无真实项目明确为假设场景。
- `confirm`可带`projectBrief`，用于临开考补充或替换主项目；此动作不额外调用模型，项目题收敛为绑定新原文的澄清题，其他技术题沿用已准备内容并提示。需要重新深度分析时使用开考前纠偏。
- `POST /api/v3/sessions/{id}/transcription-wait`和`transcription-resume`沿用`{requestId,expectedRevision,expectedQuestionId}`。只允许当前计时场次的当前问题，等待每次最多10秒，同一等待窗口内重复通知不延期；恢复后可继续同题下一段听写。前端停止等待最多4秒，随后恢复作答状态。
- 公共会话增加`mainProject:{id,name,brief,hypothetical}`、`stage:INTRO|PROJECT|TECHNICAL|QUESTIONS`、`stages:[{id,label,status,answeredCount}]`及`timing:{enabled,limitMs,elapsedMs,remainingMs,running,expired,serverNow,waitingUntil?}`。阶段状态为`NOT_STARTED|IN_PROGRESS|COMPLETED`。无版本历史不补这些派生内容。
- 消息增加`stage`；候选人反问后同一次追问调用的回应使用`kind:REPLY,role:ASSISTANT`，不带`questionId`，不作为下一道题。反问超时可用明确的收尾提示保留问题，不新增调用。正常结束仍由用户显式生成复盘。
- 复盘增加`stages`与适用时的`matrix:{version,project:{id,name},notice,items:[]}`。矩阵每项为`{checkpointId,title,status,reason,evidence:[{questionMessageId,answerMessageId,questionQuote,answerQuote}]}`，七项齐全；状态为`CLEAR|PARTIAL|UNCOVERED`。已讲清和待补强必须有本场真实相邻问答引用；缺项、伪造引用不落盘为成功报告。未覆盖不推断能力不足。

共享补练路径不在`/api/v3`前缀下：

| 路径 | 行为 |
|---|---|
| `GET /api/project-evidence/{SESSION|V3}/{sourceId}/gaps` | 读取本场各检查点的已有补练关联 |
| `GET .../gaps/{checkpointId}` | 读取单项；尚无补练时`caseId:null` |
| `POST .../gaps/{checkpointId}` | `{requestId}`；仅待补强/未覆盖可生成一道新题，返回`{checkpointId,caseId,status,operationId?,latestAttempt?}` |

同场同检查点使用稳定操作键，换请求标识或刷新不重复生成。失败保留操作指针，沿用V2显式重试；生成成功后复用`/practice/:id`和原单题API。案例标记`contextOrigin:PROJECT_GAP`、`sourceSessionKind:SESSION|V3`，单题结果通过关联读取，原报告、矩阵及问答保持原样。补练依赖已启用的V2存储，新空库需按既有流程准备，不自动迁移。
