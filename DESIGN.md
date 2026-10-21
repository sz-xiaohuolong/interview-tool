---
name: 面试练习室
description: 浅色、低干扰的面试练习与复盘界面
colors:
  surface-canvas: "#f7faff"
  surface: "#ffffff"
  surface-soft: "#f0f4fa"
  text-primary: "#202c40"
  text-secondary: "#405069"
  text-muted: "#59677d"
  border-subtle: "#dce3ee"
  border-control: "#7f8ea3"
  accent: "#315fd5"
  accent-hover: "#254dbb"
  accent-soft: "#eaf0ff"
  accent-ink: "#294b9b"
  success: "#247456"
  success-soft: "#edf7f1"
  warning: "#855a16"
  warning-soft: "#fff7e8"
  danger: "#b13b43"
  danger-soft: "#fff0f1"
typography:
  headline:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "28px"
    fontWeight: 650
    lineHeight: 1.45
    letterSpacing: "-0.025em"
  title:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: 1.5
    letterSpacing: "-0.02em"
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.7
    letterSpacing: "normal"
  reading:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.85
    letterSpacing: "normal"
  label:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.5
    letterSpacing: "normal"
  navigation:
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.7
  breadcrumb:
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.7
  report-headline:
    fontSize: "32px"
    fontWeight: 650
    lineHeight: 1.4
    letterSpacing: "-0.025em"
  report-headline-mobile:
    fontSize: "30px"
    fontWeight: 650
    lineHeight: 1.4
    letterSpacing: "-0.025em"
  report-question:
    fontSize: "24px"
    fontWeight: 600
    lineHeight: 1.6
    letterSpacing: "-0.02em"
  report-body:
    fontSize: "20px"
    fontWeight: 400
    lineHeight: 1.8
  report-support:
    fontSize: "16px"
    lineHeight: 1.8
rounded:
  control: "8px"
  panel: "12px"
  dialog: "16px"
spacing:
  space-1: "4px"
  space-2: "8px"
  space-3: "12px"
  space-4: "16px"
  space-5: "20px"
  space-6: "24px"
  space-8: "32px"
  space-10: "40px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    padding: "10px 18px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.control}"
    padding: "10px 18px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.text-muted}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.control}"
    padding: "11px 13px"
  navigation:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent-ink}"
    rounded: "{rounded.control}"
    padding: "12px 14px"
  badge:
    backgroundColor: "{colors.success-soft}"
    textColor: "{colors.success}"
    rounded: "5px"
    padding: "4px 8px"
  panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.panel}"
    padding: "24px"
---
# 当前界面视觉规范

本文件维护项目当前生效的视觉系统，供日常界面维护和 Impeccable 读取。产品定位与行为由 [PROJECT.md](docs/vibe/PROJECT.md) 和当前正式版本的产品规范维护。

当前正式版本仍为 V1，日常入口已启用 V2。原 V1 重构保留在 [V1 界面设计记录](docs/vibe/releases/v1/UI_DESIGN.md)；当前 V2 导航、四字入口和复盘 C 布局归 [V2 界面设计记录](docs/vibe/releases/v2/UI_DESIGN.md)，实际结果由 [V2 验证记录](docs/vibe/releases/v2/VERIFICATION.md) 维护。本文件只保存当前生效的视觉系统，不替代版本任务记录。

## 设计方向

采用冷色中性底、蓝色操作重点、浅色侧栏和紧凑工作区。界面优先呈现主操作、阅读内容和清楚的状态反馈。

设计差异度 4 / 10，动效强度 2 / 10，信息密度 6 / 10。界面应先帮助用户选择练习、组织回答、理解复盘，再传递品牌感。

## 全局 Tokens

| 用途 | CSS 变量 | 值 |
|---|---|---|
| 页面背景 | `--surface-canvas` | `#f7faff` |
| 内容表面 | `--surface` | `#ffffff` |
| 次级表面 | `--surface-soft` | `#f0f4fa` |
| 正文 | `--text-primary` | `#202c40` |
| 次级正文 | `--text-secondary` | `#405069` |
| 辅助文字、占位文字 | `--text-muted` | `#59677d` |
| 分隔线 | `--border-subtle` | `#dce3ee` |
| 控件边界 | `--border-control` | `#7f8ea3` |
| 主操作、选中与焦点 | `--accent` | `#315fd5` |
| 主操作悬停 | `--accent-hover` | `#254dbb` |
| 浅蓝选中面 | `--accent-soft` | `#eaf0ff` |
| 浅蓝面上的文字 | `--accent-ink` | `#294b9b` |
| 成功文字 / 背景 | `--success` / `--success-soft` | `#247456` / `#edf7f1` |
| 警告文字 / 背景 | `--warning` / `--warning-soft` | `#855a16` / `#fff7e8` |
| 错误文字 / 背景 | `--danger` / `--danger-soft` | `#b13b43` / `#fff0f1` |

字体使用本地系统栈：`-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif`。不引入外部字体请求。

字号层级：辅助 12px、元信息 13px、控件与面包屑 14px、通用正文 15px、桌面主导航与专项工具 16px、三级标题 17px、二级标题 20px、工具页标题 28px、首页标题 34px。一级标题字距 `-0.025em`，二级标题 `-0.02em`；基础行高 1.7，段落 1.8，长文 1.85。计数、评分、进度统一 `tabular-nums`。

间距采用 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40px。圆角：徽章 5px，按钮与输入框 8px，独立表面 12px，弹窗 16px。布局仅用细边框、表面色阶和留白组织；仅弹窗使用带冷色偏向的柔和阴影。

## 表面模式

| 模式 | 适用内容 | 视觉重心 |
|---|---|---|
| Operate | 高频操作、配置表单、面试工作区 | 紧凑且边界清楚；状态和主操作易扫读；保持原生控件与键盘操作便利 |
| Read | 题目、回答、资料详情、复盘 | 以信息层级、舒适行高和自然换行为主；阅读正文控制在 65～75ch |
| Persuade | 首页引导与功能入口 | 左对齐、克制表达，突出真实功能和主入口；避免装饰干扰工具使用 |

V1 各页面的具体模式和布局见 [V1 界面设计记录](docs/vibe/releases/v1/UI_DESIGN.md#页面方案)；V2 导航采用 Operate，报告采用 Read，见 [V2 界面设计记录](docs/vibe/releases/v2/UI_DESIGN.md)。

## 公共组件与响应式

- Button、Input、Select、Badge 沿用现有原生元素和类名，不新增包装逻辑。Reka UI 弹窗继续负责焦点、Esc 和可访问性。
- 只通过公共样式更新 LoadingState 的视觉呈现；保留 `role="status"`、标签和所有调用条件。空态保留实际下一步入口，错误态保留原恢复动作。
- 桌面侧栏 216px，内容最大宽度 1320px；1180px 以下收紧侧栏和内容边距；900px 以下配置说明转下方；820px 以下导航转为顶部可横向滚动行；600px 以下主要模块单列；统计保留三列，评分和会话元信息保留两列。
- “专项训练”当前仅展示“错题重练”和“项目经历”。工具栏仅在专项模块及详情显示，普通页面和报告页不显示。能力看板、岗位诊断、系统设计的历史地址保留；原题重练地址归属错题重练。全站一级导航保持现状，面包屑父级可返回已有列表。
- 主导航桌面 16px、至少48px高；820px以下14px、至少44px高，局部横向滚动。专项工具16px、桌面至少48px高，600px以下至少44px高；面包屑14px可换行，窄屏状态区可另起行。
- 移动端仍可读取元信息和操作输入，不通过 CSS 隐藏功能。
- 390px 和窄至 320px 的布局不得出现整页横向滚动；长词、路径与模型正文自然换行；列表摘要可省略，历史岗位、详情标题、正文及附件名完整换行。输入框在移动端为 16px，降低聚焦缩放风险。
- 全站使用统一的浅色方案。

### 错题重练工作台

错题复习与原题重练共用工作台。桌面左栏宽270～330px，右侧展示所选题目、原答诊断、重新作答与变化对比；1000px以下收紧为230～280px左栏，760px以下切换列表和详情。搜索、筛选、桌面列表滚动和手机整页滚动在返回列表时恢复；未提交回答沿用已有草稿缓存。

同题关联案例集中放在“同题作答记录”折叠区，可以直接打开已有主问题、追问及作答。列表和详情均提供至少44px高的删除入口，使用原生确认框；成功后移除入口，失败保留内容。完成状态表示作答与评估已保存，不使用“已攻克”推断掌握。颜色、输入、面板及焦点样式沿用全局规则，验证见[V5专项工作台记录](docs/vibe/releases/v5/VERIFICATION.md#专项训练收敛与错题重练工作台2026-10-21)。

### 复盘阅读表面的局部覆盖

2026-10-16 按用户批准的 C 方案实施复盘阅读布局。该覆盖仅作用于报告内容、报告上下文工具，以及显示报告时的手机应用壳；其他页面继续使用全局字号和布局。字号以 frontmatter 中的 `report-*` 角色为准，字体和颜色沿用全局令牌。报告正文为20px／1.8，报告内容区辅助说明为16px；应用壳按上文导航与面包屑字号维护。

逐题原答、分类分析、回答思路与知识解释作为主文；岗位、概览、评分、整理复习、原题重练及完整对话作为辅助内容。原答和分析自然换行，不裁切模型输出。原始问答、推荐思路与知识解释的预格式正文最大行长为72ch。分类使用文字和状态色共同区分，保留原生 details/summary。

桌面右栏宽340px、栏间距32px；1280px以下右栏收为300px、栏间距24px；1100px以下单列。实际文档顺序为主文、概览、整理复习、原题重练、完整对话。600px以下标题为30px，操作目标至少44px，报告问题选择框至少48px高。报告页不显示专项工具栏。

范围与批准稿取舍见 [V2 界面设计记录](docs/vibe/releases/v2/UI_DESIGN.md)，实际结果见 [V2 验证记录](docs/vibe/releases/v2/VERIFICATION.md)；原 V1 过程记录保留。局部规则已实施，不等同于 Comp-led 像素门禁通过。

### V3 综合面试与知识依据的局部排版

2026-10-18 按用户批准的知识依据 C 稿实施，沿用系统字体和现有颜色令牌。V3 工作台的对话正文与作答输入为18px／1.8，辅助说明14px；来源标题与完整摘录16px／1.8，版本及获取时间14px，完整指纹13px等宽自然换行。摘录最大行长72ch，不裁切正文。

知识依据在页面下方，位于已有结束操作、报告及证据矩阵之后；默认收起成一行，展开后以单列和细分隔线组织来源，各来源继续使用原生折叠。审核稿右侧是展开状态的放大示意，不实现右栏或抽屉。保留原来源锚点，引用时展开对应摘录并聚焦标题。具体边界及证据见 [V3 技术设计](docs/vibe/releases/v3/TECH_DESIGN.md#知识依据排版2026-10-18) 和 [V3 验证记录](docs/vibe/releases/v3/VERIFICATION.md#知识依据排版限定验证2026-10-18)。

### V3 新版复盘的紧凑布局

2026-10-18 按用户指定的快面 AI 参考，调整 `report.version = 2` 的复盘阅读区。顶部“本场已讲清的内容”和“待补强”并排为两张等宽卡片，分别使用现有成功与警告令牌；700px 以下自然转为单列。卡片保留内容观察、改进建议和原答定位，原答摘录放在逐题回顾中。

下方以白色通栏卡片呈现“详细评估”，读取已有报告正文并保留段落、作答统计和四阶段记录。此处正文18px／1.8，诊断与逐题正文16px／1.8，标题18px，辅助文字14px；模块间距20px，双列间距16px。按钮、链接和折叠标题至少44px高。以上局部覆盖不改变旧版报告的字号。

项目证据覆盖与缺口保持展开，逐题分组继续支持定位后自动展开。“定向补练”和“查看完整原始对话”在底部使用原生 `details/summary` 默认收起；补练创建、继续、查看结果和失败恢复均保留在展开内容中。项目矩阵自身的补练入口保留。具体证据见 [V3 验证记录](docs/vibe/releases/v3/VERIFICATION.md#复盘紧凑布局限定验证2026-10-18)。

## 状态与动效

交互反馈时长 180ms，缓动 `cubic-bezier(0.22, 1, 0.36, 1)`。仅按钮、链接、输入控件、选中项与弹窗状态使用过渡；不做页面入场编排或装饰循环。请求中的既有旋转指示器仅表达真实忙碌状态；加载骨架静态呈现。遵循 `prefers-reduced-motion`，保留清晰的键盘焦点。状态同时通过文字表达。

正文、辅助文字、占位符与按钮文字对比度最低 4.5:1；大字最低 3:1。彩色提示面使用对应色系的深色文字。控件边框在白底与浅底的对比度均不低于 3:1。禁用按钮以浅底和边框区分，保持状态文字可读；键盘焦点为 2px 蓝色轮廓。

## 规范维护

机器可读的基础设计令牌位于本文件 frontmatter。组件可视化样例、动效和断点扩展位于 [.impeccable/design.json](.impeccable/design.json)，与本文件同步维护。版本任务的范围、过程和验证记录按 [文档职责地图](DOCUMENT_MAP.md) 进入对应版本目录。
