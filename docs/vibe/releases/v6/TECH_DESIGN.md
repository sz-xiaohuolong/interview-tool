# 投递管理 MVP 技术方案

2026-10-22：已实现以下设计并通过相关验证；证据范围和交付核验见 [VERIFICATION](VERIFICATION.md)。沿用 Java 21、Spring Boot 3.5.9、Vue 3、SQLite 与现有本机安全边界。

## 1. 业务边界与文件职责

新增 `controller/job/JobApplicationController`、`service/job/JobApplicationService`、`service/job/JobApplicationCsvService`、`mapper/JobApplicationStore`、`entity/JobApplication`。存储类复用同包 `SqliteStore` 的 JdbcTemplate 和事务；不进入 MaterialService，不调用模型或招聘站点。前端 `api/job-applications.ts` 与 `pages/jobs/JobApplications.vue` 接入单一 App、导航及 `/job-applications` 路由。

## 2. 数据与非破坏性初始化

`schema.sql` 使用 `CREATE TABLE/INDEX IF NOT EXISTS` 新增 `job_applications`，不修改或重建原表。稳定 UUID 为主键，独立列保存全部字段；无公司唯一约束，归档独立布尔值。索引覆盖归档、状态、行动日期。只使用参数绑定，排序字段与方向白名单。启动前的 `TrainingSchemaPreflight` 和维护脚本 `prepare-training.py` 允许旧库缺少本轮新增的表及索引，随后由 SqliteStore 补建；已有表结构不匹配、未知对象、旧表缺失或迁移校验和异常仍拒绝，不放宽原数据保护。

| 字段 | 类型与约束 |
|---|---|
| id | UUID 文本；创建生成，CSV 恢复可保留 |
| company、position | 必填文本，最多 200 字符；保留原文，不静默 trim |
| location、platform | 文本，最多 200／100 字符，默认空字符串 |
| url | 最多 2048 字符；仅有主机的 HTTP(S) 链接，禁止凭据及控制字符；不抓取 |
| jd、notes | 最多 24000／12000 字符；保留空格、中文、换行 |
| jobType | CAMPUS、INTERNSHIP、SUMMER_INTERNSHIP、FULL_TIME、OTHER，默认 OTHER |
| status | 需求七枚举，默认 TO_APPLY，无单向限制 |
| appliedDate、nextActionDate | 可空 YYYY-MM-DD，不作时区转换 |
| interviewAt、interviewNote | 可空带偏移 ISO 日期时间；说明最多 1000 字符 |
| archived | 布尔值，默认 false，与 status 分离 |
| createdAt、updatedAt | UTC ISO 时刻；CSV 恢复保持原值；正常编辑只推进 updatedAt |

## 3. API 契约与错误

调用地址统一为 `/api/job-applications`，复用本机来源校验；无新增认证或跨域放行。成功返回 JSON 记录或列表，删除 204。400 表示输入无效或 CSV 行冲突，404 记录不存在，409 表示 CSV 预览失效；错误结构复用 `{code,message}`。完整调用说明已同步至当前 [技术设计第 12 节](../../TECH_DESIGN.md#12-求职管理与-csv-接口)。

- GET 列表：`company` 子串、`status`、`archived=false|true|all`；`sort=updatedAt|appliedDate|nextActionDate|interviewAt|createdAt`，`direction=asc|desc`。空日期始终末尾，同日用 ID 稳定排序。
- POST 新增；GET／PUT／DELETE `/{id}`。PUT 全量业务字段，不改变 ID 和创建时间。
- PATCH `/{id}/status` 接收 `{status}`，PATCH `/{id}/archive` 接收 `{archived}`，只变更目标字段及修改时间。
- GET `/csv/template`、GET `/csv/export` 返回 UTF-8 CSV（含 BOM），导出全部记录及归档，不受当前表格筛选影响。
- POST `/csv/preview` multipart `file`：预览无写入。POST `/csv/import` 同一文件与 `previewToken`：重新校验、对照预览后在单事务内写入；任何错误均整批拒绝。重复行明确跳过。

## 4. CSV 格式与身份

使用 Apache Commons CSV 1.13.0（Apache-2.0）的 RFC4180 解析／输出，避免手写引号与换行解析器；新增依赖限于这一职责；其 Commons IO 2.18.0 与现有 POI 传递依赖一致，无需升级共享 IO 库。官方依赖依据为 Maven Central 的 commons-csv 1.13.0 POM。固定中文表头：格式、记录ID、公司名称、岗位名称、工作地点、招聘平台、原始岗位链接、JD正文、岗位类型、投递状态、投递日期、下次行动日期、面试时间、面试说明、备注、已归档、创建时间、修改时间。格式值 `投递记录-1`；枚举用技术值，归档 true／false。模板只有表头，用户每行填写格式值；ID 和创建／修改时间可留空，由系统生成。正文空字符串，日期空单元格表示未填写。

有 ID 的行：已存在且全字段一致标重复；相同 ID 不同内容标冲突；新 ID 按原 ID 恢复。无 ID 的行：以完整业务内容比较，已存在或本批重复明确跳过；不同公司或岗位不会合并。同公司同岗位的不同业务内容可独立保存。无 ID 的新行以完整业务内容生成确定性的 UUID，原记录后续编辑后再次导入旧文件会提示冲突。预览返回逐行结果和 HMAC 签名令牌，绑定文件与相关记录状态，15 分钟有效，服务重启后失效；确认时重新校验并在同一事务中检查，相关数据变化要求重新预览。提交响应丢失时重新预览，可识别已写入的记录，避免重复新增。

导出对以 `= + - @`（含前置空白／控制字符和全角变体）开头的文本及原本以 `文本:` 开头的文本加一层 `文本:`，防止表格公式求值；导入本格式只去除一层该前缀，精确恢复原值。用户不要删除保护前缀。字段用 CSV 引号规则处理逗号、双引号、CR/LF。

文件最多 5 MiB、5000 条记录；字段按上表限制，UTF-8 非法字节、列缺失／多余、未知枚举、错误日期均明确拒绝，不截断。预览列出记录序号（表头后首条为第 2 条，可含多行正文），逐行标错误；空文件和只有表头给明确提示。错误不回显 JD／备注正文，日志不输出文件内容。

## 5. UI 与失败恢复

按根 DESIGN 的求职管理章节实施；表格内状态更新成功才应用新值，失败显示错误并保留原状态。编辑与新增用公共 Modal 抽屉，保存中禁用重复提交／关闭，保存失败保留内容。列表请求使用代次避免过期响应覆盖；搜索／筛选独立于详情与表单。删除通过独立确认框；CSV 预览在确认之前不影响数据库。

## 6. 验证、交付与恢复

先运行失败行为测试，再完成 M1～M3；M4 执行标准检查、真实 SQLite HTTP／浏览器及重启测试。测试库与日常／演示库隔离。开发前记录日常数据库和持久文件摘要，交付重启后对比原表和文件；新表仅新增不影响原数据。新增表保留，不自动删表、不重置用户库。旧版本的严格结构校验可能拒绝新表，因此不能承诺直接回退旧 JAR；优先修复当前代码，必要时由用户明确选择完整备份恢复，并先导出新增投递记录，避免丢失升级后的数据。持续授权只覆盖提交、origin/main 与日常后端重启。

## 7. 岗位搜索技术契约（2026-10-23，待实现）

以下是 G1 的实施契约，承接已冻结的 V6-REQ-01～07、根 DESIGN 和三站 CDP 样例；**不是已实现接口**。复用一个 Spring Boot 后端、Vue App、SQLite 和现有来源校验。访问路径为后端 → 本机 Chrome 的 CDP WebSocket → 已登录招聘页面；不使用 `mcp-jobs`、独立 Cookie 爬虫、付费 API 或模型。

### 7.1 浏览器与来源边界

使用 Java 21 自带的 HTTP／WebSocket 客户端和现有 Jackson 处理 CDP，不新增浏览器运行时依赖。优先发现本机 Chrome 的 `DevToolsActivePort`；开发者可用 `INTERVIEW_JOB_BROWSER_ACTIVE_PORT_FILE` 指定文件。只接受回环地址、合法本机端口和 `/devtools/browser/` 路径；不把端点、协议事件、请求头或会话信息返回前端。文件缺失、Chrome 关闭或会话未获允许时明确标为访问配置不可用，不自行启动或修改日常 Chrome。

连接只对本次创建的标签进行导航、DOM 读取和正常页面请求；取消或回收时只关闭自己拥有的标签，不关闭浏览器或用户原标签。标准 CDP 的主页面求值作为判断依据，不依据旧 Playwright 客户端的 `about:blank` 缓存判定源站失效。正常浏览器响应中的列表字段优先于字体编码后的 DOM；只提取业务字段，网络事件在处理后释放，不落盘 Cookie、请求头或令牌。

每站用独立适配器承担城市映射、源站分页、字段提取和故障分类。允许通过正常页面控件或同一已登录页面的同源请求取得下一页，不把认证数据复制到独立 HTTP 客户端。城市目录由后端提供，只有三站映射已核实的城市才可选；关键词按原文传递。适配器必须限制自动预取，将平台多返回的卡片与产品每批最多20条、每站累计100条的边界分开处理；不为了凑数继续抓取。具体城市目录和源站字段选择在实现及真实接入检查中核验后登记。

### 7.2 共用数据结构

平台值为 `BOSS`、`LIEPIN`、`ZHAOPIN`。岗位身份始终为 `key = source + ":" + sourceId`，其中 `sourceId` 来自真实职位身份或其具体职位 URL，不能用标题或公司生成。外部 URL 只允许对应平台的 HTTPS 具体职位地址，去掉访问令牌和跟踪参数后返回。

`JobRecord` 字段如下：

| 字段 | 类型与含义 |
|---|---|
| key、source、sourceId | 非空字符串；平台及来源身份 |
| title、company、city、location、url | 字符串；缺失公司或地点可为空，不能以平台首页冒充 url |
| salaryText | 原始薪资文本；缺失为空，不使用字体私用区字符冒充数字 |
| salaryMinK、salaryMaxK、salaryMonths | 可空数值；月薪单位 k，薪数单列。两端均空表示月薪未知；仅一端有值表示明确的开放区间 |
| experienceText、educationText | 可空原文；去首尾空白，未知为空；明确“不限”是已知值 |
| description | 可空正文；空表示未取得，普通文本呈现，不执行源站 HTML |
| fetchedAt、detailFetchedAt、publishedAt | 获取时刻、可空详情获取时刻、可空源站发布时间；不把获取时刻当发布时间 |

`SourceProgress` 为 `{source,status,count,hasMore,errorCode,errorMessage}`。状态为 `SEARCHING`、`READY`、`EMPTY`、`FAILED`、`ENDED`、`LIMIT`、`STOPPED`；错误字段可空，错误文字不含底层协议或认证值。

`TaskSnapshot` 为 `{id,keyword,cityId,cityName,status,revision,sources,jobs}`。`sources` 是已选择平台的进度数组，`jobs` 是岗位数组；任务状态为 `RUNNING`、`IDLE`、`STOPPED`。每次有效结果更新递增 `revision`，同站保留原顺序，列表按平台交替组织。未知来源不计为空结果。

### 7.3 搜索接口与任务生命周期

统一前缀 `/api/job-search`，复用现有本机写入来源校验和 `{code,message}` 错误格式。

| 方法与路径 | 输入 | 成功输出与行为 |
|---|---|---|
| GET `/cities` | 无 | `[{id,name}]`；返回已支持且有真实映射的城市 |
| POST `/tasks` | `{keyword,cityId,sources}` | 立即返回 `TaskSnapshot`，后台分来源执行；关键词空白、未选城市／平台、未知平台返回400 |
| GET `/tasks/{id}` | 无 | 返回完整快照并刷新活动时刻；前端仅在有查询中来源时轮询 |
| POST `/tasks/{id}/more` | `{}` | 对可继续且未到上限的来源加载下一批，返回快照；查询中不重复调度 |
| POST `/tasks/{id}/retry` | `{source}` | 仅重试选中的失败来源，成功旧批次保留，重试不重复追加已取得身份 |
| POST `/tasks/{id}/stop` | `{}` | 立即停止追加并保留结果；中断、释放本任务浏览器资源 |
| POST `/tasks/{id}/jobs/{key}/detail` | `{}` | 返回该任务已有岗位的 `JobRecord`；按来源读取正文，不接受任意外部 URL |

任务仅存内存，不持久化搜索历史。每来源运行有代次，任务停止、新任务替换和来源重试均须阻止旧代次回写；停止后不再加载更多。前端离开模块时发出停止并使自己的请求代次失效，刷新后只恢复条件。后端回收15分钟无活动任务及其标签，避免仅依赖浏览器离开事件。查不到的任务返回404及清楚的过期／不存在说明，前端保留已显示内容并允许重新搜索。

来源故障至少区分未配置、未登录／会话过期、需要验证、限流、超时、站点结构变化及普通网络失败。失败只影响该来源；没有足够证据时不标为职位下架。详情失败使用相应的安全错误码和中文文字，保留概要；不会静默调用其他业务接口或伪造成功结果。

### 7.4 分组、筛选与快照

前端以平台＋来源身份去重，再按来源交替组织。首版只有规范化后的完整标题、公司、完整地点和已取得的完整正文均一致，才具备跨站折叠证据；规范化仅统一空白，不删除部门、地点或招聘要求。任一关键字段缺失、只有名称相似或正文不同均独立保留。薪资不参与合成，差异按来源展示。算法单独测试，同一来源须同时符合全部筛选，未知开关逐字段处理；经验、学历选项保留原文。

收藏快照不被后续详情或搜索覆盖。来源 JD 关联与快照分开存储，取消收藏和删除资料互不影响。服务端只接受当前任务或已有收藏中的来源身份，不能信任前端提交的任意岗位内容作为原站数据。

| 方法与路径 | 输入 | 输出与幂等规则 |
|---|---|---|
| GET `/favorites` | 无 | `Favorite[]`；每项 `{id,createdAt,sources}`，来源在 `JobRecord` 上增加 `savedAt`；按首次收藏倒序，同时间按 id 排序 |
| POST `/favorites` | `{taskId,jobKeys,favoriteId?}` | 返回 `Favorite`；从任务复制已取得快照。整组收藏及补充只添加新来源，已存在来源不覆盖；相同来源不产生副本。多来源须满足同岗证据 |
| DELETE `/favorites/{id}` | 无 | 204；删除整条收藏及快照，保留 JD |
| POST `/jd-links/lookup` | `{keys}` | 返回以 key 为索引的对象；未保存为 null，已保存值为 `{materialId,source,sourceId,url,fetchedAt,contentEdited}`；只读，不抓取 |
| POST `/jd` | `{taskId?,favoriteId?,jobKey,name,content}` | 返回 `{materialId,created,source}`；必须能从指定任务或收藏找到原岗位。名称非空≤120、正文非空≤24000；同一来源已有关联时返回原资料，不覆盖编辑。关联资料已删除时可重新创建 |

资料库跳转统一为 `/materials?kind=JD&materialId={id}`，真实选中并打开目标资料。`Material` 增加可空 `jobSource` 元数据，结构与上表的已保存关联相同；普通手工资料为空。资料后续编辑应更新 `contentEdited` 的事实，不能丢失原来源身份或用该字段改写用户正文。

### 7.5 存储、事务与兼容

在现有 SQLite 中增加 `job_search_favorites`（id、created_at）、`job_search_favorite_sources`（source_key 主键、favorite_id、snapshot_json、saved_at）及 `job_search_jd_links`（source_key 主键、material_id、source_json、原正文摘要）。来源身份唯一约束实现收藏与 JD 的幂等；外键仅约束新增业务实体，不关联投递表。收藏删除级联自己的来源，资料删除使相应 JD 关联失效；不恢复已删除资料。

整组收藏／补充与 JD 新建／关联使用现有 `SqliteStore.atomic`，并发重试不得留下重复资料或半条收藏。新增表通过 `CREATE TABLE IF NOT EXISTS` 创建，结构预检及维护脚本只放行这三张可缺失新表和已声明索引，保留原有严格校验。不得重建旧库。新增表之后旧 JAR 可能拒绝新结构，恢复方式沿用上文第6节。

### 7.6 实施与验证接缝

后端负责来源适配、任务状态、输入与 URL 边界、持久化和幂等；前端负责已确认的页签、列表／详情、原文筛选、来源差异、预览和响应式。以以上契约分开实现，双方不得静默改字段或路径。行为先写失败测试；招聘站点不可控部分使用明确标识的严格替身测试状态与边界，真实 CDP 样例另行核验，不以替身宣称源站质量通过。

后端集成覆盖批量与来源身份、失败／重试／停止竞态、快照不覆盖、事务幂等、删除后重存、旧库兼容。前端覆盖实际路径、页签／离开清理、过滤不串来源、晚到响应、失败保留编辑和精确资料跳转。集成后执行标准工程、真实隔离浏览器和三站运行样例，再按持续授权提交同步、重启日常后端和核对原数据；实际结果仍只记录在 VERIFICATION。
