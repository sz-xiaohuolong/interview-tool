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

本规范于2026-10-21依据当前源码更新，记录已实现的可复用视觉规则。YAML frontmatter 是基础令牌，`.impeccable/design.json` 仅扩展阴影、动效、断点和组件演示。规范依据为 [全局样式](frontend/src/style.css)、[报告样式](frontend/src/styles/report.css)、[训练样式](frontend/src/styles/training.css) 与相应 Vue 组件；产品事实由 [PRODUCT.md](PRODUCT.md) 指向现有文档，实施与验证记录见 [PROGRESS.md](docs/vibe/PROGRESS.md)。下列英文二级标题与命名规则标记为 DESIGN.md 格式所需的固定标识符。

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
