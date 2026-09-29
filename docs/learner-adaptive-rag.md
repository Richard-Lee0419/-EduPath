# EduPath 学情感知自纠错 RAG

## 核心创新

EduPath 的检索链路不再只使用向量 Top-K，而是实现五个协同能力：

1. **结构感知语义切分**：先识别标题层级、段落、列表、代码围栏、Markdown 表格和公式块，再结合概念锚点与教学角色决定切分边界。代码、表格和公式作为受保护原子，即使超过普通字符窗口也不会被句子规则截断；每个 chunk 保存 `heading_path`、`structure_types`、章节深度、语义签名和前后邻接关系。
2. **混合检索**：稠密向量检索与 BM25 词法检索并行召回，通过 Reciprocal Rank Fusion 合并排序。
3. **学情感知重排序**：使用学生薄弱点、学习目标、典型错因和有效难度对候选证据进行教学价值加权。
4. **自适应 MMR 上下文**：`AdaptiveMmrSelector` 识别 focused、procedural、comparative、exploratory 查询意图，并结合候选冗余度、知识点过滤、薄弱点数量和难度动态计算 MMR λ。聚焦问题提高相关性权重；对比与探索问题提高多样性权重，并为不同结构和不同章节证据增加互补奖励。
5. **自纠错检索**：`RetrievalCriticAgent` 根据首条证据分数和查询覆盖率判断 `sufficient`、`partial` 或 `insufficient`；证据不足时自动加入画像与教学意图改写查询并重试，仍不足则导师明确拒绝无依据回答。

## 结构感知切分

切分不再按固定字符窗口直接截断，而是经过两级决策：

1. 结构解析器生成 `heading`、`paragraph`、`list`、`code`、`table`、`math` 原子；
2. 语义边界器综合标题变化、概念锚点 Jaccard、局部词项连贯度和教学角色迁移进行合并。

这保证“算法说明 → 参考代码 → 复杂度分析”在内容允许时保持连续，同时不同章节在标题边界上稳定切开。索引文本会加入标题路径、概念锚点、教学角色和结构类型，使 Dense 与 BM25 都能利用文档结构。

## Adaptive MMR 上下文选择

MMR 基础目标为：

```text
MMR(d) = λ × relevance(d) - (1 - λ) × max similarity(d, selected) + structure_bonus(d)
```

- `λ` 动态范围为 0.50–0.86；
- `structure_bonus` 奖励尚未覆盖的代码、表格、公式或章节路径；
- 上下文预算根据查询意图、难度和学习节奏在约 1800–4800 字之间自适应；
- 每条证据返回 `mmr_score` 和 `context_role`，角色包括主相关证据、结构互补证据、多样性扩展证据和支撑证据。

## 可解释输出

每条证据返回：

- `dense_score`：向量相关度；
- `lexical_score`：BM25 相关度；
- `fusion_score`：RRF 融合分；
- `personalization_score`：学情增益分；
- `mmr_score`：自适应 MMR 选择分；
- `retrieval_reasons`：匹配薄弱点、学习目标、错因补救或难度的解释。
- `context_role`：该证据在最终上下文中的作用；
- `structure_types` / `heading_path`：结构类型和章节路径。

每次检索返回 `retrieval` / `diagnostics`：

```json
{
  "status": "sufficient",
  "original_query": "二叉树递归遍历",
  "effective_query": "二叉树递归遍历",
  "attempts": 1,
  "strategies": ["structure_aware_chunking", "dense", "bm25", "rrf", "learner_rerank", "adaptive_mmr"],
  "top_score": 0.93,
  "query_coverage": 0.81,
  "evidence_count": 3,
  "chunking_strategy": "structure_aware_semantic_v2",
  "query_intent": "procedural",
  "mmr_lambda": 0.70,
  "candidate_count": 12,
  "redundancy_before": 0.58,
  "diversity_after": 0.79,
  "context_budget_chars": 3040,
  "selected_context_chars": 2486,
  "message": "检索证据充分，可进行基于证据的生成。"
}
```

这些字段已贯通资源生成、资源持久化与详情、知识库搜索、智能导师、Java 长任务进度和 Vue 页面。登录用户的画像由 Java 后端读取并注入，前端不直接提交身份画像，从而保证个性化排序使用可信用户上下文。

## 比赛演示建议

- 用精确术语问题展示 BM25 与向量召回的互补结果。
- 上传同时包含章节标题、代码、表格和公式的课程资料，展示结构单元完整保留及章节路径。
- 对同一主题分别输入聚焦问题和对比问题，展示 MMR λ、上下文预算和证据多样性变化。
- 使用两个薄弱点不同的画像查询同一主题，展示证据顺序和 `retrieval_reasons` 的差异。
- 输入知识库外问题，展示查询自动改写两次后由 `RetrievalCriticAgent` 阻止无证据回答。

## 评测指标

- 检索：Recall@K、MRR、nDCG@K、候选冗余度、上下文多样性；
- 切分：结构完整率、标题路径准确率、跨章节污染率；
- 证据：查询覆盖率、证据支持率、拒答准确率；
- 教学：使用证据后的测验正确率与掌握度增量；
- 工程：检索延迟、重试率和单次检索成本。
