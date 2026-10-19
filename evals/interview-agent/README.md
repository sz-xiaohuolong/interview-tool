# 专业面试 Agent 固定评测

这套资产比较本项目原有综合面试策略与升级后的策略。它是开发用命令行评测，不增加网页入口，不创建另一套后端，也不把 Mock 结果当作模型质量证据。

## 固定范围

`cases.json` 保存24个合成案例，Java后端、Agent/RAG/Tool Calling、项目架构与实现、异常回答与边界各6例。每例保留岗位、职级、原问题、候选回答、历史问答、已提及考点、来源编号、合理行为、禁止行为及六维人工评分规则。首批为 `JAVA-01`、`JAVA-06`、`AGENT-01`、`AGENT-02`、`ARCH-01`、`EDGE-03`。

`coveredPoints` 只描述候选人已经提及的内容，不预先向模型标注正确性、缺口或下一步。人工预期、禁止行为和评分规则只进入 `human-reference.json` 和评阅包，输入生成器采用白名单，禁止把这些字段发送给模型。

每例固定调用一次 `FOLLOWUP` 和一次 `REVIEW`。复盘只针对原有固定问答，不把新追问的未知回答补进来。原、新策略收到相同输入，包括历史、来源、`strategyVersion=1`、技术阶段和明确假设项目；复盘使用 `reviewVersion=2`、`assessmentVersion=1`，不生成真实项目矩阵。这轮检验固定上下文中的追问与复盘，不证明多轮动态诊断、完整会话或真实项目质量。

## 原策略与来源

`baseline/` 直接保存 Git 提交 `92261c9` 的本项目综合面试 `followup.txt`、`review.txt` 和 `InterviewOutputValidator.java` 文本快照；SHA-256及原始路径见 `baseline/manifest.json`。基线不是参考仓库的 Skill，也不会接入正式业务服务。升级策略从当前 `backend/src/main/resources/prompts/interview/` 复制到本机运行目录后固定摘要。

`sources.json` 的9条技术短引在2026年10月19日使用 Python `urllib` 从一手来源实际获取，去除脚本/样式并折叠连续空白后逐字匹配。每条保存网址、获取日期、HTTP正文摘要和短引摘要。来源包括 Java规范/API、Spring、PostgreSQL、Microsoft、OpenAI、RAG原论文和OWASP；短引只支持其明确范围，不应据此推断完整技术结论。缺来源案例保持空数组，用于检查模型能否保留未知。默认校验与运行不刷新来源。

运行器执行的是**公共 Python 机械校验子集**，不是 Java生产校验器或完整业务Service。它核对严格JSON、来源身份、已答题号、连续原答短引、官方短引及详细评估关联；新策略还核对有限诊断。`referenceProductionValidatorHash` 仅说明参考的生产契约快照，没有表示该Java类已执行。模型提出的动作与服务约束后的 `effectiveAction` 分开记录；次数上限不会被混写成JSON格式错误。真实软件流程仍须由项目后端和浏览器测试验证。

准备协议和每项结果同时绑定运行器源码摘要 `runnerHash`；执行前及跨策略、扩组对比时重新核对，脚本变化后不能混用旧协议结果。

## 默认离线操作

在仓库根目录执行：

```bash
python3 scripts/interview-agent-eval.py
python3 -B -m unittest discover -s evals/interview-agent/tests -v
```

默认命令只校验24例、来源摘要和原策略快照，不读取模型密钥、不发请求、不写评测目录。可离线生成输入和空白评分表：

```bash
python3 scripts/interview-agent-eval.py prepare \
  --strategy baseline --batch pilot \
  --out .evidence/interview-agent-eval/baseline-pilot
```

未带模型配置的准备结果会标为“未配置”，不能直接真实执行；需要真实评测时应新建一个绑定实际配置的目录，不能覆盖已存在的输入或人工记录。

## 首批真实基线：最多12次请求

只有显式 `real` 子命令才会调用模型。主代理执行前读取已经获准的本机连接；本脚本不执行 `.env.local`，也不启动应用或创建面试。

配置可来自现有 `data/model-config.json` 的完整连接、`--config` 指定的本机JSON、环境变量 `INTERVIEW_AGENT_EVAL_CONFIG` 指向的JSON，或已导出的 `LLM_*` / `INTERVIEW_FOLLOWUP_*` 环境变量。持久文件如果仅记录 `source: STARTUP`，应使用已导出的启动环境。地址和密钥只保留内存，不打印或写入协议。准备时显式 `--use-env` 才读取启动环境。

下面两条命令沿用同一已导出的本机环境：

```bash
python3 scripts/interview-agent-eval.py prepare \
  --strategy baseline --batch pilot --use-env \
  --out .evidence/interview-agent-eval/baseline-pilot-real

python3 scripts/interview-agent-eval.py real \
  --run .evidence/interview-agent-eval/baseline-pilot-real \
  --max-attempts 12
```

使用配置文件时，准备与真实运行均传相同的 `--config`。固定参数为温度0.4，追问输出上限800、复盘10000；模型、追问模型、两种思考强度、DeepSeek追问关闭思考设置及脱敏服务身份全部绑定协议。实际响应模型、用量、结束原因与指纹另行保存。模型费用由服务方计收，声明输出上限不是账单费用。

模型HTTP请求直达 `/chat/completions`，不隐式调用 `ANALYZE` / `PLAN`，不自动重试、不跟随重定向。网络失败、非法JSON、截断或机械校验失败立即停止该批，失败仍计入预算。原始成功/非法模型输出和校验结果保存在本机 `.evidence`，HTTP错误正文因可能含敏感信息不保存或显示。

## 人工基线关卡

12项原策略结果齐备后导出评阅包：

```bash
python3 scripts/interview-agent-eval.py pack \
  --run .evidence/interview-agent-eval/baseline-pilot-real
```

人工阅读 `review.md`、原问原答、历史、来源及 `human-reference.json`，逐例填写 `scores.json`：

- 六个维度分别填0、1、2；不适用填字符串 `N/A` 并写理由，不能用0代替不适用。每个有效分值也须有具体理由。
- `blockers` 只填实际发生的 `ANSWER_LEAK`、`FABRICATED_EXPERIENCE`、`FABRICATED_TECHNICAL_EVIDENCE`；没有则空数组。正式面试中泄题、捏造经历或技术依据都阻断新策略通过，平均分不能抵消。
- `baselineIssues` 填实际观察到的问题，例如 `{"metric":"no_repetition","description":"再次问了原答已完整说明的租户缓存隔离"}`。没有问题用空数组，不能为证明改善而事后改写基线。
- 人工完成后，手动将评分文件的 `confirmedByHuman` 改为 `true`，填写 `reviewer`。
- 再手动填写 `human-gate.json` 的 `confirmedByHuman: true`、真实 `reviewer` 与 `confirmedAt`。`runId`、`taskHashes` 和 `resultsHash` 由运行器绑定，保持原值。

运行器没有自动确认命令，不填写人工分值。测试中的人工字段只是本机合成测试占位，不能作为真实人工标注。Agent可协助整理证据，但不能把自己的初评写成用户人工评分。

## 新策略、匿名包与对比

完成对应批次原策略人工评分后，才允许运行同批新策略：

```bash
python3 scripts/interview-agent-eval.py prepare \
  --strategy candidate --batch pilot --use-env \
  --out .evidence/interview-agent-eval/candidate-pilot

python3 scripts/interview-agent-eval.py real \
  --run .evidence/interview-agent-eval/candidate-pilot \
  --baseline .evidence/interview-agent-eval/baseline-pilot-real \
  --max-attempts 12

python3 scripts/interview-agent-eval.py pack \
  --run .evidence/interview-agent-eval/candidate-pilot

python3 scripts/interview-agent-eval.py blind \
  --baseline .evidence/interview-agent-eval/baseline-pilot-real \
  --candidate .evidence/interview-agent-eval/candidate-pilot \
  --out .evidence/interview-agent-eval/blind-pilot
```

匿名包按案例独立随机分配A/B，移除追问诊断和策略标签，只呈现可比较的面试行为。私有对应表写在输出目录同级，不与评分者共享。基线已经先行评分，因此这份包只用于后续匿名交叉检查，不声称完全双盲。人工完成新策略 `scores.json` 与 `human-gate.json` 后执行：

```bash
python3 scripts/interview-agent-eval.py compare \
  --baseline .evidence/interview-agent-eval/baseline-pilot-real \
  --candidate .evidence/interview-agent-eval/candidate-pilot \
  --out .evidence/interview-agent-eval/pilot-comparison.json
```

通过要求：整体及四类均不退步，且改善基线已暴露的至少一个对应维度问题；低于满分的基线维度也自动视为已有问题，不能通过留空 `baselineIssues` 绕过改善要求。没有基线问题时保留这一事实，不硬造改善。N/A适用范围改变会拒绝对比，需要人工先统一评分规则。样例、来源、输入、模型参数或校验版本不同会拒绝混组；Prompt及其生产契约摘要作为原/新策略各自身份保存。实际返回模型不同或缺失也拒绝混组。

## 扩展24例与预算复用

首批原/新合计24次，完整24例原/新合计96次。账本固定在仓库共享Git目录对应的 `.evidence/interview-agent-eval/ledger.json`，多个工作树共用；更换 `--out` 不会重置总账。账本登记在请求之前，进行中、失败和中断都计数。没有重置预算的CLI选项。

首批人工对照通过后，分别准备 `--batch full` 的原、新策略目录。每次真实运行额外传：

```text
--pilot-baseline .evidence/interview-agent-eval/baseline-pilot-real
--pilot-candidate .evidence/interview-agent-eval/candidate-pilot
--max-attempts 36
```

原策略完整批先运行并人工评分，随后新策略完整批才运行。输入、模型参数和Prompt不变时，成功的首批12项会按任务摘要复用，每种策略只再发送余下36次。扩组前会检查首批人工评分、阻断项及对照结果。原策略完整批人工评分可沿用已评首批内容，仍需核对完整批次身份和证据后确认。

同一成功任务再次运行会跳过；已有失败/中断任务不会自动重试。遇到失败后应先保存诊断、处理原因并与用户核对后续预算；不得删账本、换目录或改预期来凑够通过。未来改模型、Skill、Prompt或决策逻辑时，这套固定输入与人工规则可以继续作为回归资产，但新的真实调用仍须服从当时明确的授权和预算。
