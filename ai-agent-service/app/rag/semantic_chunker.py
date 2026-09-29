from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass
from hashlib import sha1

from app.rag.embeddings import tokenize


@dataclass(frozen=True)
class SemanticAtom:
    index: int
    text: str
    role: str
    heading_path: tuple[str, ...]
    anchors: tuple[str, ...]


@dataclass(frozen=True)
class SemanticChunkSpec:
    title_suffix: str
    content: str
    anchors: tuple[str, ...]
    roles: tuple[str, ...]
    heading_path: tuple[str, ...]
    semantic_signature: str


class SemanticChunker:
    """Concept-role graph chunker for course knowledge documents.

    The chunker treats a document as pedagogical atoms instead of fixed windows.
    Boundary decisions combine heading changes, concept-anchor overlap, local
    lexical coherence, and teaching-role transitions such as definition ->
    example -> algorithm -> complexity.
    """

    def __init__(
        self,
        *,
        min_chars: int = 220,
        target_chars: int = 720,
        hard_max_chars: int = 1100,
        max_anchors: int = 14,
    ) -> None:
        self.min_chars = min_chars
        self.target_chars = target_chars
        self.hard_max_chars = hard_max_chars
        self.max_anchors = max_anchors

    def split(self, title: str, content: str) -> list[SemanticChunkSpec]:
        atoms = self._atomize(content)
        if not atoms:
            return [
                SemanticChunkSpec(
                    title_suffix="",
                    content="空文档，未解析到有效文本。",
                    anchors=(),
                    roles=("empty",),
                    heading_path=(),
                    semantic_signature="empty",
                )
            ]

        chunks: list[list[SemanticAtom]] = []
        current: list[SemanticAtom] = []
        for atom in atoms:
            if current and self._should_cut(current, atom):
                chunks.append(current)
                current = []
            current.append(atom)
            if self._content_length(current) >= self.hard_max_chars:
                chunks.append(current)
                current = []
        if current:
            chunks.append(current)

        return [self._to_spec(title, chunk_atoms) for chunk_atoms in chunks]

    def _atomize(self, content: str) -> list[SemanticAtom]:
        blocks = _split_blocks(content)
        atoms: list[SemanticAtom] = []
        heading_stack: list[str] = []
        for block in blocks:
            for text in _split_oversized_block(block, self.hard_max_chars):
                role = _detect_role(text)
                if role == "heading":
                    level, heading = _parse_heading(text)
                    heading_stack = heading_stack[: max(0, level - 1)]
                    heading_stack.append(heading)
                anchors = _extract_anchors(text)
                atoms.append(
                    SemanticAtom(
                        index=len(atoms),
                        text=text,
                        role=role,
                        heading_path=tuple(heading_stack),
                        anchors=anchors,
                    )
                )
        return atoms

    def _should_cut(self, current: list[SemanticAtom], next_atom: SemanticAtom) -> bool:
        current_len = self._content_length(current)
        projected_len = current_len + len(next_atom.text) + 2
        if projected_len > self.hard_max_chars:
            return True
        if current_len < self.min_chars:
            return False

        previous = current[-1]
        if next_atom.role == "heading" and current_len >= self.min_chars:
            return True
        if projected_len > self.target_chars:
            return True

        continuity = _continuity_score(previous, next_atom)
        strong_role_boundary = previous.role in {"complexity", "exercise"} and next_atom.role in {
            "heading",
            "definition",
            "algorithm",
            "architecture",
            "concept",
        }
        heading_changed = _heading_distance(previous.heading_path, next_atom.heading_path) >= 2
        return continuity < 0.18 or (heading_changed and continuity < 0.32) or strong_role_boundary

    @staticmethod
    def _content_length(atoms: list[SemanticAtom]) -> int:
        return sum(len(atom.text) for atom in atoms) + max(0, len(atoms) - 1) * 2

    def _to_spec(self, title: str, atoms: list[SemanticAtom]) -> SemanticChunkSpec:
        content = "\n\n".join(atom.text for atom in atoms).strip()
        anchor_counter: Counter[str] = Counter()
        role_order: list[str] = []
        heading_path: tuple[str, ...] = ()
        for atom in atoms:
            anchor_counter.update(atom.anchors)
            if atom.role not in role_order:
                role_order.append(atom.role)
            if atom.heading_path:
                heading_path = atom.heading_path

        anchors = tuple(anchor for anchor, _ in anchor_counter.most_common(self.max_anchors))
        roles = tuple(role_order)
        title_suffix = _title_suffix(title, heading_path)
        signature_basis = "|".join((*anchors, *roles, content[:240]))
        signature = sha1(signature_basis.encode("utf-8")).hexdigest()[:16]
        return SemanticChunkSpec(
            title_suffix=title_suffix,
            content=content,
            anchors=anchors,
            roles=roles,
            heading_path=heading_path,
            semantic_signature=signature,
        )


COURSE_ANCHORS: tuple[str, ...] = (
    "二叉树",
    "二叉搜索树",
    "平衡树",
    "AVL",
    "红黑树",
    "堆",
    "栈",
    "队列",
    "链表",
    "数组",
    "哈希表",
    "图",
    "深度优先",
    "广度优先",
    "DFS",
    "BFS",
    "最短路径",
    "Dijkstra",
    "Bellman-Ford",
    "拓扑排序",
    "动态规划",
    "贪心",
    "分治",
    "回溯",
    "复杂度",
    "递归",
    "排序",
    "查找",
    "Cache",
    "缓存",
    "主存",
    "CPU",
    "流水线",
    "指令",
    "寄存器",
    "ALU",
    "总线",
    "页表",
    "TLB",
    "虚拟地址",
    "物理地址",
    "虚拟存储",
    "中断",
    "异常",
    "补码",
    "定点数",
    "浮点数",
    "寻址方式",
    "数据通路",
    "控制器",
    "冒险",
    "分支预测",
)

STOP_ANCHORS: set[str] = {
    "可以",
    "因此",
    "通常",
    "例如",
    "如果",
    "需要",
    "以及",
    "这个",
    "一种",
    "进行",
    "使用",
    "实现",
    "说明",
    "问题",
    "方法",
    "过程",
}

HEADING_RE = re.compile(r"^(#{1,6})\s+(.+)$|^(第[一二三四五六七八九十0-9]+[章节]\s*[:：]?.+)$|^([一二三四五六七八九十0-9]+[、.．]\s*.+)$")
LIST_RE = re.compile(r"^\s*(?:[-*+]|\d+[.)、])\s+")
COMPLEXITY_RE = re.compile(r"O\([^)]+\)|复杂度|时间复杂度|空间复杂度", re.IGNORECASE)
EQUATION_RE = re.compile(r"[$=≤≥<>∑√]|\\\(|\\\[")
EN_TERM_RE = re.compile(r"\b[A-Za-z][A-Za-z0-9_+#-]{1,}\b")
ZH_PHRASE_RE = re.compile(r"[\u4e00-\u9fffA-Za-z0-9_+#-]{2,18}")


def _split_blocks(content: str) -> list[str]:
    normalized = "\n".join(line.rstrip() for line in (content or "").replace("\r\n", "\n").splitlines())
    lines = normalized.split("\n")
    blocks: list[str] = []
    current: list[str] = []
    in_code = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("```"):
            current.append(line.rstrip())
            if in_code:
                blocks.append("\n".join(current).strip())
                current = []
            in_code = not in_code
            continue
        if in_code:
            current.append(line.rstrip())
            continue
        if not stripped:
            if current:
                blocks.append("\n".join(current).strip())
                current = []
            continue
        if _is_heading(stripped) and current:
            blocks.append("\n".join(current).strip())
            current = []
        current.append(stripped)
        if _is_heading(stripped):
            blocks.append("\n".join(current).strip())
            current = []
    if current:
        blocks.append("\n".join(current).strip())
    return [block for block in blocks if block]


def _split_oversized_block(block: str, hard_max_chars: int) -> list[str]:
    if len(block) <= hard_max_chars:
        return [block]
    sentences = re.split(r"(?<=[。！？；.!?;])\s*", block)
    pieces: list[str] = []
    current = ""
    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence:
            continue
        if current and len(current) + len(sentence) > hard_max_chars:
            pieces.append(current)
            current = sentence
        else:
            current = sentence if not current else f"{current}{sentence}"
    if current:
        pieces.append(current)
    return pieces or [block[:hard_max_chars]]


def _detect_role(text: str) -> str:
    stripped = text.strip()
    if _is_heading(stripped):
        return "heading"
    if stripped.startswith("```") or "\n```" in stripped:
        return "code"
    if "习题" in stripped or "练习" in stripped or "请证明" in stripped:
        return "exercise"
    if COMPLEXITY_RE.search(stripped):
        return "complexity"
    if any(keyword in stripped for keyword in ("定义", "称为", "是指", "不变式", "性质")):
        return "definition"
    if any(keyword in stripped for keyword in ("算法", "步骤", "伪代码", "遍历", "递归过程")):
        return "algorithm"
    if any(keyword in stripped for keyword in ("例如", "示例", "例：", "样例")) or LIST_RE.search(stripped):
        return "example"
    if any(keyword in stripped for keyword in ("Cache", "缓存", "CPU", "流水线", "寄存器", "页表", "TLB", "指令", "主存")):
        return "architecture"
    if EQUATION_RE.search(stripped):
        return "equation"
    return "concept"


def _extract_anchors(text: str) -> tuple[str, ...]:
    anchors: list[str] = []
    lower_text = text.lower()
    for anchor in COURSE_ANCHORS:
        if anchor.lower() in lower_text:
            anchors.append(anchor)

    for match in EN_TERM_RE.findall(text):
        if len(match) >= 2 and match.lower() not in STOP_ANCHORS:
            anchors.append(match)

    for phrase in ZH_PHRASE_RE.findall(text):
        if phrase in STOP_ANCHORS:
            continue
        if any(anchor in phrase or phrase in anchor for anchor in COURSE_ANCHORS):
            anchors.append(phrase)
        elif 2 <= len(phrase) <= 8 and any(suffix in phrase for suffix in ("树", "图", "表", "栈", "队列", "算法", "地址", "指令", "缓存", "存储", "流水线")):
            anchors.append(phrase)

    seen: set[str] = set()
    result: list[str] = []
    for anchor in anchors:
        normalized = anchor.strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        result.append(normalized)
    return tuple(result)


def _continuity_score(left: SemanticAtom, right: SemanticAtom) -> float:
    anchor_score = _jaccard(set(left.anchors), set(right.anchors))
    token_score = _jaccard(set(tokenize(left.text)), set(tokenize(right.text)))
    role_score = _role_transition_score(left.role, right.role)
    heading_score = 1.0 - min(1.0, _heading_distance(left.heading_path, right.heading_path) / 3)
    return 0.42 * anchor_score + 0.28 * token_score + 0.18 * role_score + 0.12 * heading_score


def _role_transition_score(left: str, right: str) -> float:
    if left == right:
        return 1.0
    strong_pairs = {
        ("heading", "definition"),
        ("heading", "concept"),
        ("definition", "example"),
        ("definition", "algorithm"),
        ("concept", "definition"),
        ("concept", "example"),
        ("algorithm", "code"),
        ("algorithm", "complexity"),
        ("code", "complexity"),
        ("architecture", "example"),
        ("architecture", "definition"),
        ("equation", "example"),
    }
    if (left, right) in strong_pairs:
        return 0.82
    if right == "heading":
        return 0.05
    if left in {"complexity", "exercise"}:
        return 0.18
    return 0.42


def _heading_distance(left: tuple[str, ...], right: tuple[str, ...]) -> int:
    common = 0
    for left_item, right_item in zip(left, right):
        if left_item != right_item:
            break
        common += 1
    return (len(left) - common) + (len(right) - common)


def _jaccard(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)


def _is_heading(text: str) -> bool:
    return bool(HEADING_RE.match(text.strip()))


def _parse_heading(text: str) -> tuple[int, str]:
    stripped = text.strip()
    markdown = re.match(r"^(#{1,6})\s+(.+)$", stripped)
    if markdown:
        return len(markdown.group(1)), markdown.group(2).strip()
    numbered = re.match(r"^([一二三四五六七八九十0-9]+)[、.．]\s*(.+)$", stripped)
    if numbered:
        return 2, numbered.group(2).strip()
    return 1, stripped


def _title_suffix(title: str, heading_path: tuple[str, ...]) -> str:
    if not heading_path:
        return ""
    tail = heading_path[-1].strip()
    if not tail or tail == title:
        return ""
    return tail
