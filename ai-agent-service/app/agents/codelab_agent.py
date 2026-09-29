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
        code = self._code_for(topic)
        content = (
            f"## {topic}代码实验\n\n"
            f"> 个性化安排：{personalization.learning_instruction()}。\n\n"
            "### 实验目标\n"
            "- 把抽象规则转成可运行步骤。\n"
            "- 用打印日志观察关键状态变化。\n\n"
            "### Python 实验\n"
            f"```python\n{code}\n```\n\n"
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
