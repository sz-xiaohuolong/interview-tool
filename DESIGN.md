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
# 界面设计规范

适用本次 V1 全站表现层重构。产品定位与行为沿用 [PROJECT.md](docs/vibe/PROJECT.md) 和 [SPEC.md](docs/vibe/releases/v1/SPEC.md)，本文件仅维护视觉规则。

## 设计方向

参考用户提供的浅色面试工具界面，采用冷色中性底、蓝色操作重点、浅色侧栏和紧凑工作区。保持现有品牌、文案、页面入口与原生控件；不增加参考图中的账户、会员、搜索、趋势图或虚构评分。

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

字号层级：辅助 12px、元信息 13px、控件 14px、正文与阅读正文 15px、三级标题 17px、二级标题 20px、工具页标题 28px、首页标题 34px。一级标题字距 `-0.025em`，二级标题 `-0.02em`；基础行高 1.7，段落 1.8，长文 1.85。计数、评分、进度统一 `tabular-nums`。

间距采用 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40px。圆角：徽章 5px，按钮与输入框 8px，独立表面 12px，弹窗 16px。布局仅用细边框、表面色阶和留白组织；仅弹窗使用带冷色偏向的柔和阴影。

## 表面模式与页面布局

| 页面 | 模式 | 视觉重心 |
|---|---|---|
| 练习概览 `/` | Persuade，受工具属性约束 | 左对齐引导；真实统计；三个横向、非等宽练习入口，快速面试为主操作；下方最近练习与资料 |
| 配置 `/configure/:mode` | Operate | 主表单与说明侧栏；保留原生选择框和表单顺序；提交动作清楚可见 |
| 简历押题结果 | Read | 题号、题目、依据与折叠思路分层；正文限制在 65～75ch；原生 details/summary |
| 面试 `/sessions/:id` | Operate | 左侧低干扰对话区与右侧紧凑信息栏；输入、发送、结束与重试状态清楚；原滚动容器保留 |
| 复盘 `/reports/:id` | Read | 摘要、四维评分、逐题分析、原始问答分层；长文自然换行，不裁切答案 |
| 资料库 `/materials` | Operate / 详情 Read | 清晰分类、资料清单、独立详情弹窗；长文件名与附件文本可读 |
| 历史 `/history` | Read | 行式列表；状态、时间、继续面试与复盘入口易扫读 |

## 公共组件与响应式

- Button、Input、Select、Badge 沿用现有原生元素和类名，不新增包装逻辑。Reka UI 弹窗继续负责焦点、Esc 和可访问性。
- 只通过公共样式更新 LoadingState 的视觉呈现；保留 `role="status"`、标签和所有调用条件。空态保留实际下一步入口，错误态保留原恢复动作。
- 桌面侧栏 216px，内容最大宽度 1320px；1180px 以下收紧侧栏和内容边距；900px 以下配置说明转下方；820px 以下导航转为顶部可横向滚动行；600px 以下主要模块单列；统计保留三列，评分和会话元信息保留两列。
- 对话容器保持原 `ref="chat"` 与滚动职责。移动端仍可读取元信息和操作输入，不通过 CSS 隐藏原功能。
- 390px 和窄至 320px 的布局不得出现整页横向滚动；长词、路径与模型正文自然换行；列表摘要可省略，历史岗位、详情标题、正文及附件名完整换行。输入框在移动端为 16px，降低聚焦缩放风险。
- 全站沿用用户参考图的浅色方案，不引入主题切换或新的存储键。

## 状态与动效

交互反馈时长 180ms，缓动 `cubic-bezier(0.22, 1, 0.36, 1)`。仅按钮、链接、输入控件、选中项与弹窗状态使用过渡；不做页面入场编排或装饰循环。请求中的既有旋转指示器仅表达真实忙碌状态；加载骨架静态呈现。遵循 `prefers-reduced-motion`，保留清晰的键盘焦点。状态同时通过文字表达。

正文、辅助文字、占位符与按钮文字对比度最低 4.5:1；大字最低 3:1。彩色提示面使用对应色系的深色文字。控件边框在白底与浅底的对比度均不低于 3:1。禁用按钮以浅底和边框区分，保持状态文字可读；键盘焦点为 2px 蓝色轮廓。

## 改动边界与验收

锁定 `backend/**`、前端 API / router / types / format / main / App.vue、环境、依赖、配置、测试和脚本。所有 Vue `<script>` 原字节保留，所有条件、循环、事件、路由、Props、校验与无障碍绑定保留。

工作树基线为 `ca17b612dc462ef3a312a95a1184fb9d65807af7`。逐模块核对模板绑定；工程检查、最终前端构建与既有端到端测试通过；在显式 Mock 独立数据环境中完成桌面、移动端及边界状态验收。一次完整视觉审查后集中修复，并完成修复复核；无效截图仅重截，不作为通过证据。实际结果由现有验证与进度文档维护。

## 参考图与交付范围

用户已指定原截图为本次参考，`.impeccable/config.json` 使用 `buildPath: comp`。原图的视觉语言映射到现有六页；首页保留现有练习入口、真实统计、最近练习与资料，会话页承担左侧对话与右侧信息栏。原图中超出现有功能的内容不实现，因此不以跨产品整图像素差异宣称复刻通过。

界面文案全部保留，包括原有引导句与模式编号；这些内容不因通用设计规则而删除。机器可读基础令牌位于本文件 frontmatter，组件可视化样例与动效等扩展位于 `.impeccable/design.json`，产品事实仍由既有文档维护。

## 文件边界清单

| 分类 | 路径与约束 |
|---|---|
| 锁定的后台与业务文件 | `backend/**`；`frontend/src/api.ts`、`router.ts`、`types.ts`、`format.ts`、`main.ts`、`App.vue`；所有 Vue 的 `<script>` / `<script setup>` |
| 锁定的工程与配置 | 环境文件、依赖和锁文件、构建配置、`scripts/**`、现有单测及 `frontend/tests/**` |
| 已重构的 UI 文件 | `frontend/src/style.css`；`frontend/src/pages/` 中的 `Dashboard.vue`、`Configure.vue`、`Session.vue`、`Report.vue`、`Materials.vue`、`History.vue` |
| 公共组件升级方式 | Button、Input、Select、Badge、Modal、LoadingState、导航和 Layout Shell 沿用现有元素与 Props，仅由共享 CSS 更新外观 |
| 设计与验收文档 | 本文件、`PRODUCT.md`、`.impeccable/design.json`、唯一 `DOCUMENT_MAP.md`、当前进度与 V1 验证记录 |

桌面会话为输入区预留高度：对话记录高度 `clamp(200px, calc(100dvh - 580px), 560px)`，窄屏恢复独立阅读区。所有原事件、表单与无障碍属性保留。
