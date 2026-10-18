# V3 技术设计

状态：APPROVED / IMPLEMENTED，2026-10-17。产品行为以 SPEC 为准；实际验证及适用范围见 VERIFICATION，不以设计描述代替证据。

## 追加式接入

新增独立 `v3/pom.xml`，Boot仍为3.5.9，加入原backend源码/资源/测试作为构建输入，但输出到v3/target；不修改原POM。新增Spring AI 1.1.8（Boot3.5兼容线）、Jsoup解析公开文档。版本查证同时使用Context7和官方tag源码/Maven metadata；Context7混入main的2.x片段不能用于1.1.x实现。

新增类位于 `cn.interview.v3` 的controller/service/mapper/model/knowledge/config包，利用原Application组件扫描。通过v3配置启用，日常dev与独立V3启动器均已接入；原backend构建不包含新模块。Controller负责输入与响应；Service负责状态、队列与流程；Mapper负责原子文件快照。新状态保存在 `DATA_DIR/v3`，不修改原SQLite表，不迁移个人库。

前端新增 `src/v3` 与 `vite.v3.config.ts`，仅V3启动配置替换HTML入口。加载已有V2入口与路由，额外注册 `/interview-v3` 与 `/interview-v3/:id`；首页/导航的原快速配置路径通过追加路由守卫导向新配置，带trainingLinkId的既有V2训练链保持原入口。原页面、CSS、router不改。新增V3常驻入口可返回已有功能。

## Agent/检索切片

1. ResumeAnalysis：只读本场资料快照，结构化提取技术栈/职责/疑点/受控主题。
2. KnowledgeRetriever：主题键选择allowlist URL，用有限超时/大小/重定向限制获取；Jsoup去脚本，缓存文本，按词相关度选择片段；公开网络只见固定URL，不见个人资料。来源元数据与片段写入本场快照。
3. InterviewPlanner：将岗位、简历证据和知识片段合成固定题单、备用题、预期考查方向；服务端验证引用来自当前输入。
4. InterviewAgent：使用当前问题、回答、局部证据和历史，限时一次模型调用；后台定时器在7秒左右选有效备用题或标为中断，拒绝迟到结果。
5. ReviewAgent：刷新当前相关公开资料以终审核实，生成逐题发现；服务端检查questionId/sourceIds，缺证据技术判断降为INSUFFICIENT。

V3ModelGateway隔离Spring AI；显式mock仅用于测试并标识。真实模式无Key直接失败，不静默切Mock。单次RetryTemplate仅一次；深度阶段读取原配置，追问可用V3_FAST_MODEL/V3_FAST_REASONING_EFFORT，默认同模型low。凭证/服务地址不进入日志或公共协议。

## 执行与数据

慢任务使用有界队列，回答使用独立执行器，不被资料下载阻塞。每个状态变更在单会话同步及原子落盘中完成。revision保护客户端提交，generation保护后台写回；requestId绑定动作与输入，重复请求读取现状、不同输入拒绝。服务启动将未完成阶段转INTERRUPTED，等待显式重试。

队列/分析/检索/规划/复盘阶段写入phase。更正和刷新增加generation，取消使旧任务失效。任务预算与本场快照持久化；网络不持有存储锁。每个模型调用先计数再发送。

## 原始资料与证据

使用MaterialService读取已有RESUME/JD并生成有界文本，保存资料名/id/内容hash/截断状态。旧数据不回写。公共API裁剪个人原文、未问题单和内部模型输入。仅本机查看会话与来源；个人数据/缓存/运行日志不提交。

## 技术来源（核对日期2026-10-17）

- Context7 `/spring-projects/spring-ai/v1.1.8`：程序化模型构造与JSON响应；另逐条以同tag源码核对。
- https://github.com/spring-projects/spring-ai/tree/v1.1.8 ：1.1.8实现与许可证。
- https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-openai/maven-metadata.xml ：适用版本发布元数据。
- Context7 `/websites/router_vuejs`；https://router.vuejs.org/guide/advanced/dynamic-routing.html ：动态追加路由与守卫。
- https://v6.vite.dev/config/ ：沿用Vite6配置机制，不升级现有工具链。

技术接入不等于质量提升，是否完成由VERIFICATION逐项记录。


## 实施后的关键约束

- 正常追问完成、备用切换和取消均隔离generation，避免旧定时器影响下一轮；公开allowedActions由真实状态与待恢复阶段推导，不靠界面中文猜测。
- 公开来源现17项，新增JavaGuide Redis题库/缓存策略、Microsoft官方Cache-Aside、Redis官方分布式锁。它们用于补足实际合成验收发现的缓存旁路与一致性资料缺口；每次仍最多获取六源，公开查询不携带个人正文。
- 同版本保存各阶段实际所见excerpts；刷新后按正文hash区分版本、按48小时重算过期。模型只接收本轮相关节选，公共快照可回查曾用片段。
- 纠偏保留累计更正，更新快照摘要；显式移除已保存JD会清除旧正文。简历最低覆盖单独检查resumeBased，不能用JD引用冒充简历经历。
- 模型输入超过预算时明确节选并保存覆盖提示，原本场资料/回答不被该步骤改写。模型未提供用量时保留usageIncomplete，不把未知计为实际零成本。
- 新增 `scripts/e2e-v3.sh` 与独立Playwright配置，在显式迁移的合成库上用V3后端跑原15项浏览器回归；不改旧测试或旧配置。原测试入口未开启V2导致的失败与新夹具前缀问题均在验证记录保留。
- 原日常服务与V3若读取同一资料库，不应同时恢复同一批会话；独立启动器在默认data且8080已有服务时明确拒绝，需先停旧服务或指定独立资料目录。最初限定验收使用独立合成库；用户随后要求合入日常dev，已将 `scripts/dev.sh` 接到V3构建与前端配置，日常5173/8080使用原data，切换前已停止旧服务并完整备份。原业务源码、POM和前端路由保持。

这是受控阶段工作流：模型决定分析、题目和追问内容，服务端负责资料边界、可用工具/来源、状态及预算。没有开放任意网络搜索、自动执行用户指令或无限自主循环。这个边界是当前实现事实，后续是否扩展按训练收益另行决定。


## 专项训练交互修复（2026-10-18）

本次追加授权仅涉及V3复用的错题复习和原题重练前端。上轮误把新建五分钟面试作为原题重练；该设计已撤回，不能继续作为当前事实。

当前开始入口使用现有practice-cases接口按源会话及原问题ID创建或复用单题案例。继续入口只导航已有案例或旧关联会话，不创建资源。Practice自动恢复/准备草稿，READY后以原答和本次作答配对重评，原报告及未知历史协议保留。已完成只是保存记录的界面分类，不自动绑定掌握证据。

本轮不使用上轮本地新增的latestTraining后端摘要，全部恢复使用原有只读接口；没有修改PracticeController、PracticeService、Schema或模型协议。范围、调用细节、异常恢复和证据统一见 [本次修复记录](REVIEW_PRACTICE_FIX.md)。V3综合面试的既有边界与整体验收状态保持。


## 追加设计：矩阵、语音与完整实战（2026-10-18，已实施）

沿用现有 JSON 会话与 V2 单题案例，不迁移历史。新会话写入 `experienceVersion: 1`；旧场次缺字段即走旧逻辑。共享矩阵规则与单题来源适配放在原 backend 的职责包中，使 backend 独立构建不反向依赖 v3。模型在已有复盘任务中输出矩阵；服务端验证检查点、问答引用和原文片段，未覆盖不推导为能力缺陷。V3 通过共享服务创建缺口单题，保留来源快照与补练关联。

新 V3 配置增加 `interviewMode: TRAINING|TIMED`、`timeLimitMinutes`（默认30）及 `projectId/projectBrief`。选中的项目资料或用户简述形成主项目快照；摘要确认可纠偏。四阶段作为服务端持久化状态，准备时为阶段生成适合岗位和资料的题目；进度和时限驱动追问收敛。训练原题数强度保留，四阶段各提供一次作答机会优先于原最少题数。

计时由`V3InterviewProgress`保存已用时与当前用户作答计时起点，公开剩余时长和服务端时间供前端显示；模型处理与故障状态停止累计，读取不改变版本。停止听写后可通知本题等待结果，每个窗口最多10秒且重复通知不延期，超时恢复计时；前端自身最多等待4秒，同题可分段听写。刷新按服务端状态恢复；到点保留当前答题机会且不产生下一题。正常关闭保存累计值，重启从最近累计值恢复，宕机不扣时；硬崩时最近未保存的作答时间可能少计。草稿沿用本机存储，不自动提交。

前端共用轻量语音输入组件，使用浏览器 SpeechRecognition/webkitSpeechRecognition，封装开始、停止、结果、权限错误和销毁；无新增模型依赖。只在用户点击时启动，提示浏览器可能在线识别。API 事件替身用于状态与草稿测试，真实 Chrome 识别质量需单独人工验证。

实施分工：矩阵代理负责原 backend 矩阵/补练与共享展示组件；阶段代理负责 V3 后端的主项目、阶段、时钟及其模型契约；语音代理负责共用输入组件及旧 Session 接入；主代理负责 V3 前端、契约集成、权威文档和总体验证。各代理先测试失败再实现，不同时写同一文件。

实际接入使用`ProjectEvidenceService`校验七项和真实相邻问答，`ProjectGapService`复用V2固定任务、幂等与失败恢复；公开单题列表/详情裁剪内部生成协议，未生成完成不能建立作答尝试。无新数据库表或历史批量回写。补练只读源矩阵，结果通过案例关联显示；首次缺口作答没有原答，不伪造比较。

新V3题单按四阶段排序，接近时限收敛追问并跳过同阶段剩余主问题；到点保存末答后不调用FOLLOWUP。反问回应包含在同次FOLLOWUP结果中，使用无questionId的REPLY消息，超时明确收尾；仍保留显式生成复盘入口。复盘在原一次REVIEW中追加矩阵与阶段，不额外为矩阵调用模型。引用校验能证明原文存在，不能独立证明所有自然语言的项目归属；相关质量范围以真实合成验证为准。
