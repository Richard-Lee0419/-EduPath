from __future__ import annotations

import json
import os
from dataclasses import dataclass
from hashlib import sha1, sha256
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class SourceDocument:
    course_id: int
    course_code: str
    knowledge_point_id: int
    knowledge_point: str
    title: str
    content: str
    source: str
    license: str = ""
    author: str = ""
    source_url: str = ""
    content_hash: str = ""
    license_status: str = "pending"
    related_knowledge_point_ids: tuple[int, ...] = ()
    document_type: str = "lecture"
    language: str = ""
    contains_examples: bool = False


class DocumentLoader:
    """Document loading boundary for PDF, Word, Markdown, and plain text."""

    def __init__(self, seed_corpus_path: str | None = None, include_builtin: bool = True) -> None:
        self.seed_corpus_path = seed_corpus_path
        self.include_builtin = include_builtin

    def load_builtin_documents(self) -> list[SourceDocument]:
        """Return the built-in corpus plus optional Hugging Face seed documents."""

        builtin = list(BUILTIN_DOCUMENTS) if self.include_builtin else []
        return _deduplicate_documents([*builtin, *self.load_seed_documents()])

    def load_seed_documents(self, path: str | None = None) -> list[SourceDocument]:
        configured_path = path if path is not None else self.seed_corpus_path
        corpus_path = _resolve_seed_corpus_path(configured_path or os.getenv("EDUPATH_SEED_CORPUS_PATH") or "")
        if not corpus_path.exists():
            return []

        documents: list[SourceDocument] = []
        with corpus_path.open("r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    item = json.loads(line)
                except json.JSONDecodeError:
                    continue
                document = _to_source_document(item)
                if document is not None:
                    documents.append(document)
        return _deduplicate_documents(documents)


BUILTIN_DOCUMENTS: tuple[SourceDocument, ...] = (
    SourceDocument(
        course_id=1,
        course_code="data_structures_algorithms",
        knowledge_point_id=131,
        knowledge_point="二叉树递归遍历",
        title="二叉树递归遍历与调用栈",
        content=(
            "二叉树递归遍历的核心是把一棵树拆成根节点、左子树和右子树三个部分。"
            "前序遍历先访问根节点，再递归遍历左子树和右子树；中序遍历先左子树、再根节点、再右子树；"
            "后序遍历先处理左右子树，最后访问根节点。递归出口通常是当前节点为空。"
            "理解调用栈有助于判断访问顺序和返回时机。"
        ),
        source="data_structures_algorithms/tree-recursive-traversal.md",
    ),
    SourceDocument(
        course_id=1,
        course_code="data_structures_algorithms",
        knowledge_point_id=132,
        knowledge_point="二叉搜索树",
        title="二叉搜索树不变式",
        content=(
            "二叉搜索树要求任意节点的左子树关键字小于该节点，右子树关键字大于该节点。"
            "查找、插入和删除都依赖这个有序不变式。中序遍历一棵二叉搜索树可以得到递增序列，"
            "删除有两个孩子的节点时通常用前驱或后继替换。"
        ),
        source="data_structures_algorithms/binary-search-tree.md",
    ),
    SourceDocument(
        course_id=1,
        course_code="data_structures_algorithms",
        knowledge_point_id=141,
        knowledge_point="图的遍历",
        title="图的深度优先与广度优先遍历",
        content=(
            "深度优先遍历使用递归或栈沿一条路径尽可能深入，适合连通性、拓扑关系和回溯问题。"
            "广度优先遍历使用队列逐层扩展，适合无权图最短路径和层次距离。"
            "图遍历必须记录 visited 集合，避免在环中重复访问。"
        ),
        source="data_structures_algorithms/graph-traversal.md",
    ),
    SourceDocument(
        course_id=1,
        course_code="data_structures_algorithms",
        knowledge_point_id=142,
        knowledge_point="最短路径",
        title="最短路径算法选择",
        content=(
            "无权图最短路径可以用 BFS；边权非负时常用 Dijkstra；存在负权边时可使用 Bellman-Ford 检测负环。"
            "算法选择取决于图规模、边权条件和是否需要恢复路径。"
        ),
        source="data_structures_algorithms/shortest-path.md",
    ),
    SourceDocument(
        course_id=2,
        course_code="computer_organization",
        knowledge_point_id=251,
        knowledge_point="Cache 映射方式",
        title="Cache 直接映射、全相联与组相联",
        content=(
            "Cache 映射方式决定主存块可以放入哪些 Cache 行。直接映射中每个主存块只能映射到一个固定行，"
            "硬件简单但冲突缺失更明显；全相联允许放入任意行，冲突少但比较器成本高；"
            "组相联把 Cache 分成多个组，主存块先定位到组，再在组内选择任意行。地址通常拆成标记、索引和块内偏移。"
        ),
        source="computer_organization/cache-mapping.md",
    ),
    SourceDocument(
        course_id=2,
        course_code="computer_organization",
        knowledge_point_id=252,
        knowledge_point="虚拟存储器",
        title="虚拟地址、页表与 TLB",
        content=(
            "虚拟存储器把程序看到的虚拟地址转换成物理地址。页表记录虚拟页到物理页框的映射，"
            "TLB 缓存最近使用的页表项以降低地址转换开销。缺页异常发生时，操作系统需要把页面调入内存。"
        ),
        source="computer_organization/virtual-memory.md",
    ),
    SourceDocument(
        course_id=2,
        course_code="computer_organization",
        knowledge_point_id=242,
        knowledge_point="CPU 与流水线",
        title="流水线冒险与处理",
        content=(
            "CPU 流水线把取指、译码、执行、访存和写回等阶段重叠执行。"
            "结构冒险来自资源冲突，数据冒险来自指令依赖，控制冒险来自分支改变执行流。"
            "常见处理方式包括暂停、转发、分支预测和流水线清空。"
        ),
        source="computer_organization/cpu-pipeline.md",
    ),
    SourceDocument(
        course_id=2,
        course_code="computer_organization",
        knowledge_point_id=211,
        knowledge_point="数据表示",
        title="补码与定点数表示",
        content=(
            "计算机常用补码表示有符号整数。补码的最高位表示符号权重，减法可以转化为加法，"
            "溢出判断需要看符号位和进位关系。定点数的小数点位置由约定决定。"
        ),
        source="computer_organization/data-representation.md",
    ),
)


def _to_source_document(item: dict[str, Any]) -> SourceDocument | None:
    content = str(item.get("content") or "").strip()
    if not content:
        return None
    course_code = str(item.get("course_code") or _infer_course_code(item)).strip()
    knowledge_point = str(item.get("knowledge_point") or item.get("topic") or "课程扩展语料").strip()
    return SourceDocument(
        course_id=int(item.get("course_id") or _course_id(course_code)),
        course_code=course_code,
        knowledge_point_id=int(item.get("knowledge_point_id") or _stable_knowledge_point_id(course_code, knowledge_point)),
        knowledge_point=knowledge_point,
        title=str(item.get("title") or knowledge_point).strip(),
        content=content,
        source=str(item.get("source") or item.get("dataset") or "hf_seed_corpus").strip(),
        license=str(item.get("license") or "").strip(),
        author=str(item.get("author") or "").strip(),
        source_url=str(item.get("source_url") or "").strip(),
        content_hash=str(item.get("content_hash") or _content_hash(content)).strip(),
        license_status=str(item.get("license_status") or "pending").strip(),
        related_knowledge_point_ids=tuple(int(value) for value in item.get("related_knowledge_point_ids") or []),
        document_type=str(item.get("document_type") or "lecture").strip(),
        language=str(item.get("language") or "").strip(),
        contains_examples=bool(item.get("contains_examples", False)),
    )


def _default_seed_corpus_path() -> Path:
    return Path(__file__).resolve().parents[2] / "data" / "seed_corpus" / "seed_documents.jsonl"


def _resolve_seed_corpus_path(raw_path: str) -> Path:
    if not raw_path:
        return _default_seed_corpus_path()
    candidate = Path(raw_path)
    if candidate.is_absolute() or candidate.exists():
        return candidate
    service_root = Path(__file__).resolve().parents[2]
    repo_root = service_root.parent
    for base in (service_root, repo_root):
        resolved = base / candidate
        if resolved.exists():
            return resolved
    return candidate


def _infer_course_code(item: dict[str, Any]) -> str:
    text = " ".join(str(item.get(key) or "") for key in ("course", "topic", "knowledge_point", "title", "content"))
    if any(keyword in text for keyword in ("Cache", "CPU", "流水线", "寄存器", "页表", "computer architecture")):
        return "computer_organization"
    return "data_structures_algorithms"


def _course_id(course_code: str) -> int:
    return 2 if course_code == "computer_organization" else 1


def _stable_knowledge_point_id(course_code: str, knowledge_point: str) -> int:
    digest = sha1(f"{course_code}:{knowledge_point}".encode("utf-8")).hexdigest()
    return 9000 + int(digest[:5], 16) % 90000


def _deduplicate_documents(documents: list[SourceDocument]) -> list[SourceDocument]:
    unique: list[SourceDocument] = []
    seen: set[tuple[int, str]] = set()
    for document in documents:
        signature = document.content_hash or _content_hash(document.content)
        key = (document.course_id, signature)
        if key in seen:
            continue
        seen.add(key)
        unique.append(document)
    return unique


def _content_hash(content: str) -> str:
    normalized = " ".join(content.split())
    return sha256(normalized.encode("utf-8")).hexdigest()
