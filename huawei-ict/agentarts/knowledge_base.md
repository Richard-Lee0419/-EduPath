# 平台知识库配置卡

> 名称：`edupath-course-kb`　｜　区域：以账号可用的 AgentArts 区域为准（示例：cn-southwest-2 贵阳一）
> 用途：给 AgentArts 工作流/智能体提供课程证据与引用。
> 注意：平台知识库**不对外提供检索 API**；高代码 Agent 走 MCP 检索工具。

---

## 1. 基础配置

| 项 | 建议值 | 说明 |
| --- | --- | --- |
| 知识库名称 | `edupath-course-kb` | 与比赛材料一致 |
| 类型 | 平台知识库（默认创建） | — |
| 容量 | 1 GB | 108 篇文档足够 |
| 向量模型 | `pangu_embedding` | 中文课程语料 |
| 精排模型 | `pangu_rerank` | 提升首条命中 |
| 分段方式 | 自动分段 | — |
| 标题层级 | 3 | 保留章/节/小节 |
| 分段长度 | 约 500 字 | 与现有 RAG 对齐 |
| 重叠 | 50–80 字 | 减少跨段断裂 |
| 引用与归属 | 开启 | 回答带来源 |

---

## 2. 文档清单

| 课程 | 文档数 | 格式 | 来源 |
| --- | ---: | --- | --- |
| 数据结构与算法 | 60 | `.md` / `.jsonl` | cp-algorithms、OpenDSA |
| 计算机组成原理 | 48 | `.md` / `.jsonl` | RISC-V ISA Manual、lowRISC Ibex |

上传前确认每篇包含：标题、知识点、来源、许可证、commit、中文导读、学习目标、推演案例、自测。

---

## 3. 元数据模板

```json
{
  "course_id": 1,
  "course_code": "data_structures_algorithms",
  "knowledge_point_id": 9345,
  "knowledge_point": "二叉树遍历",
  "title": "前序、中序、后序与层序遍历",
  "source": "OpenDSA / BinaryTreeTraversal.rst",
  "source_url": "https://github.com/OpenDSA/OpenDSA/blob/f4e4afc.../RST/en/Binary/BinaryTreeTraversal.rst",
  "source_commit": "f4e4afcee2fcc0b47a888ebb5648c8ebb659c53c",
  "license": "MIT",
  "attribution": "Adapted from OpenDSA contributors",
  "difficulty": "basic",
  "curriculum_tier": "course_core",
  "search_aliases": ["二叉树遍历", "前序", "中序", "后序", "层序"]
}
```

---

## 4. 命中测试记录（上传后填写）

| # | 查询 | 期望 | 实际最高分 | 命中标题 | 判定 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | 二叉树前序、中序、后序有什么区别 | 强命中 | | | |
| 2 | 递归遍历为什么可能栈溢出 | 强命中 | | | |
| 3 | 二叉搜索树中序为什么有序 | 强命中 | | | |
| 4 | 红黑树删除的 5 种情况 | 弱/无 | | | 应拒答 |

> 截图保存到 `evidence/kb-hit-test.png`；第 4 条是负例，答辩要讲。

---

## 5. 检索参数

| 参数 | 值 |
| --- | --- |
| top_k | 5 |
| 阈值 | 0.35 |
| 课程过滤 | `course_id` |
| 引用 | 开启 |
| 重排 | 开启 |

---

## 6. 维护

- 新增文档 → 更新 manifest → 上传 → 命中测试 → 跑评测集 → 更新 `docs/03` 数字。
- 删除文档 → 确认引用不再出现 → 重新跑评测集。
- 每季度检查许可证与上游 commit 是否仍可访问。
