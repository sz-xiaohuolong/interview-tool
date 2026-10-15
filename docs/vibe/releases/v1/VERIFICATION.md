# V1 验证记录

| 范围 | 当前事实 |
|---|---|
| V1 功能 | 原验收记录及合成模型样本已归档；AC01～AC11 见下表 |
| 后续模型配置 | `high` 参数测试及真实请求通过；未重新进行完整模型语义评测 |
| 复现入口 | 根目录 README 的测试命令；公开证据在本目录 `evidence/` |

本次文档整理与仓库同步不改变代码行为，复用下列有效证据。受版本控制的摘要和合成样本可跨机器查阅；`.runtime/`、`.evidence/`、`backend/test-results/` 中的原始日志、截图与本机数据库仅本地保留，不随仓库上传。

## 验证过程记录


2026-10-15：仓库为空；Java 21.0.8、Maven、Node 23.11.0 可用。环境无模型 Key；用户表示会配置 `.env.local`，继续开发。其余证据待执行后记录。

2026-10-15 14:00：后端 23 项测试与 package 通过，真实 jar 独立重启验证通过；原始记录 backend/test-results/final-build.log 和 jar-smoke.json。前端 16 项单测通过，vue-tsc + Vite 生产构建通过。scripts/smoke.py 对实际 8080 Mock 服务的 7 组 HTTP 验收通过，记录 .evidence/mock-smoke.json。当前浏览器与第二轮独立审查进行中，真实模型仍等待配置。

## V1 验收结果（2026-10-15）

代码、本地 Mock 验收及真实模型合成样本验收已完成。真实服务使用 `gpt-6.1-sol`；结构检查与逐项语义判读分别记录，**不将 Mock 结果视为真实模型质量证明，也不将有限样本推广为所有输入的质量保证**。

| 验收项 | 当前证据 | 状态 |
|---|---|---|
| AC01 启动与页面 | 实际 8080/5173 启动，Playwright 独立 18080/4173 全流程 | 通过 |
| AC02 快速面试与追问约束 | ApiIntegrationTest、HTTP/浏览器验收；真实模型两次引用回答追问后切换主问题 | 通过 |
| AC03 简历押题与选题 | PDF/DOCX/Markdown 解析测试；真实10题依据逐字匹配简历，选题练习与报告完成 | 通过 |
| AC04 项目与资料 | 多附件/截断/TXT上传；真实L1/L2/L3共3主问题、2追问、5回答，假设明确标注 | 通过 |
| AC05 复盘与重练 | 四维依据、原始回答、重练；真实报告识别故意答错，区分缺失与无法判断，引用对应原文 | 通过 |
| AC06 持久化与恢复 | 真实 jar 停止/重启恢复；浏览器刷新已答/未答草稿；pendingAction 恢复 | 通过 |
| AC07 幂等与终态 | 请求ID+问题ID、结束后拒绝、生成失败重试、多标签页冲突保留草稿 | 通过 |
| AC08 模型异常 | 可控服务覆盖缺Key/429/超时/非法JSON/动作及数值边界；真实长报告超时后重启保留对话，单次重试成功 | 通过 |
| AC09 文件边界 | 5 MiB、文件类型/空内容/损坏/UTF-8、提取截断、扫描PDF提示 | 通过 |
| AC10 事实与信息边界 | 系统规则、引用校验、快照、白名单、防答案泄露、跨源回归；真实样本无虚构技术/指标，假设与已有事实分开 | 通过（真实模型结论限定本次样本） |
| AC11 复现与说明 | README、dev/test/e2e脚本、HTTP smoke、锁文件 | 通过 |

### 可复现证据

- 后端：`mvn -f backend/pom.xml test package`，29 项，0 失败/0 错误；`backend/test-results/final-build.log`。
- 前端：`npm --prefix frontend test`，4 个测试文件、21 项全部通过；`npm --prefix frontend run build` 完成 TypeScript 检查与 Vite 构建。使用 Node.js 22.23.3 验证。
- 浏览器：`bash scripts/e2e.sh`，4 项全部通过，最终运行 7.9 秒；`.evidence/e2e-final.log`。验证三种业务路径、刷新、快捷发送、历史、报告和 390px 窄屏，未发现控制台错误。
- HTTP：`python3 scripts/smoke.py --output .evidence/mock-smoke.json`，7 组通过，仅使用合成资料。
- 重启：`backend/test-results/jar-smoke.json`、`jar-smoke-server.log`、`jar-restart-server.log`，独立18080真实进程验证。后续修复的模型处理路径另有单测与新JAR端到端验证。
- 视觉：`.evidence/e2e-home.png`、`.evidence/e2e-report.png`、`.evidence/e2e-report-mobile.png`。
- 依赖：前端 `npm audit` 返回 0 个已知漏洞；这不是对所有依赖和应用的全面安全审计。
- 真实三路径：`python3 scripts/smoke.py --real --output .evidence/real-smoke.json`，7组通过；3份报告均 READY，10道押题已保存。独立语义审查核对10条押题依据及18条报告引用，均对应原文。
- 真实项目深挖：[项目深挖证据](evidence/real-project-depth.json)，3主问题、2追问、5回答；故意错误被正确归类，13条报告引用均可定位；达到追问上限后正常结束。
- 真实恢复：该深挖报告首次超过60秒，状态明确为 FAILED；调整本地超时为180秒并重启后，完整对话逐条一致；仅重试原报告一次成功，删除自建合成资料后历史与报告仍一致。
- 真实界面：已在浏览器检查模型标识与完整复盘，控制台无错误；`.evidence/real-report.jpg`。
- 本地交付：通过 `./scripts/dev.sh` 启动日常环境，8080健康与5173首页200；[本地交付证据](evidence/local-delivery.json)。日常 `data/` 初始为空，验收库保留在 `.runtime/real-qa-data/`。

### 审查闭环

独立只读审查识别并修复：编码路径跨源写入绕过、评分/题号整数溢出、JSON尾部非法内容、假设场景缺少可见标记、末题动作空白导致卡住、押题题干泄露参考答案、多标签页未保存草稿丢失、资料分类入口错误。每项按适用范围补充失败回归并复验；最终针对性复核无剩余代码阻断。

### 真实模型接入与限制

用户授权代填配置后，首次请求因简称 `6.1sol` 不被识别而返回400。查询服务模型列表后改为完整ID `gpt-6.1-sol`，正常HTTPS请求成功。长报告需更长等待，本地超时配置为180秒。上述问题均已按实际原因修正配置并验证；未改模型客户端行为。

模型服务仍可能限流或超时；报告是练习参考。验证覆盖指定模型和合成样本，不证明所有题目都正确，也未进行大规模质量评测。扫描PDF不支持OCR，公网部署与多用户使用不属于V1。

## high 配置的后续验证

2026-10-15：新增可选 `LLM_REASONING_EFFORT` 并将本地值设为 `high`。请求测试先因缺少字段失败，再验证规划、面试官、押题与评估四种任务均发送 `high`，同时验证留空不发送该字段。执行 `mvn -o -f backend/pom.xml test package`，31项测试通过、0失败、0错误，打包成功；本地原始日志为 `.runtime/reasoning-red.log` 与 `.runtime/reasoning-green.log`。

真实服务的合成配置请求返回200，见 [参数兼容性证据](evidence/reasoning-upstream.json)。这次检查证明参数传递及服务接受请求；上方完整语义样本采用当时的服务端默认思考强度。

## 被测源文件定位

[源文件摘要](evidence/source-manifest.sha256) 登记本次归档的代码、测试、依赖锁与公开配置的 SHA-256。文档归档未修改这些文件。前端测试与浏览器证据采集于 `high` 接入前，前端源文件未改变；后端31项检查覆盖 `high` 接入后的代码。
