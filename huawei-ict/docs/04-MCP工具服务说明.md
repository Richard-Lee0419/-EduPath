# 04 · MCP 工具服务说明

> 作用：把 EduPath 已有的课程、画像、生成、评估能力，变成 AgentArts 可以**自动发现、按需调用**的工具。
> 代码：`bridge/`　｜　运行：`python -m bridge.mcp_server` 或 `python -m bridge.gateway`
> 依赖：**Python 标准库**，无需 pip 安装。

---

## 1. 为什么用 MCP

| 方式 | 问题 |
| --- | --- |
| 把逻辑写进工作流提示词 | 不可复用、不可测试、模型容易编 |
| 每个动作写一个 OpenAPI 插件 | 平台间不通用，描述与鉴权重复 |
| **MCP** | 工具自描述（JSON Schema）、跨语言、平台可发现、可单独测试 |

对比赛的意义：**“工具编排”是评分点**。MCP 让评委看到“Agent 真的调了工具”，而不是“模型写了一段像工具调用的话”。

---

## 2. 架构

```text
AgentArts 工作流 / 智能体
        │ MCP (JSON-RPC 2.0)
        ▼
bridge/mcp_server.py  ── tools/list, tools/call
        │
        ├─ demo_fixture        离线彩排（默认）
        └─ edupath_ai_service  转发到原项目 FastAPI
                │
                ├─ /kb/search
                ├─ /profile/extract
                ├─ /resource/generate
                ├─ /path/generate
                ├─ /tutor/chat
                ├─ /quiz/generate
                └─ /evaluation/analyze
```

### 2.1 三种传输

| 传输 | 地址/命令 | 用途 |
| --- | --- | --- |
| stdio | `python -m bridge.mcp_server --stdio` | 本地调试、Claude Desktop 类客户端 |
| Streamable HTTP（JSON-RPC POST） | `POST http://127.0.0.1:8765/api/mcp` | 平台/网关直接调用 |
| HTTP + SSE（旧版） | `GET http://127.0.0.1:8765/api/mcp/sse` | 兼容只支持 SSE 的客户端 |

> 不同 AgentArts 版本对 MCP 传输的要求可能不同。若平台只接受官方 SDK 的 SSE 实现，可在本服务前加一层 `mcp-proxy`，或按平台文档调整；核心 `initialize/tools/list/tools/call` 已实现。

---

## 3. 工具目录（10 个）

| # | 工具名 | 作用 | 关键入参 | 返回 | 读/写 |
| --- | --- | --- | --- | --- | --- |
| 1 | `edupath_get_profile` | 读取/增量提取画像 | `student_id`, `message` | profile/confidence/dimensions | 读 |
| 2 | `edupath_search_knowledge` | 课程证据检索 | `query`, `course_id`, `top_k` | 证据列表（chunk/来源/分数） | 读 |
| 3 | `edupath_plan_resource_bundle` | 资源组合规划 | `goal`, `weak_points`, `resource_types` | 类型/难度/时长/验收 | 读 |
| 4 | `edupath_generate_learning_resource` | 生成讲义/导图/题库/代码实验 | `knowledge_point`, `resource_types`, `student_profile` | 资源 + 证据 + 质量 + 安全 | 写（生成） |
| 5 | `edupath_plan_learning_path` | 按天规划路径 | `target`, `days`, `daily_minutes` | 每日任务/证据/调整策略 | 写（生成） |
| 6 | `edupath_answer_with_evidence` | 课程辅导问答 | `question`, `answer_mode` | 回答/步骤/引用/置信度 | 读 |
| 7 | `edupath_generate_quiz` | 生成 1–3 道单选题 | `knowledge_point`, `question_count` | 题目/解析/证据 | 写（生成） |
| 8 | `edupath_analyze_quiz_result` | 测验分析 | `score`, `question_results` | 掌握度/错因/下一步 | 读 |
| 9 | `edupath_review_safety` | 安全审核 | `text` | 通过/风险/问题/建议 | 读 |
| 10 | `edupath_get_course_outline` | 课程与知识点大纲 | `course_id` | 课程/知识点列表 | 读 |

完整 JSON Schema 运行：

```bash
python -m bridge.mcp_server --list-tools
```

### 3.1 工具设计约定

- **单一职责**：一个工具只做一件事；不要把“画像+检索+生成”塞进一个工具。
- **结构化返回**：`structuredContent` 是 JSON，`content[0].text` 是同样的 JSON 字符串（兼容只读 text 的客户端）。
- **来源标注**：所有生成类返回都带 `mode`（`demo_fixture` / `edupath_ai_service` / `live_fallback`），不把演示数据说成模型输出。
- **幂等**：生成类工具用 `task_id`/`resource_id` 关联；重复调用不应产生重复资源（平台侧落库时校验）。
- **超时**：检索 15s、生成 120s、路径 90s；超时返回明确错误，不静默返回空。

---

## 4. 本地运行

```bash
cd huawei-ict
python -m bridge.mcp_server --list-tools          # 查看 10 个工具
python -m bridge.mcp_server --stdio               # stdio 模式
python -m bridge.gateway --port 8765              # HTTP + Demo
```

HTTP 自测：

```bash
curl -s http://127.0.0.1:8765/api/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

调用检索：

```bash
curl -s http://127.0.0.1:8765/api/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0","id":2,"method":"tools/call",
    "params":{
      "name":"edupath_search_knowledge",
      "arguments":{"query":"二叉树 中序 递归栈","course_id":1,"top_k":4}
    }
  }'
```

期望：`result.structuredContent.results` 至少 1 条，`chunk_id` 为 `E1` 等，`mode` 为 `demo_fixture`。

### 4.1 接原项目 AI 服务

```bash
# Windows PowerShell
$env:EDUPATH_AI_BASE_URL="http://127.0.0.1:8000"
$env:EDUPATH_BRIDGE_MODE="edupath_ai"
python -m bridge.gateway --port 8765
```

此时工具的 `mode` 变为 `edupath_ai_service`；如果某个端点失败，会回退到 fixture 并带 `fallback_reason`，UI 会提示。

> 原项目的 AI 服务目录可能是 `ai-agent-service/`（新版）或 `ai-service/`（旧版），以你本地实际为准；只需保证 `EDUPATH_AI_BASE_URL` 指向它。

---

## 5. 在 AgentArts 注册

### 5.1 前置

- MCP 服务已部署到平台可访问的地址（本机 `127.0.0.1` 平台访问不到；需公网/内网可达，或用内网穿透）。
- 生产环境加 HTTPS 与鉴权头；Demo 可先用临时域名。

### 5.2 步骤（以控制台实际为准）

1. AgentArts → 工具/插件 → 自定义 MCP → 新建。
2. 名称：`edupath-tools`。
3. 传输：SSE 或 Streamable HTTP。
   - SSE：`https://<your-host>/api/mcp/sse`
   - HTTP：`https://<your-host>/api/mcp`
4. 鉴权：Bearer Token（生产）；Demo 可关闭。
5. 超时：60s（生成类可单独放宽）。
6. 保存后点“发现工具”，应看到 10 个 `edupath_*`。
7. 试调用 `edupath_get_course_outline`，确认返回课程列表。
8. 在工作流节点里选择 MCP 工具，绑定参数（如 `query = {{start.message}}`）。

### 5.3 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 发现 0 个工具 | 传输/路径不对，或平台访问不到 | 先用公网 curl 测 `/api/mcp`；确认没有路径重写 |
| 调用超时 | 生成类超过平台默认超时 | 调大节点超时；或拆成异步任务 + 轮询 |
| 中文乱码 | 未按 UTF-8 返回 | 本服务已设置 `charset=utf-8`；检查网关 |
| 工具参数校验失败 | Schema 与调用不一致 | 用 `--list-tools` 对照字段名/类型 |
| SSE 断连 | 代理缓冲/空闲超时 | Nginx 关闭缓冲、加心跳；本服务已每 15s ping |

---

## 6. 协议示例

### 6.1 initialize

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"agentarts","version":"1.0"}}}
```

响应：

```json
{
  "jsonrpc":"2.0","id":1,
  "result":{
    "protocolVersion":"2024-11-05",
    "capabilities":{"tools":{"listChanged":false}},
    "serverInfo":{"name":"edupath-agentarts-bridge","version":"1.0.0"}
  }
}
```

### 6.2 tools/call

```json
{
  "jsonrpc":"2.0","id":3,"method":"tools/call",
  "params":{
    "name":"edupath_generate_learning_resource",
    "arguments":{
      "course_id":1,
      "knowledge_point":"二叉树遍历",
      "resource_types":["lecture","mindmap","quiz"],
      "difficulty":"basic"
    }
  }
}
```

响应结构：

```json
{
  "jsonrpc":"2.0","id":3,
  "result":{
    "content":[{"type":"text","text":"{...}"}],
    "structuredContent":{
      "mode":"demo_fixture",
      "resources":[
        {"resource_type":"lecture","title":"...","content":"...","evidence_chunk_ids":["E1"]}
      ],
      "evidence":[{"chunk_id":"E1","score":0.94}],
      "quality":{"total_score":88,"gate_passed":true},
      "safety":{"passed":true,"risk_level":"low"}
    },
    "isError":false
  }
}
```

---

## 7. 错误语义

| 场景 | JSON-RPC error | 前端表现 |
| --- | --- | --- |
| 未知工具 | `-32602` | “工具不存在” |
| 参数不合法 | `-32602` | “参数校验失败” |
| 工具内部异常 | `-32603` | “工具执行失败，已记录” |
| 方法不存在 | `-32601` | “协议方法不支持” |
| 解析失败 | `-32700` | “请求不是合法 JSON” |

> 生成类失败不要返回空数组冒充成功；`isError` 或 `error` 必须明确。

---

## 8. 安全

| 项 | 做法 |
| --- | --- |
| 鉴权 | 生产加 `Authorization: Bearer <token>`；网关校验 |
| 限流 | 按 IP/Token 限流；生成类更严格 |
| 输入 | 长度限制（message ≤ 4,000 字）；文件上传单独处理 |
| 输出 | 安全审核；不返回密钥/内部路径 |
| 隐私 | 只用脱敏 student_id；不记录完整对话到日志 |
| 依赖 | 无第三方依赖，减少供应链风险 |
| 网络 | 只暴露 `/api/mcp` 与 Demo；管理端点不对外 |

---

## 9. 自测

```bash
python scripts/selfcheck.py
```

覆盖：

- 10 个工具均能列出、Schema 唯一。
- 检索返回证据且带来源/许可证。
- 生成返回 3 类资源、质量 88、安全通过。
- 风险文本被拦截（`代写`/`代考`）。
- MCP `initialize` / `tools/list` / `tools/call` 正常。
- 本地编排 9 步、任务成功。

输出：`evidence/selfcheck.json`。

---

## 10. 与原项目的关系

| 原项目能力 | 本服务如何用 |
| --- | --- |
| `POST /kb/search` | `edupath_search_knowledge` 直接转发 |
| `POST /profile/extract` | `edupath_get_profile` 转发 |
| `POST /resource/generate` | `edupath_generate_learning_resource` 转发（120s 超时） |
| `POST /path/generate` | `edupath_plan_learning_path` 转发 |
| `POST /tutor/chat` | `edupath_answer_with_evidence` 转发 |
| `POST /quiz/generate` | `edupath_generate_quiz` 转发 |
| `POST /evaluation/analyze` | `edupath_analyze_quiz_result` 转发 |
| SafetyAgent | `edupath_review_safety` 本地规则 + 可扩展转发 |

> 原项目不需要改一行代码；只要服务能访问到，工具就能用。
