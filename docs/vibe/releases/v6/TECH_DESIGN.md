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
