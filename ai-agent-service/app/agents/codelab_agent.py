from app.rag.retriever import RagEvidence
from app.schemas.resource_schema import GeneratedResource
from app.services.personalization import PersonalizationContext


class CodelabAgent:
    name = "CodelabAgent"

    def generate(
        self,
        topic: str,
        difficulty: str,
        knowledge_points: list[str],
        evidence: list[RagEvidence],
        personalization: PersonalizationContext,
    ) -> GeneratedResource:
        page_evidence = next((item for item in evidence if item.page is not None), None)
        content = self._material_lab(topic, page_evidence, personalization) if page_evidence else (
            f"## {topic}代码实验\n\n"
            f"> 个性化安排：{personalization.learning_instruction()}。\n\n"
            "### 实验目标\n"
            "- 把抽象规则转成可运行步骤。\n"
            "- 用打印日志观察关键状态变化。\n\n"
            "### Python 实验\n"
            f"```python\n{self._code_for(topic)}\n```\n\n"
            "### 观察任务\n"
            "1. 修改输入，记录输出变化。\n"
            "2. 写出触发边界条件的最小样例。\n"
            "3. 对照 RAG 证据解释每一次状态更新。"
        )
        return GeneratedResource(
            title=f"{topic}代码实验",
            resource_type="codelab",
            content_format="markdown",
            content=content,
            summary=personalization.reason_for("codelab", topic),
            difficulty=difficulty,
            knowledge_points=knowledge_points,
            personalized_reason=personalization.reason_for("codelab", topic),
            estimated_minutes=personalization.estimated_minutes,
            profile_fingerprint=personalization.fingerprint,
        )

    def _material_lab(
        self, topic: str, evidence: RagEvidence, personalization: PersonalizationContext
    ) -> str:
        return (
            f"## {topic}代码实验\n\n"
            f"> {personalization.learning_instruction()}\n\n"
            f"### 实验目标\n从讲义第 {evidence.page} 页找一个可检验的例子，"
            f"把条件、讲义给出的结果和实测结果分别记录下来。证据：`{evidence.chunk_id}`。\n\n"
            "### Python 记录工具\n"
            "```python\n"
            "case = input('输入讲义中的条件或样例: ')\n"
            "expected = input('记录讲义给出的结果: ')\n"
            "observed = input('记录自行推演或实验得到的结果: ')\n"
            "print({'案例': case, '一致': expected.strip() == observed.strip()})\n"
            "```\n\n"
            "### 观察任务\n"
            "1. 选用讲义里的例子运行记录工具，解释预期结果依据。\n"
            "2. 修改一个条件，重新推演或实验。核对条件是否仍满足讲义所述规则。\n"
            "3. 对照原页标明输入变化和实测差异，将结论保留在证据覆盖范围内。"
        )

    def _code_for(self, topic: str) -> str:
        if "Cache" in topic:
            return (
                "def direct_map(block_number: int, line_count: int) -> int:\n"
                "    return block_number % line_count\n\n"
                "for block in [0, 4, 8, 1, 5]:\n"
                "    print(block, '-> line', direct_map(block, 4))"
            )
        return (
            "class Node:\n"
            "    def __init__(self, value, left=None, right=None):\n"
            "        self.value = value\n"
            "        self.left = left\n"
            "        self.right = right\n\n"
            "def preorder(node):\n"
            "    if node is None:\n"
            "        return []\n"
            "    return [node.value] + preorder(node.left) + preorder(node.right)\n\n"
            "root = Node('A', Node('B'), Node('C'))\n"
            "print(preorder(root))"
        )
