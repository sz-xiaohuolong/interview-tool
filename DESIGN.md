---
name: "面试练习室"
description: "开发者原生紧凑工作台：有界分栏、平面列表、证据可回查"
colors:
  surface-canvas: "#ffffff"
  surface: "#ffffff"
  surface-soft: "#f8fafc"
  text-primary: "#0f172a"
  text-secondary: "#334155"
  text-muted: "#64748b"
  border-subtle: "#e2e8f0"
  border-control: "#94a3b8"
  accent: "#2563eb"
  accent-hover: "#1d4ed8"
  accent-soft: "#eff6ff"
  accent-ink: "#1e40af"
  success: "#047857"
  success-soft: "#ecfdf5"
  warning: "#92400e"
  warning-soft: "#fffbeb"
  danger: "#be123c"
  danger-soft: "#fff1f2"
  navigation-active: "#e2e8f0b3"
  navigation-hover: "#eef2f6"
typography:
  headline:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "24px"
    fontWeight: 650
    lineHeight: 1.4
    letterSpacing: "-0.025em"
  title:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.5
    letterSpacing: "-0.02em"
  subtitle:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.6
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.6
  reading:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.75
  label:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.6
  metadata:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.75
  navigation:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.6
  button:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"PingFang SC\", \"Microsoft YaHei\", sans-serif"
    fontSize: "13px"
    fontWeight: 500
    lineHeight: 1.5
  data:
    fontFamily: "\"JetBrains Mono\", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.8
  metric:
    fontFamily: "\"JetBrains Mono\", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
    fontSize: "24px"
    fontWeight: 500
rounded:
  control: "6px"
  panel: "8px"
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
    padding: "6px 12px"
    typography: "{typography.button}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.control}"
    padding: "6px 12px"
    typography: "{typography.button}"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.text-muted}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
    typography: "{typography.button}"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.control}"
    padding: "7px 10px"
  navigation:
    backgroundColor: "{colors.navigation-active}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.control}"
    padding: "6px 10px"
  badge:
    backgroundColor: "{colors.success-soft}"
    textColor: "{colors.success}"
    rounded: "{rounded.control}"
    padding: "2px 6px"
  panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.panel}"
    padding: "16px"
  message:
    backgroundColor: "{colors.surface-soft}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.control}"
    padding: "12px 14px"
    typography: "{typography.reading}"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
---

# 面试练习室视觉规范

本规范的全局规则于2026-10-21依据当前源码更新；后续已确认但未实现的页面方案单独标注状态，不能视为当前产品行为。YAML frontmatter 是基础令牌，`.impeccable/design.json` 仅扩展阴影、动效、断点和组件演示。规范依据为 [全局样式](frontend/src/style.css)、[报告样式](frontend/src/styles/report.css)、[训练样式](frontend/src/styles/training.css) 与相应 Vue 组件；产品事实由 [PRODUCT.md](PRODUCT.md) 指向现有文档，实施与验证记录见 [PROGRESS.md](docs/vibe/PROGRESS.md)。下列英文二级标题与命名规则标记为 DESIGN.md 格式所需的固定标识符。

## Overview

**Creative North Star: "开发者原生紧凑工作台"**

以开发者熟悉的工具工作区为视觉方向：借鉴 Hoppscotch 的有界分栏、LobeChat 的问答与来源折叠、Linear 的平面行列表。界面围绕开始练习、作答、复盘与补练展开；主要内容清楚可见，辅助上下文在侧栏或折叠区内回查。

白色工作面与浅灰辅助面形成稳定层次，细分隔线划定职责。文字使用紧凑但可读的层级，长回答保留自然换行和阅读行高。蓝色突出操作，语义状态色附带文字解释；不把装饰性标语当作页面信息层级。

错题重练工作台于2026-10-22追加局部规则：推荐攻坚卡是唯一视觉高光，使用浅蓝表面与3px渐变顶条；题库和详情保持白底、1px细分隔线。页头标题16px／600，元信息至少11px，题目正文限制为72ch；分段筛选、选中边线及悬停采用150ms过渡，减少动态效果时禁用动画。桌面作答操作栏在详情滚动区贴底，手机沿用列表与详情切换。实现与行为边界见[V5技术设计](docs/vibe/releases/v5/TECH_DESIGN.md#工作台视觉与交互2026-10-22)。

**Key Characteristics:**

- 有边界的主从分栏，窄屏按内容顺序堆叠或在列表与详情之间切换。
- 高密度行列表与明确标题，正文、元信息和等宽数据各有职责。
- 普通表面平整，交互焦点、选中状态与真实反馈清楚可辨。

## Colors

配色由冷中性色、克制的操作蓝和可识别的状态色组成，具体色值以frontmatter为准。

- **主操作蓝**：`accent` 用于主按钮、焦点轮廓和活动页签；`accent-hover` 提供悬停反馈，`accent-soft` 与 `accent-ink` 用于选中内容、提示及用户回答。
- **工程状态色**：`success`、`warning`、`danger` 分别表达成功、警告和错误，配套浅色背景服务于小范围反馈，不用于装饰性填色。
- **中性色**：`surface` 与 `surface-canvas` 承载工作面，`surface-soft` 承载侧栏和辅助信息；三级文字色分别服务于正文、次要说明和元信息。
- **边界与导航**：`border-subtle` 用于区块分隔，`border-control` 用于输入与次按钮。主导航活动项使用 `navigation-active` 灰底与主文字色，悬停使用 `navigation-hover`；模块内页签使用蓝色下划线。

**The 语义优先 Rule.** 蓝色用于主要操作和选中反馈；成功、警告、错误色必须对应真实状态，并同时给出文字说明。

## Typography

系统无衬线字体服务于中文工具界面，不加载外部字体，没有独立装饰展示字。等宽字体优先使用本机 JetBrains Mono，并按frontmatter回退至系统等宽字体；数据同时使用等宽数字对齐。

- **页面标题**：`headline` 用于常规页面标题；手机常规标题缩至22px，报告标题保留24px。综合面试实训页使用18px紧凑标题，属于该页面的局部覆写。
- **区块标题**：`title` 与 `subtitle` 表达两级内容组织；避免只靠增加粗体段落形成长文层级。
- **界面正文**：`body` 是全局基础；输入与阅读正文使用14px。普通段落行高1.8，原始引文视场景使用1.85；对话与当前报告正文采用 `reading`。
- **元信息与数据**：`metadata` 承载日期、来源和辅助提示。`data` 表示小型上下文值，`metric` 表示报告指标；模型、耗时、轮次、分数与源码路径采用等宽字体，不将每个局部字号增设为全局令牌。
- **标签与操作**：表单标签采用 `label`，按钮采用 `button`；导航默认常规字重，选中后使用600字重。

**The 正文完整 Rule.** 原答、评估说明与来源正文保持自然换行；仅列表摘要允许省略，完整内容须可打开回查。

## Layout

外壳采用200px侧栏，可折叠为56px；右侧主工作面承载44px面包屑栏及页面内容。导航图标为16px线性图标，导航项最小高度32px。默认页面最大宽度1320px，配置页面最大1100px，报告最大1600px；实训与训练工作台铺满可用宽度。默认页面内边距为24px 28px 40px，窄屏逐步收紧。间距使用frontmatter中的4px基准阶梯，局部控件按实际内容调整。

分栏保持内容职责：面试主交互与上下文采用3:2，报告采用题号索引、阅读正文与辅助复盘三个区域，训练工作台采用左侧列表与右侧作答区。内部区块通过分隔线连接；列表基础高度44px，长内容可增长，不把完整正文压入固定行高。

响应式断点以实际作用区分：

| 视口上限 | 当前行为 |
|---|---|
| 1200px | 报告由三栏变为索引与正文两栏，辅助区移至正文下方。 |
| 1180px | 只调整专项训练导航水平内边距，不是全局外壳断点。 |
| 1100px | 通用双栏堆叠，默认页面收紧内边距。 |
| 1000px | 综合面试上下文移到对话下方；训练工作台调整列表比例与正文内边距。 |
| 900px | 项目面试、配置与通用训练阅读分栏堆叠。 |
| 820px | 左侧导航转为顶部横向导航，导航项最小高度44px。 |
| 800px | 报告改为单列，题号索引改为横向可滚动列表。 |
| 760px | 训练工作台在列表与详情之间切换，显示返回列表入口。 |
| 700px | 训练对比区与综合面试摘要改为单列。 |
| 600px | 常用按钮与展开控件最小高度44px，输入字号16px，页面边距再次收紧。 |

页面保持原生滚动；对话、来源检查器与题目索引可在自己的有界区域滚动。折叠和移动端切换不裁切原文，长路径可换行，代码可局部滚动。断点按实际内容压力使用，不复制成新的平行布局系统。

## Elevation & Depth

普通面板、列表与问答区不使用装饰阴影，层次来自背景、细边界与间距。弹窗使用半透明遮罩、轻微背景模糊和柔和阴影；当前阴影为 `0 16px 48px #0f172a26`，只用于浮层层级。弹窗采用焦点管理与独立滚动边界。

**The 平面分隔 Rule.** 普通区块使用细边界、背景差异和留白分层；嵌入工作区的内容优先去掉独立卡片外框。

交互状态过渡采用180ms及 `cubic-bezier(0.22, 1, 0.36, 1)`。按下按钮可有1px位移，弹窗进入只做短距离与透明度过渡；减少动态偏好下禁用非必要动效和顺滑滚动。动效不承担信息表达的唯一职责。

## Shapes

控件与问答气泡采用 `control` 小圆角，独立面板、工作区外边界和弹窗采用 `panel` 圆角。所有常规分隔线使用1px。工作区内嵌内容优先采用直边分隔，不重复绘制圆角外框；状态点保持圆形。当前状态徽章采用与控件一致的小圆角，属于已实现样式，不据此推导所有标签必须使用同一种轮廓。

## Components

### 按钮

紧凑、明确地表达主要和次要操作。主按钮与次按钮内边距见frontmatter，桌面最小高度32px；主按钮为蓝底白字，次按钮为白底与可见边界。轻量按钮默认无底色，悬停使用辅助底色。禁用态统一使用辅助文字与浅灰面，按下反馈保留。键盘焦点采用2px蓝色轮廓与3px间距；手机常用操作提高到44px。

### 输入与选择

原生输入、选择器与多行文本保留清晰边缘、小圆角及14px正文；内边距见frontmatter。悬停强化边界，聚焦使用蓝色边界与2px轮廓，轮廓间距2px。禁用态使用浅灰面。多行文本允许纵向调整；作答舱采用适应内容的高度并设置上限。手机输入字号16px。

### 导航与页签

主导航以灰底标记选中项，侧栏折叠后保留图标与可访问名称。模块内页签采用蓝色文字与2px底边，横向空间不足时允许局部滚动。模型设置入口位于侧栏底部，连接状态点配合文字说明；手机将入口移至顶部区域。

### 状态徽章与提示

徽章是小范围状态说明，使用12px文字及frontmatter中记录的紧凑内边距。成功、警告、错误及中性变体均要表达实际业务含义；不要重复输出已由标题说明的信息。提示条以浅色底、细边界和正文说明承载可处理的状态。

### 面板与行列表

独立面板使用白底、细边界与16px内边距。嵌入分栏后的内部面板去掉重复边界、圆角与阴影，以分割线和标题组织。通用行列表采用底部分隔线、主标题、少量元信息与尾部操作；摘要可省略，打开后显示完整内容。选中行以浅蓝反馈，悬停行使用浅灰面。

### 问答与证据

问答正文采用 `reading`，信息来源与角色使用元信息层级。面试官回答使用浅灰面，用户回答使用浅蓝面；正文最大行宽约72ch，报告阅读正文约75ch。输入舱位于对话区域下部，以顶部分隔线衔接。来源和原始对话通过折叠区回查，详情以真实内容展开；正式作答中不提前暴露参考答案或复盘专属证据。

## Do's and Don'ts

### 应遵循

- 使用主操作蓝、浅灰辅助面和细分隔线建立操作层次。
- 保留明确标题、完整正文与可回查的来源，把分数、耗时、轮次和路径排成等宽数据。
- 根据内容职责采用分栏或平面行列表；窄屏保留业务操作、键盘焦点和返回路径。
- 按钮、输入与可展开内容提供明确的焦点状态，状态色始终配文字。

### 应避免

- 不要把大圆角卡片层层嵌套，也不要给普通列表和面板增加厚重阴影。
- 不要使用装饰性眉题、无意义标语或字符图标替代明确标题和线性操作图标。
- 不要给任务状态、报告指标或图表填入未经返回的数值。
- 不要把旧版设计图、历史断点或尚未实现的交互写成当前规范。

## 资料库页面设计（2026-10-22，已实现／已验证）

本节记录用户通过grilling确认的八项决策，状态为`IMPLEMENTED`，已完成资料库前端实施与限定范围验证。范围为[Materials.vue](frontend/src/pages/material/Materials.vue)的界面与前端交互；业务依据为[V1资料管理需求](docs/vibe/releases/v1/SPEC.md#req05资料管理与本地保存)及[现有源码关联](docs/vibe/TECH_DESIGN.md#10-github-公开仓库源码面试)。本节为设计事实源，[布局草图](docs/vibe/releases/v5/materials-layout.html)仅作配套图示，使用合成资料，不连接API或保存文件。

### 已确认的方向与边界

| 决策 | 已确认方案 |
|---|---|
| Q1 页面目标 | 以整理、核对和更新资料为主；使用资料开始练习为次要快捷入口。 |
| Q2 功能范围 | 优化现有能力的展示与交互，可增加前端拖拽；不新增AI标签提取、匹配评分、默认主简历或加密能力。 |
| Q3 首屏结构 | 等规格资料卡片概览，只显示摘要；点击后打开详情，无主简历Hero、错落Bento或常驻全文预览。 |
| Q4 上传方式 | 拖拽与点击选择进入同一待上传状态，确认后才发送请求；新增简历与为既有资料追加附件分开。 |
| Q5 详情容器 | 桌面右侧宽抽屉，手机全屏；关闭后恢复原分类、搜索、滚动位置和焦点。 |
| Q6 新增入口 | 页头一个“新增资料”主按钮，选择简历／项目／目标JD；上传区放在录入界面和附件管理内，空库提供新增引导。 |
| Q7 浏览规则 | 默认全部；全部／简历／项目／目标JD附数量，名称与手工正文搜索，按更新时间倒序；带有效kind参数时优先指定分类。 |
| Q8 视觉基调 | 延续白底、冷灰细线与蓝色主操作；卡片8px圆角、默认无阴影，标题16px、摘要14px、元信息12px，类型用线性图标与文字区分。 |

### 页面结构与响应式草图

沿用全站外壳与导航；资料库不另建侧栏或顶部品牌区。页头保留“我的资料库”和一句说明。工具栏包含分段分类与搜索框，搜索提示为“搜索名称或正文”，不显示未实现的快捷键徽标。分类数量统计全部资料，不随搜索改变；有搜索时另显示匹配数量。摘要仅供扫描，完整原文始终可回查。

```text
桌面概览（1440px视口，沿用200px全站侧栏）
┌────────┬─────────────────────────────────────────────┐
│全站导航│ 我的资料库                       [新增资料] │
│        │ 整理经历、项目与目标岗位                     │
│        │ [全部 简历 项目 目标JD]   [搜索名称或正文]  │
│        │ ┌────────────┐ ┌────────────┐ ┌───────────┐ │
│        │ │类型 · 时间 │ │类型 · 时间 │ │类型 · 时间│ │
│        │ │名称／摘要  │ │名称／摘要  │ │名称／摘要 │ │
│        │ │附件／仓库  │ │附件／仓库  │ │附件等信息 │ │
│        │ │查看 编辑 删除││查看 编辑 删除││查看 编辑 删除││
│        │ └────────────┘ └────────────┘ └───────────┘ │
│        │ 后续资料按相同网格排列；底部保留隐私说明     │
└────────┴─────────────────────────────────────────────┘

桌面详情                       手机详情
┌───────────────┬────────────┐  ┌────────────────────┐
│概览保持原位置 │名称 [关闭] │  │[返回] 资料详情      │
│背景遮罩不可操作├────────────┤  ├────────────────────┤
│               │正文与附件  │  │名称／类型／更新时间│
│               │独立滚动    │  │正文、仓库、附件    │
│               │编辑／管理  │  │阅读区域独立滚动    │
│               ├────────────┤  ├────────────────────┤
│               │关闭／练习  │  │关闭／开始练习      │
└───────────────┴────────────┘  └────────────────────┘
```

网格以可用内容宽度决定列数，最多3列：每列最小280px、间距16px；不足两列时切为一列，最后一行不拉伸卡片填满。1440px通常3列，1024px通常2列，768px在现有顶部导航外壳下为2列，375px为1列；这些是布局目标，实施时验证侧栏展开／收起及820px附近的外壳切换。页内边距桌面沿用28px、手机16px，不修改全站断点。卡片同排等高，内容区约260px起，不强制裁掉操作或长名称。

抽屉桌面宽度为`min(760px, 视口宽度 - 48px)`，768px及以下全屏。抽屉标题区与底部操作区固定在容器内，中间单独滚动，正文最大72ch；底部适配安全区。打开时锁定背景交互与滚动，关闭后恢复；不在背景页面重复展示全文。采用dialog语义、焦点约束、Esc关闭和明确关闭按钮，上传／保存进行中沿用现有关闭保护。

### 卡片内容与数据来源

沿用[Material／Document契约](frontend/src/types.ts)和[仓库契约](frontend/src/source-code.ts)，不推导不存在的业务指标。

| 卡片内容 | 来源与展示规则 |
|---|---|
| 类型、名称、更新时间 | 使用kind、name、updatedAt；图标表示资料类型，不把整份资料一律标成PDF。名称最多显示两行，详情头部可查看完整名称。 |
| 摘要 | 优先使用手工正文content，空白时取首个有文本附件的text；只压缩空白并截取原文，不总结、不提炼标签。注明“正文摘录”或“附件摘录”，最多3行；无文本显示“暂无正文，可打开资料补充”。 |
| 附件 | documents.length；附件原文件名及文本在详情完整可查。截断附件必须显示真实截断提示，不用“摘要”掩盖已丢失的后续文本。 |
| 仓库 | 仅项目显示；有值时展示repository及真实status，无关联时显示“未关联仓库”。缺失或未知状态用中性说明，不伪造成功。完整错误与警告在详情读取原组件。 |
| 操作 | 名称／查看打开详情，编辑与删除是独立按钮，不把整张article变成嵌套交互控件。删除沿用确认及失败反馈；练习入口位于详情底部，JD不显示练习按钮。 |

搜索对name和content做去首尾空白、不区分大小写的包含匹配，不搜索附件全文、仓库名或隐藏标签；分类与搜索同时生效。排序按updatedAt倒序，同时间以稳定ID排序；清空搜索不重置分类。关闭详情保留列表上下文；新建或编辑成功后定位已保存资料，若被原筛选隐藏，切到该类型并清空搜索，明确展示保存结果。

不使用“底牌已被系统掌握”“本地隐私加密”“永不上传云端”等无依据文案。页尾保留真实告知：“资料与附件保存在本机。开始练习时，相关资料会发送给已配置的模型服务。”

### 录入、详情与状态

“新增资料”展开三项类型选择。选择后沿用已有录入表单与校验；新增／编辑可以复用当前Modal，详情本身只保留一个抽屉。从详情进入编辑时先关闭详情，避免叠两层浮层；取消编辑回到该资料详情，保存成功显示更新后的详情。简历保留上传／手动填写，项目保留正文与可选公开GitHub仓库，JD保留文本录入。

拖拽区是可点击、可键盘触达的文件选择入口，不自动写入。新增简历只接收一份文件；多文件明确提示选择一份，不静默丢弃。附件区允许多选，保留现有20个上限。简历支持PDF／DOCX／Markdown，项目附件另支持TXT；单文件5MiB，空文件、不可解析文件和扫描PDF沿用现有拒绝及说明，不承诺OCR。待上传列表显示文件名、格式与大小，确认按钮与取消／重新选择相邻；没有真实进度数据时仅显示“正在上传”，不画虚假百分比。

| 状态 | 布局与反馈 |
|---|---|
| 首次读取 | 保留页头，显示现有加载提示；禁用依赖数据的写操作，不用假卡片或零计数冒充读取结果。 |
| 无资料／分类为空 | 简短说明及新增对应类型入口，不展示大面积空白网格；首次空库优先引导上传简历。 |
| 搜索无匹配 | 显示查询结果为空与清空搜索入口，保留分类和新增能力。 |
| 读取失败 | 显示具体错误与重新加载，不能表现成空库。 |
| 上传／保存中 | 禁用重复操作与关闭，保留选择及输入；拖入无效文件不提交。 |
| 上传部分成功 | 已保存文件从待上传队列移除，剩余文件保留；重试只发送剩余项，不重复上传成功项。 |
| 保存／删除失败 | 错误与相关操作相邻，表单输入、原记录及附件保持可恢复。 |
| 仓库异常／附件截断 | 展示真实状态、警告和原文边界；不隐藏，也不改写为成功。 |

### Design Tokens 与局部规格

以下值是当前资料库页面的映射，不新增第二套全局主题。优先引用本文件frontmatter与现有CSS语义变量；只有网格、抽屉及局部字号需要页面级变量。全站13px正文基准不改，资料卡摘要及阅读正文局部使用14px。

| 用途 | 令牌／目标值 | 使用约束 |
|---|---|---|
| 页面／卡片／辅助背景 | surface-canvas／surface／surface-soft：#fff／#fff／#f8fafc | 卡片不铺类型色，不叠加渐变。 |
| 文字 | text-primary #0f172a；text-secondary #334155；text-muted #64748b | 主要名称、摘要、元信息三级。 |
| 细边界／控件边界 | border-subtle #e2e8f0；border-control #94a3b8 | 卡片1px细线；输入保证边界可辨。 |
| 主操作／选中 | accent #2563eb；accent-hover #1d4ed8；accent-soft #eff6ff | 页头新增为主按钮；练习为次要按钮。 |
| 反馈 | success #047857；warning #92400e；danger #be123c及对应soft | 仅表达真实状态，同时显示文字。 |
| 字体 | 原系统中文无衬线；路径与数据用现有font-mono | 无外部字体请求，无装饰展示字体。 |
| 页面标题／卡片标题 | headline 24px／650／1.4；title 16px／600／1.5 | 手机页面标题22px；卡片标题不缩小。 |
| 摘要／正文／元信息 | reading 14px／1.75；metadata 12px／1.75 | 不采用11px资料信息；表单手机输入16px。 |
| 间距 | 原4px阶梯；卡片20px内边距，网格16px间距，区块24px | 手机卡片16px内边距；抽屉桌面24px、手机16px。 |
| 圆角 | panel 8px；control 6px | 卡片8px，输入和按钮6px；抽屉全屏直边。 |
| 卡片宽度与高度 | 最小280px，最多3列，最小约260px高 | 自然增高，同排等高；一份资料不拉伸为Hero。 |
| 抽屉／阅读行宽 | 最大760px；正文最大72ch | 768px及以下全屏。 |
| 阴影与遮罩 | 卡片none；抽屉沿用浮层阴影与遮罩 | 普通卡片悬停只改边框／浅底，不浮起。 |
| 控件／焦点 | 桌面主控件36px，手机触控44px；2px蓝色焦点环 | 图标有名称，焦点不被裁切，hover不是唯一入口。 |
| 动效 | 沿用180ms与现有缓动；减少动态偏好时禁用非必要过渡 | 只响应悬停、选中、抽屉开关及拖入状态。 |

### 实施边界与验收依据

沿用Vue、现有Modal／Notice／LoadingState／SourceCodeEvidence及上传API，无新后端、数据库字段、模型调用或第三方UI依赖。接口和原有业务能力保持；默认分类由简历改为全部、点击才展示详情是本次明确批准的可见行为变化，不能以旧测试默认预览断言拒绝新方案。

保留`data-kind`既有值、`data-material-preview`、表单ID（material-name、resume-file、material-content、material-github、document-files）及dialog／分类分组语义；新增“全部”值单独定义，不改既有三类值。资料内容预览的可访问名称迁入打开后的详情。保留编辑、删除、上传等原操作名称；入口“新增资料”属于已确认变更，三项类型选择继续使用“新增简历／新增项目／新增目标 JD”。禁止放宽未知URL替身或删掉原业务测试来适配新UI。

后续实施时先补失败行为测试，再修改页面：覆盖全部／指定kind、搜索边界与排序、点击打开与关闭恢复、键盘焦点、拖入待确认且无自动写入、部分失败重试、完整正文／截断警告、编辑不改附件、两种练习路由及JD无练习入口。验证375／768／1024／1440px与820px外壳切换，检查长中文名称、仓库路径、20附件、空／错／加载状态与整页溢出。继续执行相关Materials单测、前端类型／构建及资料到练习的浏览器链路；只按实际运行记录结果。

最初设计阶段只核对文档与静态草图。后续实施已完成：现有Modal增加可选drawer布局与忙碌关闭保护，FileDropzone复用文件选择和拖入入口；资料页行为测试、类型／构建及隔离浏览器核对通过，日常后端重启与原数据核对通过。证据与适用范围见[V5验证记录](docs/vibe/releases/v5/VERIFICATION.md#资料库界面实施2026-10-22)。设计决策无未决项；后续必须改变已确认行为时，回到对应问题讨论，普通实现细节由Agent决定。
