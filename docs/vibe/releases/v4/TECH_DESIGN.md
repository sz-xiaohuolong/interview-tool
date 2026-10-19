# V4技术设计

2026-10-19，按已冻结Q1～Q12实施；本文记录本轮实际实现及边界，验证结果见VERIFICATION。

## 流程策略与共用能力

沿用InterviewController、InterviewService、InterviewModelGateway、OpenAiJsonClient、InterviewSessionStore和一个前端App。通过内部业务流程策略区分快速练习与综合面试；复用回答、幂等、预算、恢复、复盘。新增sessionType，缺失时沿用综合行为；不通过删除experienceVersion模拟快速。

快速准备以岗位构造通用上下文，省去资料分析调用，复用受控来源与PLAN；Java、Agent岗位选择相应受控主题，自定义其他岗位不套用Java来源，没有可用依据时提示待核实；准确校验主问题数量后自动首问。综合继续ANALYZE、检索、PLAN、READY和确认开考。快速无四阶段、计时及真实经历矩阵，保留reviewVersion和assessmentVersion详细复盘。

## 诊断及边界

新场次strategyVersion=1，FOLLOWUP原响应增加diagnosis：category、coveredPoints、answerQuote、gap、nextGoal、reason。区分充分、不充分、错误、未知、矛盾、缺证据；当前原答短引须逐字匹配，长度和数组有界。保存问题/回答ID、模型动作和实际动作，后续输入复用。结构校验不证明判断正确，仍需固定样例人工核验。服务端保持追问上限、阶段和计时权威。备用题保留fallback标识，不伪造专业诊断。

问答公共响应隐藏诊断，REPORT_READY后报告提供回查；复盘不能以诊断替代原答和官方依据。诊断仍在原FOLLOWUP输出预算内，不增加模型调用。旧场次及旧报告不回填、不补算。

## 列表、存储和评测

列表增加sessionType及真实answerCount；前端以source+id处理首页、历史、继续、报告、回放和删除。缺失摘要字段保持未知，不强制计零。数据只扩展JSON快照，不迁移SQLite或个人库。

evals/interview-agent保存24案例、来源、原策略和评分规则。CLI默认离线，原始输出与共享尝试账本放忽略目录，先计尝试再请求，跨输出目录维持96次限额。原策略Prompt和校验器引用快照固定92261c9，生产只保留当前策略；评测执行公共Python机械子集，不冒充完整Java校验器或会话运行；按批人工评分完成后才允许新策略真实对照，输入/模型/参数/来源/Prompt摘要变化拒绝混组。

## 参考与许可

参考https://github.com/noamseg/interview-coach-skill ，固定提交634a8dd8689e0420c21e5f0c8ae3cfa9e1a7ab7e。借鉴references/commands/mock.md的强/弱/矛盾分支和题间不反馈，以及references/evidence-sourcing.md的依据要求。MIT许可，2026 Noam Segal。本轮自行编写项目协议，不复制整套Skill；其技术模拟只评沟通过程，本项目仍需技术依据验证。

## 现有入口兼容与实施取舍

快速配置复用现代客户端和工作台，仅新增配置页面；四阶段、计时、项目矩阵由业务类型判定。首页与历史共用 `interview-records.ts` 归一化，身份为来源与ID。旧报告的推荐练习明确指向其原有综合准备目标，避免新增快速路由接管后丢失原目标；带trainingLinkId的关联训练仍由现有LinkedConfigure提交原接口。

无新增第三方依赖：现有Vue路由和Java枚举策略已足以隔离两种流程；不引入多Agent编排框架或复制开源Skill源码。评测CLI仅用Python标准库，作为开发工具独立于应用调用接线；它不替代原后端模型客户端。固定输入包含必要历史与官方短引，不将预期、禁止行为和人工评分规则送入模型。当前对照仅检验固定上下文，不覆盖完整多轮自适应行为。
