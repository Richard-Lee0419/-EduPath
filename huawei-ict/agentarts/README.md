# AgentArts 配置包说明

本目录是把 EduPath 复建到华为云 AgentArts 的“施工图”。它不是平台官方导入包，而是**可逐项照填的配置与提示词**。

## 目录

```text
agentarts/
├── README.md                 本文件
├── workflow.json             工作流声明（12 个执行节点；含分支共 25 个节点）
├── knowledge_base.md         平台知识库配置卡 + 命中测试记录
├── mcp_tools.json            MCP 工具清单（由 bridge 生成）
├── evaluation_set.jsonl      24 条评测样本
└── prompts/
    ├── orchestrator.md       主控：意图/路由/汇总
    ├── profile.md            画像：严格 JSON、不编造
    ├── retriever.md          检索：阈值/拒答/来源
    ├── planner.md            规划：资源组合/难度/时长
    ├── generator.md          生成：引用/结构/学术诚信
    ├── tutor.md              辅导：苏格拉底/分步/提示
    ├── evaluator.md          评估：五维/门禁
    └── safety.md             安全：四类风险/拦截
```

## 建议的搭建顺序（约 90 分钟）

1. **知识库**（20 分钟）：按 `knowledge_base.md` 创建、上传 2 门课、跑 4 条命中测试。
2. **单智能体试水**（10 分钟）：建 `edupath-kb-qa`，挂知识库，粘贴 `retriever.md` + `tutor.md` 的组合提示词，验证引用与拒答。
3. **MCP 工具**（15 分钟）：部署 `bridge`，在平台注册 `edupath-tools`，发现 10 个工具，试调用课程大纲。
4. **工作流**（30 分钟）：按 `workflow.json` 拖 12 个节点，先跑通“开始→意图→检索→LLM→结束”，再逐步加并行生成、门禁、发布。
5. **发布**（10 分钟）：提交版本，记录版本号；发布 API/Web；把端点写入 `AGENTARTS_ENDPOINT`。
6. **评测**（15 分钟）：导入 `evaluation_set.jsonl`，跑 24 条，导出结果。

## 平台字段与本文的对应

| 本文 | 平台（以实际为准） |
| --- | --- |
| 智能体 | 智能体管理 → 单智能体 / 多智能体 |
| 工作流 | 工作流 → 画布节点 |
| 知识库 | 知识库 → 创建/上传/命中测试 |
| MCP | 工具/插件 → 自定义 MCP |
| 发布 | 提交版本 → 发布 API/Web |
| 评测 | 评测 → 数据集/评测任务 |
| 模型 | MaaS 预置服务（DeepSeek/盘古） |

## 注意事项

- 平台每个版本的页签可能不同；找不到时用“智能体/工作流/知识库/工具/发布/评测”这几个关键词搜帮助中心。
- 不要在工作流里写死 API Key；用平台凭据/环境变量。
- 不要在提示词里放真实学生信息；用 `stu-demo-001`。
- 提交版本说明写清：知识库名、检索参数、模型、MCP 版本、评测集版本。
