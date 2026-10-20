# 源码面试验证记录

2026-10-20。本轮实现和限定真实样本验收已完成，已在本机日常入口启用，按持续授权同步origin/main；没有创建正式版本发布。正式 Release 仍为 V1；V4 原停批评测与人工验收保持 PARTIAL，不因本轮改变结论。

## 1. 结论和适用范围

项目资料可以关联一个 GitHub 公开仓库；项目深挖和综合面试在准备时锁定默认分支当前 Commit，复用本场核验片段出题，答题期间隐藏源码和内部目标，复盘展示实际问题的固定行号依据。全部不可用须显式重试或仅用资料继续，部分可用保留有效证据。

真实模型采用本机配置的 `gpt-6.1-sol`，合成资料明确是 Petclinic 学习项目，不声明个人开发贡献。项目深挖生成3题，覆盖L1/L2/L3，全部带源码引用；综合规划8题，保留四阶段，其中5道项目/技术主问题带源码引用，开场介绍与候选人反问及其备用题均无源码引用。通过生产结构、引用、阶段与题干边界校验后，主代理逐题核对实际片段，8道源码主问题均有对应依据。

这是一次真实探索及两种真实规划验证，两种规划复用同一源码快照。软件集成测试覆盖完整接口接线及报告展示；本轮没有进行真实模型的完整连续问答、即时追问或复盘质量验收，也没有由用户逐题评分。结论不外推其他仓库、语言或供应商。

## 2. GitMCP POC 与最终选择

使用 Spring AI 1.1.8 对应 MCP SDK，隔离连接 `https://gitmcp.io/{owner}/{repo}`，成功动态发现 Petclinic 与本项目工具。Petclinic 查询 `OwnerController`、`findById`、`processCreationForm` 均取得路径，约1852、2739、1921毫秒；本项目 `InterviewService` 一次查询无命中。结果没有实际源码正文及行号，还提供通用抓取工具，因此不直接作为代码证据，不开放通用抓取。

最终采用 GitMCP 路径线索 + GitHub REST 固定SHA/blob核验 + Spring AI原生受控工具。真实探索中一次组合关键词搜索未命中，明确记录警告，固定目录回退仍取得源码；没有把搜索命中或模型推断视为事实。

原始POC：`.runtime/source-poc/http.log`、`dynamic.log` 和 `GitMcpProbe.java`。这些本机日志不提交。

## 3. 工程、接口与浏览器

源码实现阶段的 `./scripts/test.sh` 退出码0：Java 21下379项后端中371通过、8项显式联网跳过；前端30个文件的289项测试、TypeScript检查及Vite生产构建通过。最终日志为 `.runtime/source-poc/standard-delivery-final.log`。初次375项结果已被本次最终结果覆盖，不累计为额外通过数量。

最后的题干边界修复后，使用最终生产校验器回放已保存的真实两份题单：项目3题、综合8题及8道源码主问题全部通过，没有新增模型调用。日志 `.runtime/source-poc/replay-final.log`。

`bash scripts/e2e.sh`：46项全部通过。沿用原隔离运行器，新增1440px和390px两条源码失败显式继续、复盘固定链接、源码隐藏及无横向溢出检查。新增浏览器用合成HTTP快照核对展示，真实Spring接口接线由集成测试覆盖，不能把它说成真实模型浏览器全流程。

| 风险或路径 | 实际证据 |
|---|---|
| 非GitHub地址、路径穿越、明显敏感文件和目录 | `GitHubCodeClientTest`；覆盖 `secrets/config.ts`、`credentials/data.sql` 等 |
| 私有/不存在、限流、超时、错误blob与损坏缓存 | 客户端边界替身；损坏内容拒绝且删除缓存；不自动换版本 |
| 不同仓库和Commit隔离 | 客户端缓存请求路径及不同身份读取；综合刷新锁定旧SHA |
| 原生工具协议、真实读取登记、文件头不提前结束 | `SourceToolModelClientTest` 本机HTTP替身执行Spring工具，检查下一轮tool response及方法导航 |
| MCP失败与固定目录回退、模型后续失败保留部分证据 | `CodeExplorationServiceTest`；外部MCP边界替身，保存PARTIAL及警告 |
| 不支持工具时全部不可用、显式继续 | 服务测试与两条Spring API集成测试；无有效片段不伪造成功 |
| 两条API接线、刷新、解除关联、持久化及公开裁剪 | `SourceInterviewIntegrationTest`，使用独立临时库；SQLite重新建立实例后旧场快照保留 |
| 主问、备用题、即时追问的代码与目标边界 | 服务端共用校验；主问/备用泄漏回归，综合INTRO/QUESTIONS备用引用拒绝 |
| 未关联仓库时保持原流程 | 原标准回归及无源码时不新增题干限制的兼容测试 |
| 前端实际请求路径及恢复动作 | 标准测试包含 `interviews.contract`、API边界、History路由和两种会话行为测试 |

浏览器日志 `.runtime/source-poc/browser-checks.log`；截图 `frontend/test-results/source-review-1440.png`、`source-review-390.png`。后续后端修复未改变这些合成展示路径，复用该证据。

## 4. 真实源码与问题实例

仓库：`spring-projects/spring-petclinic`。本场固定 Commit：`500158f732419217507c7656904b8e6aa1bcc0d6`。GitHub REST成功取得目录和实际blob，摘要、大小及UTF-8校验通过；不是从GitMCP结果抄录代码。

以下均为真实模型题干节选，完整合成题目、证据身份、片段摘要、逐次用量和当前源文件摘要见 [SAMPLES.json](SAMPLES.json)。未公开原始模型日志或大段第三方源码。

| 实际问题 | 固定源码依据 | 主代理核对 |
|---|---|---|
| 项目L1：以新增就诊记录为例，梳理 `loadPetWithVisit` 和 `processNewVisitForm` 从模型准备到保存、跳转的链路；无法仅凭片段确认的持久化行为须指出 | [VisitController.java 第63～111行](https://github.com/spring-projects/spring-petclinic/blob/500158f732419217507c7656904b8e6aa1bcc0d6/src/main/java/org/springframework/samples/petclinic/owner/VisitController.java#L63-L111) | 片段确有owner/pet查找、visit关联、校验分支与owner保存；未读完整映射，不据此认定级联或事务配置 |
| 项目L2：表单额外提交id或嵌套对象id时，如何判断实体身份能否被改写，并构造测试验证 | [PetController.java 第89～108行](https://github.com/spring-projects/spring-petclinic/blob/500158f732419217507c7656904b8e6aa1bcc0d6/src/main/java/org/springframework/samples/petclinic/owner/PetController.java#L89-L108) | 两个binder及新增入口均在本场片段；问题询问绑定边界，没有给出拦截语句作为答案 |
| 项目L3：假设同一主人并发新增同名宠物，如何结合校验、持久化与异常处理保障一致性，并说明尚需核实的约束与事务配置 | [PetController.java 第107～134行](https://github.com/spring-projects/spring-petclinic/blob/500158f732419217507c7656904b8e6aa1bcc0d6/src/main/java/org/springframework/samples/petclinic/owner/PetController.java#L107-L134) | 确有预检、saveAndFlush及数据完整性异常分支；未读异常识别helper与数据库约束，题目明确要求核实而非预设实现 |
| 综合TECHNICAL：假设主人姓氏前缀分页查询变慢，数据库类型和表结构未确认时怎样定位耗时 | [OwnerRepository.java 第36～60行](https://github.com/spring-projects/spring-petclinic/blob/500158f732419217507c7656904b8e6aa1bcc0d6/src/main/java/org/springframework/samples/petclinic/owner/OwnerRepository.java#L36-L60) | 接口真实声明前缀分页查询，未推定具体SQL、索引或性能结论；深页问题明确为假设 |

另外4道源码主问题分别覆盖宠物新增校验反馈、就诊关联边界、并发名称约束及保存原子性核实，与上述片段相符。所有题目的源码ID均属当前快照，没有混入技术知识sourceIds。

## 5. 真实调用、失败与恢复

总计7次模型尝试：6次正常返回、1次HTTP 408。已知输入15151 Token、输出5096 Token，合计20247 Token；408没有返回用量，保持未知，不能把20247作为全部消耗。请求耗时合计182934毫秒，不含GitHub/MCP工具等待、测试启动及人工核对。

| 顺序 | 任务 | 结果 | 已知输入/输出Token | 请求耗时 |
|---|---|---|---|---|
| 1 | 源码探索：目录 | 返回 | 860 / 14 | 6391毫秒 |
| 2 | 源码探索：搜索 | 返回；搜索无命中后目录回退 | 1887 / 26 | 5774毫秒 |
| 3 | 源码探索：3次源码读取 | 返回并取得3份证据 | 2103 / 510 | 19293毫秒 |
| 4 | 源码探索：后续请求 | 上游HTTP 408，停批，保存PARTIAL | 未知 | 25284毫秒 |
| 5 | 项目深挖PLAN | 3题，结构/引用/边界通过 | 3879 / 1054 | 32368毫秒 |
| 6 | 综合ANALYZE | 通过，未发送源码 | 1293 / 480 | 16819毫秒 |
| 7 | 综合PLAN | 8题，四阶段/引用/边界通过 | 5129 / 3012 | 77005毫秒 |

首批账本 `.runtime/source-poc/live-20261020/ledger.json` 保留失败；用户随后明确不以调用预算作为关卡，后续在 `.runtime/source-poc/live-plans-20261020/` 显式复用已完成的固定快照，没有重跑探索或覆盖旧账本。后续真实JUnit退出码0，`validationPassed=true`。

其他开发失败：恢复时修复原对话遗留的两处编译错误；曾误并行两个Maven导致class截断，已清理后串行验证；前端SessionAction契约遗漏已补；缓存核验、题干泄漏、敏感目录、备用题阶段约束及未关联兼容回归均先记录失败再修复。过程日志保留在本机 `red-*`、`green-*`，不把历史失败从报告中删去。

## 6. 审查与交付边界

一次只读代码审查发现3项P2：敏感目录漏过滤、题干可能泄露片段/路径/内部目标、自我介绍备用题可能带源码引用。均已修复并补回归。复核追加的Python无括号语句泄漏也先复现后修复，审查代理最后确认该具体缺口闭合；两轮复核均为只读，没有代跑测试。没有增加版本源码目录、重复入口或服务实现。

仍未验证或未覆盖的范围：其他公开仓库/语言的大范围质量、真实GitHub匿名限流与私有仓库账号响应、强制真实GitMCP超时、不同模型的Tool Calling兼容性、真实连续问答和复盘、所有自然语言形式的答案暗示。错误替身验证的是本地恢复行为，不等同第三方所有故障已经实测。

源码探索用量单独保存在快照，当前主流程界面累计Token不包含它。每次准备有工具与输出上限，显式重试仍会新增调用。关联表与两条流程已在隔离库验证，用户追加持续自动交付授权后，本机日常服务已启用，数据核对通过，见下一节。真实连续问答与复盘仍属未验收边界；用户可通过现有日常入口继续观察。

最终交付检查：`git diff --check`通过；源码/资源没有新增版本目录或版本类名，没有新增App、启动入口或Vite配置。公开合成样本与源文件摘要已核对；文档本地引用检查通过。原始日志、源码缓存、个人资料和凭证不进入交付清单。

## 7. 持续自动交付与本机重启

2026-10-20用户明确要求每次开发完成后自动同步远程并重启后端。约定写入 `AGENTS.md`，本次包含提交相关改动并同步既有 `origin/main`；不包含强制推送、PR、Tag、正式Release或公网部署。实际提交和远程结果以Git引用为准。

首次使用本轮JAR启动日常库时，启动前检查因旧库没有新增 `project_repositories` 表而拒绝，日志 `.runtime/source-sync-20261020/backend.log`；检查发生在业务写入前。新增回归先失败：旧库两种训练开关状态及维护迁移共3个场景复现。修复后只允许缺少该新增表，已有的关联表约束不符、原业务表缺失仍拒绝；原训练迁移校验和保持。针对性14项回归全部通过，日志 `legacy-startup-red.log`、`legacy-startup-green.log`。

修复后 `mvn -f backend/pom.xml package` 退出码0：384项后端中376通过、8项显式联网跳过，JAR打包成功，日志 `.runtime/source-sync-20261020/package-verified.log`。前端及模型协议未改变，复用前述289项前端、类型/构建、46项浏览器及真实题单回放，不新增模型调用。当前45份相关代码/测试/配置摘要记录在 `SAMPLES.json`；该旧库启动修复不改变已核对的8道真实源码主问题。

后端通过Java 21独立进程运行在 `127.0.0.1:8080`；前端原进程继续运行于5173。`/api/health`、前端首页及前端代理健康均返回200，模型配置可读。重启前SQLite完整备份与接口/文件摘要、重启后核对结果保存在 `.runtime/source-sync-20261020/`，不公开真实资料。

| 核对项 | 实际结果 |
|---|---|
| 原SQLite 19张表 | 行数和内容摘要完全一致 |
| 本地文件与配置 | 15份摘要一致，包含模型配置与 `.env.local` |
| 4份资料、1份项目/单题场次、2份综合场次 | 原字段一致；没有生成中的场次，不重算历史 |
| 新增兼容响应字段 | 资料 `repository:null`；旧消息 `hasCodeEvidence:false`，均逐项确认 |
| 新表 | 仅补建 `project_repositories`，0行 |
| 服务 | 后端健康、前端首页及代理健康均200 |

原始证据、备份、源码缓存、个人资料、密钥和无关IDE/设计产物不纳入提交。自动同步与重启授权不改变本页真实验收范围，也不恢复V4其他停批评测。
