# JavaBetter 原题素材与覆盖清单

核对日期：2026-10-21。目标岗位：大模型应用 / Agent 开发工程师。来源：[JavaBetter Agent 专题](https://javabetter.cn/ai/video/)。本目录保存题名、稳定身份、链接、难度及能力映射，不保存网站答案；原题版权归来源作者。难度为本项目按工程深度给出的选题参考，不是网站评级。

60 道不同原题已逐页通过 HTTPS 读取核对题名，映射 31/60 个考点。保留原能力目录全部 60 项；素材数量、映射数量、实际已问和已答是不同口径。未映射及素材用完时继续动态出题，不能据此推断用户能力不足。原始网页只暂存本机临时目录，不提交正文。

`javabetter-agent.json` 是可执行素材唯一事实源。`SeedQuestionCatalogTest` 校验数量、身份唯一、链接、难度、无答案字段以及所有考点引用；`SeedQuestionPlannerTest` 校验难度选择、历史排除、素材耗尽及动态回退；实际会话测试验证消息/复盘和重新读取快照。

## 主题覆盖

| 主题 | 有映射考点 / 总考点 | 素材数 |
|---|---|---|
| AGENT_ARCHITECTURE | 8 / 10 | 18 |
| AGENT_CONTEXT_MEMORY | 8 / 10 | 19 |
| AGENT_HARNESS_TOOL | 7 / 10 | 10 |
| AGENT_RAG | 3 / 10 | 3 |
| AGENT_EVALUATION | 2 / 10 | 2 |
| AGENT_FRAMEWORK | 3 / 10 | 8 |

## 原题映射

| 题目 ID | 原题 | 考点 ID | 难度 |
|---|---|---|---|
| `jb-workflow-vs-agent` | [Workflow 和 Agent 有什么区别？面试时该怎么回答？](https://javabetter.cn/ai/video/workflow-vs-agent.html) | `workflow-autonomy-boundary` | MEDIUM |
| `jb-workflow-vs-agent-flight-booking` | [订机票客服该用工作流还是自主 Agent？](https://javabetter.cn/ai/video/workflow-vs-agent-flight-booking.html) | `workflow-autonomy-boundary` | MEDIUM |
| `jb-what-is-agent` | [什么是 AI Agent？与直接调用大模型 API 有什么区别？](https://javabetter.cn/ai/video/what-is-agent.html) | `observation-backed-task-state` | EASY |
| `jb-how-agent-works` | [AI Agent 是如何工作的？完整执行过程拆解](https://javabetter.cn/ai/video/how-agent-works.html) | `observation-backed-task-state` | EASY |
| `jb-agent-chatbot-difference` | [Agent 和 ChatBot 有什么区别？如何让聊天机器人进化成 Agent？](https://javabetter.cn/ai/video/agent-chatbot-difference.html) | `workflow-autonomy-boundary` | EASY |
| `jb-agent-core-components` | [一个 AI Agent 有哪些核心组件？](https://javabetter.cn/ai/video/agent-core-components.html) | `framework-tool-loop-ownership` | EASY |
| `jb-what-is-react` | [什么是 ReAct？它和 CoT 有什么区别？](https://javabetter.cn/ai/video/what-is-react.html) | `framework-tool-loop-ownership` | MEDIUM |
| `jb-plan-and-execute` | [什么是 Plan-and-Execute？它和 ReAct 怎么选？](https://javabetter.cn/ai/video/plan-and-execute.html) | `dynamic-decomposition-contract` | MEDIUM |
| `jb-agent-plan-modes` | [Agent 的 Plan 模式有哪些实现方式？](https://javabetter.cn/ai/video/agent-plan-modes.html) | `dynamic-decomposition-contract` | MEDIUM |
| `jb-is-plan-mode-dead` | [Plan 模式已经死了吗？](https://javabetter.cn/ai/video/is-plan-mode-dead.html) | `workflow-autonomy-boundary` | HARD |
| `jb-react-death-loop` | [ReAct 会死循环吗？如何设计可靠的终止机制？](https://javabetter.cn/ai/video/react-death-loop.html) | `budgeted-refinement-exit` | MEDIUM |
| `jb-agent-intent-routing` | [Agent 的意图路由怎么设计？](https://javabetter.cn/ai/video/agent-intent-routing.html) | `ambiguous-intent-routing` | MEDIUM |
| `jb-agent-requirement-clarification` | [用户需求模糊时 Agent 怎么澄清？](https://javabetter.cn/ai/video/agent-requirement-clarification.html) | `ambiguous-intent-routing` | MEDIUM |
| `jb-multi-agent-collaboration` | [Multi-Agent 协作是怎么实现的？](https://javabetter.cn/ai/video/multi-agent-collaboration.html) | `dynamic-decomposition-contract` | MEDIUM |
| `jb-multi-agent-context-passing` | [多 Agent 之间怎么传递上下文？](https://javabetter.cn/ai/video/multi-agent-context-passing.html) | `delegated-context-boundary` | HARD |
| `jb-multi-agent-conflict-resolution` | [Multi-Agent 协作时意见冲突怎么办？](https://javabetter.cn/ai/video/multi-agent-conflict-resolution.html) | `graph-state-reducer-conflict` | HARD |
| `jb-multi-agent-file-conflict` | [多个 Agent 同时改一个文件，怎么办？](https://javabetter.cn/ai/video/multi-agent-file-conflict.html) | `graph-state-reducer-conflict` | HARD |
| `jb-agent-task-cancellation` | [用户中途取消，Agent 任务怎么停下来？](https://javabetter.cn/ai/video/agent-task-cancellation.html) | `cancellation-result-race` | HARD |
| `jb-agent-user-interjection` | [Agent执行过程中，用户插话怎么处理？](https://javabetter.cn/ai/video/agent-user-interjection.html) | `cancellation-result-race` | HARD |
| `jb-agent-tool-failure-timeout` | [Agent 调用工具失败或者超时了怎么办？](https://javabetter.cn/ai/video/agent-tool-failure-timeout.html) | `recoverable-tool-error-contract` | MEDIUM |
| `jb-agent-human-in-the-loop` | [怎么设计 Agent 的 Human-in-the-loop？](https://javabetter.cn/ai/video/agent-human-in-the-loop.html) | `approval-bound-operation-resume` | MEDIUM |
| `jb-what-is-permission-mode` | [Agent的权限模式（Permission Mode）了解吗？](https://javabetter.cn/ai/video/what-is-permission-mode.html) | `least-privilege-tool-execution` | MEDIUM |
| `jb-what-is-agent-sandbox` | [Agent 的沙箱（Sandbox）你了解吗？](https://javabetter.cn/ai/video/what-is-agent-sandbox.html) | `least-privilege-tool-execution` | MEDIUM |
| `jb-agent-hnow-tool-call` | [Agent 怎么知道该调用哪个工具？Function Calling 原理拆解](https://javabetter.cn/ai/video/agent-hnow-tool-call.html) | `overlapping-tool-choice-design` | EASY |
| `jb-tool-design-principles` | [字节面试：说说 Agent 中 Tool 的设计原则？](https://javabetter.cn/ai/video/tool-design-principles.html) | `tool-schema-semantic-validation` | MEDIUM |
| `jb-harness-five-elements` | [Harness 的五要素是什么？](https://javabetter.cn/ai/video/harness-five-elements.html) | `framework-tool-loop-ownership` | MEDIUM |
| `jb-what-is-harness-engineering` | [什么是 Harness Engineering？为什么 Agent 需要运行时？](https://javabetter.cn/ai/video/what-is-harness-engineering.html) | `framework-tool-loop-ownership` | MEDIUM |
| `jb-what-is-loop-engineering` | [什么是 Loop Engineering？](https://javabetter.cn/ai/video/what-is-loop-engineering.html) | `budgeted-refinement-exit` | MEDIUM |
| `jb-will-model-eat-harness` | [模型越来越强，Harness 会不会被模型「吃掉」？](https://javabetter.cn/ai/video/will-model-eat-harness.html) | `chatmodel-business-adapter` | HARD |
| `jb-why-handcraft-agent-not-framework` | [为什么选择手搓 Agent 而不用 LangChain 这种框架？](https://javabetter.cn/ai/video/why-handcraft-agent-not-framework.html) | `chatmodel-business-adapter` | HARD |
| `jb-pi-agent-design-patterns` | [Pi Agent 里有哪些反复出现的设计模式？](https://javabetter.cn/ai/video/pi-agent-design-patterns.html) | `skill-progressive-disclosure` | HARD |
| `jb-building-effective-agents-principles` | [如何做出来一个像 Pi 这样优秀的 Agent？](https://javabetter.cn/ai/video/building-effective-agents-principles.html) | `tool-schema-semantic-validation` | MEDIUM |
| `jb-what-is-agent-reflection` | [Agent 的反思机制是什么？Self-Refine 和 Reflexion 有什么区别？](https://javabetter.cn/ai/video/what-is-agent-reflection.html) | `budgeted-refinement-exit` | MEDIUM |
| `jb-how-agent-accumulates-experience` | [Agent 如何积累经验和自我学习？](https://javabetter.cn/ai/video/how-agent-accumulates-experience.html) | `episodic-memory-retrieval` | MEDIUM |
| `jb-agent-long-term-memory` | [Agent 的长期记忆是怎么实现的？](https://javabetter.cn/ai/video/agent-long-term-memory.html) | `memory-history-separation` | MEDIUM |
| `jb-agent-short-term-memory` | [Agent 的短期记忆怎么实现？](https://javabetter.cn/ai/video/agent-short-term-memory.html) | `memory-history-separation` | EASY |
| `jb-how-to-give-agent-memory` | [怎么让 Agent 拥有记忆？短期与长期记忆设计](https://javabetter.cn/ai/video/how-to-give-agent-memory.html) | `memory-history-separation` | MEDIUM |
| `jb-why-llm-has-no-memory` | [为什么说 LLM 本身没有记忆？](https://javabetter.cn/ai/video/why-llm-has-no-memory.html) | `memory-history-separation` | EASY |
| `jb-what-is-context-window` | [什么是上下文窗口？它不是聊天窗口](https://javabetter.cn/ai/video/what-is-context-window.html) | `context-budget-priority` | EASY |
| `jb-what-is-context-engineering` | [什么是上下文工程（Context Engineering）？](https://javabetter.cn/ai/video/what-is-context-engineering.html) | `context-budget-priority` | MEDIUM |
| `jb-context-window-limit` | [为什么 LLM 的上下文窗口不能无限大？](https://javabetter.cn/ai/video/context-window-limit.html) | `context-budget-priority` | MEDIUM |
| `jb-agent-context-explosion` | [Agent 怎么避免上下文爆炸？四种常用方案](https://javabetter.cn/ai/video/agent-context-explosion.html) | `tool-turn-trimming-consistency` | MEDIUM |
| `jb-why-agent-gets-dumber` | [为什么聊着聊着 Agent 就变笨了？](https://javabetter.cn/ai/video/why-agent-gets-dumber.html) | `summary-fidelity-validation` | MEDIUM |
| `jb-llm-context-or-tool` | [对于Agent，LLM、Context、Tool谁更重要？](https://javabetter.cn/ai/video/llm-context-or-tool.html) | `execution-trace-causal-attribution` | HARD |
| `jb-agent-skill-hit-rate` | [Agent 挂了几十个 Skill，怎么保证命中率？](https://javabetter.cn/ai/video/agent-skill-hit-rate.html) | `skill-progressive-disclosure` | MEDIUM |
| `jb-agent-rag-pdf` | [Agent 的 RAG 遇到 PDF 怎么办？](https://javabetter.cn/ai/video/agent-rag-pdf.html) | `structure-aware-document-chunking` | MEDIUM |
| `jb-rag-hybrid-search` | [RAG 怎么做向量和关键词的混合检索？](https://javabetter.cn/ai/video/rag-hybrid-search.html) | `lexical-semantic-retrieval-fusion` | MEDIUM |
| `jb-what-is-prompt-caching` | [DeepSeek 为什么能让 Prompt Caching 自动成本爆降 90%，而 Claude 还需要 cache_control 字段手动控制？](https://javabetter.cn/ai/video/what-is-prompt-caching.html) | `prompt-cache-memory-boundary` | MEDIUM |
| `jb-what-is-prefix-caching` | [Prefix Caching（前缀缓存）到底是什么？](https://javabetter.cn/ai/video/what-is-prefix-caching.html) | `prompt-cache-memory-boundary` | MEDIUM |
| `jb-what-is-kv-cache` | [KV Cache 是什么？为什么只缓存 K 和 V，不缓存 Q？](https://javabetter.cn/ai/video/what-is-kv-cache.html) | `prompt-cache-memory-boundary` | MEDIUM |
| `jb-api-cache-hit-miss` | [大模型 API 缓存命中和未命中为什么差价巨大？](https://javabetter.cn/ai/video/api-cache-hit-miss.html) | `latency-cost-quality-frontier` | MEDIUM |
| `jb-what-benchmarks-test` | [SWE-bench、Terminal-Bench、GPQA Diamond 这些评测集到底在测什么？](https://javabetter.cn/ai/video/what-benchmarks-test.html) | `representative-eval-risk-strata` | MEDIUM |
| `jb-codex-long-term-memory` | [Codex 的长期记忆是怎么实现的？](https://javabetter.cn/ai/video/codex-long-term-memory.html) | `episodic-memory-retrieval` | MEDIUM |
| `jb-claude-code-memory-retrieval` | [Claude Code 如何检索长期记忆？](https://javabetter.cn/ai/video/claude-code-memory-retrieval.html) | `episodic-memory-retrieval` | MEDIUM |
| `jb-how-to-write-claudemd` | [CLAUDE.md 怎么写才真正有用？](https://javabetter.cn/ai/video/how-to-write-claudemd.html) | `just-in-time-context-refresh` | MEDIUM |
| `jb-claude-code-long-term-memory` | [Claude Code 的长期记忆是怎么实现的？](https://javabetter.cn/ai/video/claude-code-long-term-memory.html) | `memory-correction-provenance` | MEDIUM |
| `jb-codex-short-term-memory` | [Codex 的短期记忆是怎么实现的？](https://javabetter.cn/ai/video/codex-short-term-memory.html) | `summary-fidelity-validation` | MEDIUM |
| `jb-claude-code-short-term-memory` | [Claude Code 的短期记忆是怎么实现的？](https://javabetter.cn/ai/video/claude-code-short-term-memory.html) | `tool-turn-trimming-consistency` | MEDIUM |
| `jb-agent-hallucination-governance` | [Agent 出现幻觉怎么治理？](https://javabetter.cn/ai/video/agent-hallucination-governance.html) | `retrieved-evidence-claim-alignment` | HARD |
| `jb-deepseek-outage-agent-fallback` | [DeepSeek 宕机 5 小时赔付 7 天账单，如何应对 API 的单点依赖？](https://javabetter.cn/ai/video/deepseek-outage-agent-fallback.html) | `model-routing-semantic-fallback` | HARD |

## 暂未映射的考点

这些考点继续使用原动态出题，不强行关联主题相近的文章。

- `dependency-aware-parallelism`：任务依赖与受限并行
- `handoff-conversation-ownership`：委派执行与会话移交
- `tenant-memory-namespace`：跨线程记忆与租户隔离
- `memory-write-trust-gate`：记忆写入的可信边界
- `untrusted-tool-output-boundary`：不可信工具结果中的指令
- `duplicate-tool-write-idempotency`：写工具重试与业务幂等
- `tool-result-information-budget`：工具结果的信息密度
- `source-ingestion-integrity`：资料导入与正文完整性
- `embedding-index-migration`：嵌入模型变更与索引迁移
- `conversation-query-rewriting`：多轮问题改写与检索意图
- `retrieval-permission-enforcement`：检索时的资料权限约束
- `reranking-candidate-recall-boundary`：重排与召回问题的区分
- `insufficient-conflicting-retrieval-evidence`：无命中与证据冲突的回答边界
- `index-update-delete-propagation`：文档更新删除与检索一致性
- `paired-baseline-regression-experiment`：同批样本的基线与策略对照
- `stochastic-run-reliability`：随机执行的重复性与稳定性
- `outcome-trajectory-evaluation`：任务结果与执行路径的分别评估
- `judge-human-calibration`：模型评分器与人工校准
- `retrieval-generation-error-isolation`：检索与生成质量的分层归因
- `rubric-grounded-evaluation-feedback`：可复核评分规则与依据
- `adversarial-tool-permission-regression`：工具权限与恶意输入回归
- `evaluation-holdout-contamination`：保留样本与评测污染
- `advisor-chain-order`：Advisor 顺序与上下文处理
- `structured-output-business-validation`：结构化输出后的业务验证
- `checkpoint-thread-identity`：检查点与线程身份恢复
- `persisted-state-schema-evolution`：持久状态变更的兼容处理
- `streaming-event-channel-separation`：流式事件与用户可见内容
- `reactive-blocking-execution-boundary`：同步工具与流式链路的调度
- `framework-api-upgrade-contract`：框架升级与真实调用契约
