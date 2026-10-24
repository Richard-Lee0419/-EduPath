# 华为 ICT 大赛版（赛题 2）

本分支 `huawei-ict-2026` 为第十一届华为 ICT 大赛中国创新赛 · 赛题 2 的独立版本：

- **不修改 `main` 的任何代码**；比赛版本全部在 [`huawei-ict/`](huawei-ict/) 子目录。
- 内容：AgentArts 智能体/工作流设计、平台知识库配置、10 个 MCP 工具、零构建可视化 Demo、作品信息收集表、12 页 PPT、演示脚本与答辩要点。
- 快速开始：

  ```bash
  cd huawei-ict
  python -m bridge.gateway --port 8765
  # http://127.0.0.1:8765/
  ```

- 先读：[`huawei-ict/README.md`](huawei-ict/README.md) → [`docs/00-赛题解读与提交清单.md`](huawei-ict/docs/00-赛题解读与提交清单.md) → [`docs/01-现状差距与改造评估.md`](huawei-ict/docs/01-现状差距与改造评估.md)。

> 当前 Demo 默认是离线 `fixture` 彩排模式，页面明确标注“本地演示 · 未上云”。正式提交前需在 AgentArts 控制台完成知识库/工作流/MCP/发布，并配置 `AGENTARTS_ENDPOINT`。
