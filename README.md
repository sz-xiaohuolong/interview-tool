# 面试练习室 · AI 技术面试助手

面向个人本地使用的文本技术面试工具。围绕 Java 后端、AI Agent 开发、自定义岗位、简历和项目进行动态追问，保存完整对话与结构化复盘。

## 环境与启动

需要 Java 21、Maven 3.9+、Node.js 22 LTS（22.12+）或 Node.js 24 LTS、npm。SQLite 驱动随 Maven 安装，无需单独安装数据库。

```bash
test -f .env.local || cp .env.example .env.local
# 编辑 .env.local 后启动
./scripts/dev.sh
```

首次启动会安装前端依赖并下载 Maven 依赖。打开 <http://127.0.0.1:5173>，后端监听 `127.0.0.1:8080`。终端 `Ctrl+C` 停止两项服务；日志在 `.runtime/backend.log` 和 `.runtime/frontend.log`。

日常开发入口已接入 V3：`./scripts/dev.sh` 使用 V3 构建与前端配置，原综合面试入口进入新版准备流程，继续读取现有 `data/` 资料库及 `.env.local` 模型和 V2 配置。V3 会话与公开知识缓存新增在 `data/v3/`，无需迁移数据库。使用方式见 [V3 说明](v3/README.md)。

调整 Java 包结构后，先停止开发服务，再执行 `mvn -f backend/pom.xml clean` 清除旧 class，然后重新启动，避免旧包中的类残留并参与 Spring 扫描。

模型尚未配置时，可以先在 `.env.local` 显式设置 `LLM_PROVIDER=mock` 体验业务流程。页面、会话和报告都会标注“测试模式”。Mock 的题目、追问和评分只用于验证软件流程，不能视为能力评价。正式练习需使用 `openai` 模式。

## 模型配置

`.env.local` 仅用于本地，已加入 Git 忽略。启动脚本会加载它；直接使用 Maven 启动时需要自行导出环境变量。修改后重启后端。

```dotenv
LLM_PROVIDER=openai
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=你的密钥
LLM_MODEL=gpt-4o-mini
LLM_TIMEOUT_SECONDS=60
LLM_REASONING_EFFORT=
```

`LLM_BASE_URL` 是包含 `/v1` 的服务根地址，应用在其后请求 `/chat/completions`。模型需要支持聊天接口与 JSON 输出；可替换成兼容服务的地址、模型与密钥。模型名称必须使用服务商 API 的完整 ID，例如本次验证的 `gpt-6.1-sol`，不能使用 `6.1sol` 这样的简称。不要在变量等号两侧加空格；含空格或 shell 特殊字符的值使用单引号包裹。`.env.local` 由 Bash 加载，只使用自己编写、可信的配置文件。

资料保存在本机；开始真实面试、押题和复盘时，相关资料文本与对话会发送到你配置的模型服务。Key 只在后端读取，不进入前端代码或接口响应。默认缺少 Key 会给出配置错误，不会自动切换到 Mock。

如果长对话复盘超过默认的 60 秒，可将 `LLM_TIMEOUT_SECONDS` 调整为 `180` 后重启。本次真实模型配置使用 180 秒；报告超时后可直接重试生成，已保存的回答无需重新提交。

`LLM_REASONING_EFFORT=high` 会向聊天接口传入 `reasoning_effort: "high"`，适用于支持该参数的推理模型；留空使用服务端默认值。当前本地 `gpt-6.1-sol` 已配置为 `high`，统一用于规划、出题、押题与复盘，修改后需重启后端。

## 使用流程

- **快速面试**：选择岗位、级别、目标时长和风格，直接开始。默认 Agent 开发、初级、10 分钟、常规面试。每次提交一段回答，面试官最多连续追问两次。
- **简历押题**：在资料库上传 PDF、DOCX 或 Markdown 简历，再生成 10 道带依据与准备思路的题目。可以重新生成，或者选择一道题进行逐题模拟。
- **项目面试**：创建项目并填写背景、技术栈、个人职责，附加设计文档。面试覆盖整体理解、实现细节与假设场景权衡。
- **复盘**：主动结束或完成规划后查看四维评价、逐题分析、原始对话与薄弱项；可从薄弱项重新开始练习。
- **继续练习**：首页和历史保留进行中的会话，刷新后从服务端恢复。模型失败时保留已提交回答，使用“重试”继续。

旧模式的 5 / 10 / 20 分钟是题数强度，分别约 3 / 5 / 8 道主问题，并非强制倒计时。V3 不限时训练也按强度组织题目，并优先保证四阶段；计时实战另选 20 / 30 / 45 分钟，默认30分钟，确认开考后开始服务端倒计时。压力模式仍要求专业、克制，不进行人身评价。

V3 综合面试可选择一个主项目或填写简述，正常经过开场介绍、项目深挖、岗位技术与场景、候选人反问。新建项目深挖及 V3 综合面试的复盘提供七项项目证据矩阵，缺口可进入单题补练。作答支持打字和浏览器语音听写，识别后可以修改，再手动提交；应用不保存音频、不下载语音模型，浏览器可能在线处理语音。旧历史不会补算矩阵或计时。详细规则见 [V3 使用说明](v3/README.md)。

## 资料与数据

- SQLite 文件：`data/interview.db`；上传文件：`data/uploads/`。使用 `DATA_DIR` 可指定其他目录。
- 默认单文件最大 5 MiB；支持文本 PDF、DOCX、Markdown、TXT，简历上传限制为 PDF/DOCX/Markdown。
- 单份提取文本最多保留 24000 字符，进入模型的材料上下文最多 30000 字符；超长资料会显示截断提示。
- 扫描图片 PDF 暂不提供 OCR，需要先转成可提取文本的文件。
- 修改或删除资料不会改变已开始面试的资料快照与历史报告。
- 备份时先停止服务，再备份整个 `data/` 目录；恢复时同样先停止服务。不要把含个人资料的数据库或上传目录提交到 Git。

资料列表由后端的 `DATA_DIR` 决定。日常启动默认使用原 `data/`；如果使用新的独立验收目录，页面会显示另一份资料库，原来的简历、JD 和历史不会自动出现在那里。发现空列表时，先核对访问端口和数据目录，不要重复上传或覆盖原目录。V2 测试目录不能替代日常资料入口；将原库用于 V2 前，应先完成备份并按启用说明显式升级。

## 架构

```text
浏览器 Vue 3 / TypeScript / Vue Router / Tailwind
  └─ 同源 /api → Spring Boot 3 / Java 21
       ├─ Controller：输入与 HTTP 交互
       ├─ Service：资料解析、规划、面试状态、押题、复盘
       ├─ Mapper：SQLite 与资料快照
       └─ LLM 客户端：OpenAI 兼容 HTTP / 显式 Mock
```

后端根包 `cn.interview` 只保留启动类，其他代码按职责组织：

```text
backend/src/main/java/cn/interview/
  ├─ Application.java
  ├─ controller/   请求接收与 HTTP 响应
  ├─ service/      业务规则、状态流转与模型任务协调
  ├─ mapper/       SQLite 访问与启动前结构检查
  ├─ entity/       资料和文档数据对象
  ├─ dto/          层间传递的资料上下文
  ├─ client/       模型调用、显式 Mock 与并发限制
  ├─ validation/   模型输出校验
  ├─ config/       Spring 配置与请求边界
  ├─ exception/    业务异常与统一错误响应
  └─ common/       参数工具、标识和内容摘要
```

现有服务直接使用具体类，暂不拆分 Service 接口与 ServiceImpl；JSON 接口沿用现有格式，后续出现独立响应对象时再建立 VO。测试按被测职责放入对应包，跨包测试夹具只保存在测试源码中。

提示词独立保存在 `backend/src/main/resources/prompts/`。服务端校验模型 JSON，强制追问上限与状态规则；回答包含请求 ID 和问题 ID，避免重复提交与过期页面写入。报告保存原始对话引用，区分明确错误、没有提到、资料不足。

文档统一索引见 [DOCUMENT_MAP.md](DOCUMENT_MAP.md)。项目级文档包括[项目定位](docs/vibe/PROJECT.md)、[当前架构](docs/vibe/TECH_DESIGN.md)、[当前进度](docs/vibe/PROGRESS.md)与[决策记录](docs/vibe/DECISIONS.md)。V1 文档位于 `docs/vibe/releases/v1/`：[需求背景与范围](docs/vibe/releases/v1/PROJECT_BRIEF.md)、[完整产品行为与验收](docs/vibe/releases/v1/SPEC.md)、[实施计划](docs/vibe/releases/v1/IMPLEMENTATION_PLAN.md)、[实际验证记录](docs/vibe/releases/v1/VERIFICATION.md)。

## V2 人工验收与启用

V2 已新增五项训练功能：错题复习与能力档案、同题二答、JD 差距与短冲刺清单、自我介绍与 STAR 故事库、五阶段系统与 Agent 设计训练。当前正式版本仍是 V1；V2 验证范围和已知缺口见 [V2 验证记录](docs/vibe/releases/v2/VERIFICATION.md)。

V2 的代码默认开关为关闭，首次启用前需要显式迁移所选目录。新安装时可先用独立验收目录；日常使用原资料时，应备份并升级原 `data/`，而不是把空测试库替代为日常入口。具体迁移、启动及关闭步骤见 [V2 启用说明](docs/vibe/releases/v2/TECH_DESIGN.md#83-实际启用与关闭入口)。迁移前停止该目录的写入进程并保留完整备份；不要对运行中的个人库执行迁移。

开启后在“持续训练”导航进入新功能。JD 和故事在模型调用前先展示本次发送的快照；阶段反馈不会自动推进；同题重答会保留原回答。真实练习使用本机配置的模型，测试记录明确标注 Mock。

完成所选目录的迁移后，在本机 `.env.local` 设置以下启动配置，再从项目根目录运行 `./scripts/dev.sh`：

```dotenv
DATA_DIR=/absolute/path/to/interview-tool/data
V2_ENABLED=true
PORT=8080
API_PROXY_TARGET=http://127.0.0.1:8080
```

将示例路径替换为实际原资料目录。`DATA_DIR` 决定打开哪个 SQLite 文件，`V2_ENABLED` 决定是否开放 V2；端口仅用于访问对应进程。启动脚本读取 `.env.local` 并导出变量；后端返回 `/api/v2/status`，前端据此加载 V2 导航和路由。修改配置后须重启服务并刷新页面。日常统一访问 `http://127.0.0.1:5173`；独立测试服务不必常驻。IDE 直接运行后端不会自动执行本项目脚本，需要配置同样的数据目录和 V2 开关。

## 测试

```bash
# 后端测试、前端测试与生产构建
./scripts/test.sh

# 分开执行
mvn -f backend/pom.xml test
npm --prefix frontend test
npm --prefix frontend run build
```

浏览器验收使用独立临时数据库与明确标识的 Mock Provider，自动启动 4173 / 18080 两个测试服务，结束后关闭并清理临时数据，不读取 `.env.local`。首次会安装 Chromium。

```bash
# 浏览器端到端：三种业务流程、刷新、快捷发送、报告与 390px 响应式
bash scripts/e2e.sh

# V2 浏览器验收：显式 Mock、独立临时库
bash scripts/e2e.sh --config=playwright.v2.config.ts

# 对已启动的 Mock 后端做 HTTP 验收（新增合成测试会话）
python3 scripts/smoke.py --output .evidence/mock-smoke.json

# 对已配置并启动的真实模型做相同验收；结果需额外判读语义质量
python3 scripts/smoke.py --real --output .evidence/real-smoke.json
```

HTTP 验收只创建合成资料，结束后删除自己创建的资料并保留验收会话，以便检查历史快照。真实模型的出题相关性、追问意义和评估质量需要单独核验，不能由 Mock 测试代替。当前实际证据见 [V1 验证记录](docs/vibe/releases/v1/VERIFICATION.md) 和 [V2 验证记录](docs/vibe/releases/v2/VERIFICATION.md)，两者各自保留适用输入与配置边界。

## 已知边界

仅面向本机单人使用，不包含登录、实时语音视频对话、音频保存回放、云端部署、向量检索或 GitHub 扫码。语音仅提供浏览器听写；不支持时仍可打字。请保持回环地址绑定。LLM 会受到服务商限流、超时与输出格式支持程度影响；失败会明确显示并保留可恢复状态。模型评价是练习参考，引用资料不能保证模型的每条技术判断都正确。没有足够回答时报告会说明样本不足，不凭空推断能力。
