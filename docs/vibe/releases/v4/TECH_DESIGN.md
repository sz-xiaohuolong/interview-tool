# V4技术设计

## 追加设计：具体考点与跨场练习（2026-10-19）

继续使用原InterviewService和快照存储。`AgentTopicCatalog`读取本地`knowledge/agent-interview-topics.json`，`kind=INTERVIEW`表示考点发现层；当前整理六类各10项、共60个稳定考点，附33条官方／一手引用。能力目标、场景、追问方向和评判边界由本项目原创，公开目录只用于发现范围；不引入第三方题库全文或上传解析依赖。Spring AI资料固定v1.1.8，其余来源记录核验时间和适用边界；许可按对应版本与文件核对，MCP材料不统一写作MIT。

`AgentPracticePlanner`从实际展示的消息读取考点历史，隔离岗位方向和职级；普通Java岗位不套用Agent目录。快速练习按所选主题与题量选定不同考点，主题优先轮换；综合面试根据ANALYZE及真实资料选候选点，只给TECHNICAL阶段绑定，项目及首尾阶段保持原约束。综合复习未手选主题时沿用创建前的全目录复习范围，不在资料分析调用后缩小复习池或改变补新数量。最近10场按最后实际提问时间选择，影响未练优先；全部有效历史决定新练／复习身份及复习池。删除、Mock和无可靠标识旧消息排除，不迁移或猜测补算。跨场只保存练习记录，不建立能力画像。

PLAN收到选定考点；输出须带有效且不重复的pointId，主题须对应，不同身份也不能配上去空白、忽略大小写后相同的题干。身份与文本校验只能防止机械漏项、冒用或重复，实际问题是否考到该点、是否只是语义改写仍待人工。FOLLOWUP复用同次调用的assessmentPoint与当前原答，保留原缓存来源、诊断和追问上限，不新增联网或模型阶段。OFFICIAL技术核验层按选定考点的参考资料优先检索，仍最多6个来源；六类新主题纳入AI组排序；旧缓存采用当前受控目录的主题分类，正文摘要及抓取时间保留。没有拿到正文或相关片段时保留待核实，目录边界不替代技术证据。

快速配置可选agentTopics、practicePreference及补新确认。`POST /api/interviews/agent-practice-options`为复习专用预检，输入岗位、职级、题量和主题，服务端明确按REVIEW计算，只返回数量与开始条件；创建和准备阶段再次校验。复习不足需同时确认`acceptReviewFill=true`和`expectedNewSupplementCount`，数量变化则拒绝，避免按过期提示静默多补新题。默认未练优先仍直接开始。

计划与评判提示保留在私有agentPractice快照，问答公开响应剥离具体考点元数据；根问题与追问内部继承相同身份。复盘增加topicCoverage，仅统计实际展示的不同考点，并区分是否作答、新练／复习；questionId用于定位原问。计划未问不算覆盖，覆盖不表示掌握。

独立受限验证协议及运行入口见`evals/agent-topic-coverage/README.md`，原专业评测协议不变。全部新增源码按业务职责命名，未增加迭代版本工程、第二套入口或第三方依赖。

原始V4按已冻结Q1～Q12实施；上述考点扩展依据后续独立确认的Q1～Q13。本文记录实际实现及边界，验证结果见VERIFICATION。

## 流程策略与共用能力

沿用InterviewController、InterviewService、InterviewModelGateway、OpenAiJsonClient、InterviewSessionStore和一个前端App。通过内部业务流程策略区分快速练习与综合面试；复用回答、幂等、预算、恢复、复盘。新增sessionType，缺失时沿用综合行为；不通过删除experienceVersion模拟快速。

快速准备以岗位构造通用上下文，省去资料分析调用，复用受控来源与PLAN；Java、Agent岗位选择相应受控主题，自定义其他岗位不套用Java来源，没有可用依据时提示待核实；准确校验主问题数量后自动首问。综合继续ANALYZE、检索、PLAN、READY和确认开考。快速无四阶段、计时及真实经历矩阵，保留reviewVersion和assessmentVersion详细复盘。

## 诊断及边界

新场次strategyVersion=1，FOLLOWUP原响应增加diagnosis：category、coveredPoints、answerQuote、gap、nextGoal、reason。区分充分、不充分、错误、未知、矛盾、缺证据；当前原答短引须逐字匹配，长度和数组有界。保存问题/回答ID、模型动作和实际动作，后续输入复用。结构校验不证明判断正确，仍需固定样例人工核验。服务端保持追问上限、阶段和计时权威。备用题保留fallback标识，不伪造专业诊断。 2026-10-19按实际等待问题调整：追问HTTP请求默认30秒，整轮保护31秒；`INTERVIEW_FOLLOWUP_DEADLINE_MS`可覆盖整轮毫秒数，兼容旧`app.v3.fast-deadline-ms`，总时限封顶121秒、模型请求最长120秒。继续使用原异步确认、取消、generation和迟到保护；进行中只显示等待状态，不提前宣告降级。

问答公共响应隐藏诊断，REPORT_READY后报告提供回查；复盘不能以诊断替代原答和官方依据。诊断仍在原FOLLOWUP输出预算内，不增加模型调用。旧场次及旧报告不回填、不补算。

## 列表、存储和评测

列表增加sessionType及真实answerCount；前端以source+id处理首页、历史、继续、报告、回放和删除。缺失摘要字段保持未知，不强制计零。数据只扩展JSON快照，不迁移SQLite或个人库。

evals/interview-agent保存24案例、来源、原策略和评分规则。CLI默认离线，原始输出与共享尝试账本放忽略目录，先计尝试再请求，跨输出目录维持96次限额。原策略Prompt和校验器引用快照固定92261c9，生产只保留当前策略；评测执行公共Python机械子集，不冒充完整Java校验器或会话运行；按批人工评分完成后才允许新策略真实对照，输入/模型/参数/来源/Prompt摘要变化拒绝混组。

## 参考与许可

参考https://github.com/noamseg/interview-coach-skill ，固定提交634a8dd8689e0420c21e5f0c8ae3cfa9e1a7ab7e。借鉴references/commands/mock.md的强/弱/矛盾分支和题间不反馈，以及references/evidence-sourcing.md的依据要求。MIT许可，2026 Noam Segal。本轮自行编写项目协议，不复制整套Skill；其技术模拟只评沟通过程，本项目仍需技术依据验证。

## 现有入口兼容与实施取舍

快速配置复用现代客户端和工作台，仅新增配置页面；四阶段、计时、项目矩阵由业务类型判定。首页与历史共用 `interview-records.ts` 归一化，身份为来源与ID。旧报告的推荐练习明确指向其原有综合准备目标，避免新增快速路由接管后丢失原目标；带trainingLinkId的关联训练仍由现有LinkedConfigure提交原接口。

无新增第三方依赖：现有Vue路由和Java枚举策略已足以隔离两种流程；不引入多Agent编排框架或复制开源Skill源码。评测CLI仅用Python标准库，作为开发工具独立于应用调用接线；它不替代原后端模型客户端。固定输入包含必要历史与官方短引，不将预期、禁止行为和人工评分规则送入模型。当前对照仅检验固定上下文，不覆盖完整多轮自适应行为。

## 工作台提示展示修复（2026-10-19）

快速练习和综合面试共用 `frontend/src/pages/interview/InterviewSession.vue`。原工作台直接逐条显示 `warnings`，同一备用题提示累积后会重复占用首屏；当前错误与恢复按钮又被对话区隔开。

现仅在展示层按原文合并相同提示，保留首次出现顺序和每项记录次数。所有准备、查证和过程提示统一放入默认收起的原生 `details`，摘要同时说明提示项数与原始记录条数；展开后以普通文字列表阅读。重复次数仅写“记录几次”，不推断具体轮数或失败原因。历史警告数组和持久记录保持原样，后续重新打开同样适用。

当前 `session.error` 始终在折叠区外显示；失败或中断时，将原有恢复区移至对话上方，错误与重试、跳题、刷新按钮相邻。按钮仍受原有服务端允许动作与忙碌状态控制。备用问题继续按真实的 `message.fallback` 标记，不根据全场警告推断题目来源。已有两种复盘展示共用本次提示收纳，不改接口、请求封装、状态流转、草稿、计时或语音逻辑。
