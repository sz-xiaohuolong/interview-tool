# 项目定位

- 项目名称：面试练习室 · AI 技术面试助手。
- 当前 Release：V1。
- Quality Profile：Standard。
- 产品定位：面向个人开发者的本地文本技术面试训练工具，围绕岗位、简历和项目进行动态追问与复盘。
- 目标用户：准备 Java 后端、大模型应用 / Agent 开发等岗位面试的个人用户。

## 范围与事实源

当前范围由 V1 [需求背景](releases/v1/PROJECT_BRIEF.md) 和 [产品行为规范](releases/v1/SPEC.md) 维护。新增产品范围需另行确认。

根目录 [DOCUMENT_MAP.md](../../DOCUMENT_MAP.md) 是唯一权威职责地图，本文件不另建地图。当前执行位置见 [PROGRESS.md](PROGRESS.md)，运行、配置和测试命令见 [README.md](../../README.md)。

## 运行与发布边界

应用仅在本机单人环境运行；真实模型调用使用用户指定服务，相关资料与对话会发送到该服务。密钥和个人资料只保存在本地忽略目录。

用户已授权在 GitHub 创建公开代码仓库并同步 `main`；仓库地址与授权依据见 [DECISIONS.md](DECISIONS.md)。应用公网部署、创建 PR、Tag 或 GitHub Release 不属于此次同步。
