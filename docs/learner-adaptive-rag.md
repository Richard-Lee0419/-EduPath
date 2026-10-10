# 当前检索与个性化边界

本文件按公开版本的实际调用链说明检索能力。早期文档中的“混合检索、RRF、学情重排序、自纠错检索已贯通”表述超过了当前实现范围，不作为本项目现有成果。

## 已接入的检索链路

```text
KnowledgeAgent.retrieve
  → Retriever.search(course_id, query, top_k, knowledge_point_ids)
  → VectorStore.search 或 ChromaVectorStore.search
  → RagEvidence → 生成/辅导/路径等流水线
```

实际源码位于 `ai-agent-service/app/agents/knowledge_agent.py` 与 `app/rag/`。

| 环节 | 当前实现 | 局限 |
| --- | --- | --- |
| 文档切分 | `chunker.py`、`semantic_chunker.py` 使用概念、教学角色和章节信息划分文本，并保留分块 ID 与元数据 | 启发式边界不是经过人工全面标注验证的最佳切分 |
| local 检索 | 向量相似度及标题、主题、知识点等启发式加分 | 规则分数与 Chroma 分数语义不同；默认哈希 embedding 适合演示与回归 |
| Chroma 检索 | 课程过滤、embedding 召回，再按知识点过滤和加权 | 先取有限候选再筛知识点可能遗漏结果；质量依赖语料和 embedding |
| 证据 | 返回 `chunk_id`、标题、内容、来源与 score；知识库结果还可携带作者、链接、许可状态、章节路径 | 分数不是结论正确率，也不是学习效果 |

语料来源与许可见 [开放知识库说明](open-source-knowledge-base.md)。分块元数据不代表所有字段都贯通每一种前端资源接口，应以 API 契约和对应 schema 为准。

## 尚未接入的独立实现

- `app/rag/lexical_retrieval.py` 提供 `bm25_scores`；当前 `Retriever.search` 没有调用它。
- `app/rag/context_selector.py` 提供 `AdaptiveMmrSelector`；当前应用检索入口没有调用它。
- 当前调用链没有 RRF 融合、专门的画像重排序或 RetrievalCritic 查询重写循环。

这些文件是可进一步集成和验证的原型，不能据此宣称线上正在使用混合检索、MMR 去冗或自纠错检索。本次收尾不为增加技术名词而接入它们。

## 个性化实际发生在哪里

Java 读取用户画像、作答和路径状态，传递给相应 Python 流水线。资源规划与生成、路径安排、练习评估使用目标、薄弱点、偏好、难度或时间预算；`services/personalization.py` 整理相关上下文。

当前检索函数本身接收课程、查询和知识点 ID，而非完整学习画像。画像影响请求与下游生成，不等于实现了画像感知的候选重排序。测验后的画像/路径版本变化可验证机制响应，不能证明学习收益。

## 如何验证与如何解释结果

1. 先记录实际语料、embedding、向量提供方和版本，再执行现有语料构建与检索评测工具。
2. 核对 golden query 的正确证据是否在 Top-K 内，并人工检查它是否直接支持要生成的结论。
3. 若比较 local 与真实 embedding，使用相同语料和查询集；不要把一次模式的分数作为另一种模式的提升基线。
4. 如要研究 BM25/MMR，需要先接入调用链，再做固定查询集对照及失败分析；本仓库当前没有该实验结论。

数据工作区说明见 [课程语料工作区](../ai-agent-service/data/course_corpus/README.md)。缺失原始资料的历史报告、规则评分或自动化测试通过，都不能作为正式用户实验或教学效果证据。
