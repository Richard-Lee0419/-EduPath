from app.rag.retriever import RagEvidence
from app.schemas.resource_schema import GeneratedResource
from app.services.personalization import PersonalizationContext


class MindmapAgent:
    name = "MindmapAgent"

    def generate(
        self,
        resource_type: str,
        topic: str,
        difficulty: str,
        knowledge_points: list[str],
        evidence: list[RagEvidence],
        personalization: PersonalizationContext,
    ) -> GeneratedResource:
        if resource_type == "flowchart":
            title = f"{topic}流程图"
            content = self._flowchart(topic, evidence, personalization)
            summary = personalization.reason_for(resource_type, topic)
        else:
            title = f"{topic}知识图谱"
            content = self._mindmap(topic, evidence, personalization)
            summary = personalization.reason_for(resource_type, topic)
        return GeneratedResource(
            title=title,
            resource_type=resource_type,
            content_format="markdown",
            content=content,
            summary=summary,
            difficulty=difficulty,
            knowledge_points=knowledge_points,
            personalized_reason=personalization.reason_for(resource_type, topic),
            estimated_minutes=personalization.estimated_minutes,
            profile_fingerprint=personalization.fingerprint,
        )

    def _mindmap(self, topic: str, evidence: list[RagEvidence], personalization: PersonalizationContext) -> str:
        points = "\n".join(f"  - {item.knowledge_point}：{item.title}" for item in evidence[:4])
        classic_visual = _classic_visual(topic, evidence)
        mermaid_points = "\n".join(
            f"      证据{index + 1}[{_mermaid_label(item.knowledge_point)}]"
            for index, item in enumerate(evidence[:4])
        )
        evidence_lines = "\n".join(
            f"- [{item.chunk_id}] {item.title}" for item in evidence[:4]
        )
        return (
            f"## {topic}知识图谱\n\n"
            f"> 个性化安排：{personalization.learning_instruction()}。\n\n"
            "```mermaid\n"
            "mindmap\n"
            f"  root(({_mermaid_label(topic)}))\n"
            "    核心概念\n"
            f"{mermaid_points}\n"
            "    学习关系\n"
            "      定义识别\n"
            "      过程推演\n"
            "      边界验证\n"
            "```\n\n"
            f"{classic_visual}"
            f"- 核心概念\n{points}\n"
            "- 学习顺序\n"
            "  - 定义识别\n"
            "  - 过程推演\n"
            "  - 边界样例\n"
            "  - 迁移练习\n"
            "- 易错点\n"
            "  - 把执行顺序和结果顺序混淆\n"
            "  - 忽略边界条件或状态更新\n"
            f"\n### RAG 证据索引\n{evidence_lines}\n"
        )

    def _flowchart(self, topic: str, evidence: list[RagEvidence], personalization: PersonalizationContext) -> str:
        first = evidence[0].knowledge_point if evidence else topic
        classic_visual = _classic_visual(topic, evidence)
        evidence_lines = "\n".join(
            f"- [{item.chunk_id}] {item.title}" for item in evidence[:4]
        )
        return (
            f"## {topic}流程图\n\n"
            f"> 个性化安排：{personalization.learning_instruction()}。\n\n"
            "```mermaid\n"
            "flowchart TD\n"
            f"  A[读取题目: {first}] --> B{{是否满足边界条件}}\n"
            "  B -- 是 --> C[返回基础结果]\n"
            "  B -- 否 --> D[拆分子问题或地址字段]\n"
            "  D --> E[按规则执行下一步]\n"
            "  E --> F[记录状态变化]\n"
            "  F --> G[检查结果与证据是否一致]\n"
            "```\n\n"
            f"{classic_visual}"
            f"### RAG 证据索引\n{evidence_lines}\n"
        )


def _mermaid_label(value: str) -> str:
    return value.replace("[", "").replace("]", "").replace("(", "").replace(")", "")[:60]


def _classic_visual(topic: str, evidence: list[RagEvidence]) -> str:
    """Return an evidence-linked teaching diagram for classic data-structure topics."""
    searchable = " ".join(
        [topic]
        + [item.knowledge_point for item in evidence]
        + [item.title for item in evidence]
        + [item.content[:600] for item in evidence]
    ).lower()

    if any(keyword in searchable for keyword in ("红黑树", "red-black", "red black tree")):
        citation = _matching_citation(evidence, ("红黑树", "red-black", "red black tree"))
        return (
            "### 经典图示：红黑树结构与插入修复\n\n"
            "先看一个满足颜色约束的局部结构：红色节点不能直接连接红色孩子，"
            "从根到叶子的黑色节点数量保持一致。\n\n"
            "```mermaid\n"
            "flowchart TB\n"
            "  n10((\"10 · 黑\")) --> n5((\"5 · 红\"))\n"
            "  n10 --> n15((\"15 · 黑\"))\n"
            "  n5 --> n3((\"3 · 黑\"))\n"
            "  n5 --> n7((\"7 · 黑\"))\n"
            "  classDef blackNode fill:#263238,color:#fff,stroke:#101918,stroke-width:3px\n"
            "  classDef redNode fill:#e95d50,color:#fff,stroke:#a52d25,stroke-width:3px\n"
            "  class n10,n15,n3,n7 blackNode\n"
            "  class n5 redNode\n"
            "```\n\n"
            "```mermaid\n"
            "flowchart LR\n"
            "  A[插入新节点并标红] --> B[检查父节点颜色]\n"
            "  B -->|父节点为黑| C[结构合法，结束]\n"
            "  B -->|父节点为红| D[检查叔叔节点]\n"
            "  D -->|叔叔为红| E[父叔变黑，祖父变红]\n"
            "  E --> B\n"
            "  D -->|叔叔为黑或空| F[按 LL/LR/RL/RR 旋转]\n"
            "  F --> G[重新着色并保持根为黑]\n"
            "```\n\n"
            f"> 图示依据：[{citation}]；颜色只表达红黑属性，不代表节点数值大小。\n\n"
        )

    if any(keyword in searchable for keyword in ("avl", "平衡二叉", "平衡树")):
        citation = _matching_citation(evidence, ("avl", "平衡二叉", "平衡树"))
        return (
            "### 经典图示：AVL 四类失衡修复\n\n"
            "```mermaid\n"
            "flowchart LR\n"
            "  A[发现失衡节点] --> B[LL: 右旋]\n"
            "  A --> C[RR: 左旋]\n"
            "  A --> D[LR: 先左旋子树，再右旋]\n"
            "  A --> E[RL: 先右旋子树，再左旋]\n"
            "  B --> F[重新计算高度]\n"
            "  C --> F\n"
            "  D --> F\n"
            "  E --> F\n"
            "```\n\n"
            f"> 图示依据：[{citation}]。\n\n"
        )

    if any(keyword in searchable for keyword in ("快速排序", "quicksort", "quick sort")):
        citation = _matching_citation(evidence, ("快速排序", "quicksort", "quick sort"))
        return (
            "### 经典图示：快速排序的一次分区\n\n"
            "```mermaid\n"
            "flowchart LR\n"
            "  A[待排区间] --> B[选择 pivot]\n"
            "  B --> C[小于 pivot 的元素]\n"
            "  B --> D[pivot 就位]\n"
            "  B --> E[大于等于 pivot 的元素]\n"
            "  C --> F[递归处理左区间]\n"
            "  E --> G[递归处理右区间]\n"
            "  F --> H[合并得到有序序列]\n"
            "  D --> H\n"
            "  G --> H\n"
            "```\n\n"
            f"> 图示依据：[{citation}]。\n\n"
        )

    if any(keyword in searchable for keyword in ("堆排序", "最大堆", "最小堆", "heap")):
        citation = _matching_citation(evidence, ("堆排序", "最大堆", "最小堆", "heap"))
        return (
            "### 经典图示：最大堆的数组—树对应关系\n\n"
            "```mermaid\n"
            "flowchart TB\n"
            "  n0((90)) --> n1((70))\n"
            "  n0 --> n2((60))\n"
            "  n1 --> n3((40))\n"
            "  n1 --> n4((30))\n"
            "  n2 --> n5((20))\n"
            "  n2 --> n6((10))\n"
            "```\n\n"
            "数组下标从 0 开始时，节点 `i` 的孩子位于 `2i+1` 和 `2i+2`；树形图帮助观察父节点与孩子的大小约束。\n\n"
            f"> 图示依据：[{citation}]。\n\n"
        )

    if any(keyword in searchable for keyword in ("dijkstra", "迪杰斯特拉", "最短路径")):
        citation = _matching_citation(evidence, ("dijkstra", "迪杰斯特拉", "最短路径"))
        return (
            "### 经典图示：Dijkstra 松弛循环\n\n"
            "```mermaid\n"
            "flowchart TD\n"
            "  A[初始化源点距离为 0] --> B[选择未确定且距离最小的节点 u]\n"
            "  B --> C[将 u 标记为已确定]\n"
            "  C --> D[遍历 u 的相邻边]\n"
            "  D --> E[尝试用 dist u + 边权更新 dist v]\n"
            "  E --> F[仍有未确定节点]\n"
            "  F -->|是| B\n"
            "  F -->|否| G[输出最短距离与前驱]\n"
            "```\n\n"
            f"> 图示依据：[{citation}]。\n\n"
        )

    return ""


def _matching_citation(evidence: list[RagEvidence], keywords: tuple[str, ...]) -> str:
    for item in evidence:
        haystack = f"{item.knowledge_point} {item.title} {item.content}".lower()
        if any(keyword.lower() in haystack for keyword in keywords):
            return item.chunk_id
    return evidence[0].chunk_id if evidence else "课程知识库"
