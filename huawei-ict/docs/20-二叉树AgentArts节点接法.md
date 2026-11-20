# 二叉树诊断的 AgentArts 节点接法

建议在独立草稿中试验，沿用第二阶段的「开始 → 知识检索 → 原生代码 → 结束」。知识检索素材使用自编二叉树讲义，分段时保留每个 `EDUPATH-TREE-DFS-V1:<小节标识>` 和下一行段落。三个小节都应可被同一次检索召回。产品本地校验使用同一份代码和讲义。

- 开始节点输入 `problem_json`，类型为文本，承载下方 JSON。题目和示例作答已经足够。
- 知识检索用「二叉树前序中序后序、访问时机、左右顺序、遗漏重复」检索本文，输出列表接到代码节点的 `output_list`。在节点试运行里检查每个片段的 `document_name` 与 `content`。
- 使用二叉树诊断代码的 `main(args)`，返回字符串 `result_json`。结束节点输出该字段；解析后可用 `decision`、`mistake`、`expected` 与 `citation` 展示诊断。
- 草稿里至少复跑访问时机、先右后左、缺序列和错小节四条样例。记录真实召回片段和实际终态，控制台试运行与部署端点各保留对应状态。

```json
{
  "topic": "binary_tree",
  "order": "preorder",
  "nodes": [
    {"id": "A", "left": "B", "right": "C"},
    {"id": "B", "right": "D"},
    {"id": "C"},
    {"id": "D"}
  ],
  "answer": ["B", "D", "A", "C"]
}
```

该例预期命中「三种遍历中的访问时机」并返回 `diagnose / visit_timing`。换为 `["A","C","B","D"]` 应使用「固定左右子树顺序」。删除 `answer` 应返回 `clarify`。只有无关片段时，应返回 `refuse`。本地串行化入口和固定证据已测试。

[节点代码](../../ai-agent-service/app/rag/tree_diagnosis.py) · [二叉树讲义](../../ai-agent-service/app/rag/binary-tree-traversal.md) · [竞赛实现和本地复验](../../HUAWEI-ICT.md)
