"""Deterministic traversal diagnosis for EduPath and the AgentArts code node."""

import json
import re


DOCUMENT = "binary-tree-traversal.md"
MARKER = "EDUPATH-TREE-DFS-V1"
ORDERS = {"preorder": "前序", "inorder": "中序", "postorder": "后序"}
SECTIONS = {
    "order": "三种遍历中的访问时机",
    "direction": "固定左右子树顺序",
    "coverage": "检查节点是否恰好输出一次",
}


def _label(value):
    return isinstance(value, str) and re.fullmatch("[A-Z]", value) is not None


def _validated_nodes(nodes):
    if not isinstance(nodes, list) or not 1 <= len(nodes) <= 15:
        raise ValueError("请给出 1 至 15 个节点的树结构。")
    graph = {}
    children = []
    for node in nodes:
        if not isinstance(node, dict) or not _label(node.get("id")) or node["id"] in graph:
            raise ValueError("各节点必须使用唯一的大写字母标识。")
        pair = (node.get("left"), node.get("right"))
        if any(child is not None and not _label(child) for child in pair):
            raise ValueError("左右孩子应为单个大写字母，空孩子用 null。")
        graph[node["id"]] = pair
        children.extend(child for child in pair if child is not None)
    roots = graph.keys() - set(children)
    if len(roots) != 1 or len(set(children)) != len(children) or any(child not in graph for child in children):
        raise ValueError("请检查根、左右孩子引用及重复父节点。")
    root = next(iter(roots))
    if len(_traverse(graph, root, "preorder")) != len(graph):
        raise ValueError("请检查是否存在与根不相连的节点。")
    return graph, root


def _traverse(graph, node, order, right_first=False):
    if node is None:
        return []
    first, second = graph[node]
    if right_first:
        first, second = second, first
    first_walk = _traverse(graph, first, order, right_first)
    second_walk = _traverse(graph, second, order, right_first)
    if order == "preorder":
        return [node, *first_walk, *second_walk]
    if order == "inorder":
        return [*first_walk, node, *second_walk]
    return [*first_walk, *second_walk, node]


def _citation(hits, section):
    pattern = rf"{MARKER}:{section}[ \t]*\r?\n([^\r\n]+)"
    if not isinstance(hits, list):
        return None
    for hit in hits:
        if not isinstance(hit, dict) or hit.get("document_name") not in (DOCUMENT, DOCUMENT[:-3]):
            continue
        content = hit.get("content")
        if not isinstance(content, str) or (match := re.search(pattern, content)) is None:
            continue
        return {
            "marker": MARKER, "document": DOCUMENT, "section": SECTIONS[section],
            "excerpt": match.group(1).strip(), "scope": "public_course", "source_version": "v1",
        }
    return None


def _feedback(graph, root, order, answer, expected):
    if answer == expected:
        return "confirm", "", "order", f"序列符合{ORDERS[order]}遍历。"
    if len(answer) != len(graph) or set(answer) != graph.keys():
        return "diagnose", "node_coverage", "coverage", "先检查遗漏、重复或未在题中出现的节点。"
    if answer == _traverse(graph, root, order, right_first=True):
        return "diagnose", "right_before_left", "direction", "你的序列与先走右子树一致。先核对题目给定的左右孩子，再完整走完左子树。"
    for other_order, name in ORDERS.items():
        if other_order != order and answer == _traverse(graph, root, other_order):
            return "diagnose", "visit_timing", "order", f"你的序列与{name}遍历一致。这题要求{ORDERS[order]}，请核对什么时候输出当前节点。"
    return "diagnose", "sequence_order", "direction", "请从首个不同的位置回到对应节点，核对左右子树的递归顺序。"


def diagnose(problem, hits):
    if not isinstance(problem, dict):
        return {"decision": "clarify", "explanation": "请给出二叉树、遍历方式和作答序列。"}
    if problem.get("topic") != "binary_tree":
        return {"decision": "refuse", "explanation": "讲义覆盖二叉树递归遍历，请提供这个范围内的问题。"}
    order, nodes, answer = (problem.get(key) for key in ("order", "nodes", "answer"))
    if not isinstance(order, str) or order not in ORDERS or nodes is None or answer is None:
        return {"decision": "clarify", "explanation": "请给出二叉树、遍历方式和作答序列。"}
    if not isinstance(answer, list) or not 1 <= len(answer) <= 24 or not all(map(_label, answer)):
        return {"decision": "clarify", "explanation": "请用 1 至 24 个大写字母表示访问序列。"}
    try:
        graph, root = _validated_nodes(nodes)
    except ValueError as issue:
        return {"decision": "clarify", "explanation": str(issue)}

    expected = _traverse(graph, root, order)
    decision, mistake, section, explanation = _feedback(graph, root, order, answer, expected)
    citation = _citation(hits, section)
    if citation is None or not citation["excerpt"]:
        return {"decision": "refuse", "explanation": "未召回支持本次诊断的遍历讲义片段。"}
    if decision == "diagnose":
        divergence = next((step for step, (actual, target) in enumerate(zip(answer, expected), 1)
                           if actual != target), min(len(answer), len(expected)) + 1)
        explanation += f" 从第 {divergence} 个位置开始核对。"
    explanation += " 正确序列为 " + " → ".join(expected) + "。"
    return {"decision": decision, "mistake": mistake, "explanation": explanation,
            "expected": expected, "citation": citation}


def main(args):
    """AgentArts adapter. A Start variable holds the serialized problem JSON."""
    try:
        problem = json.loads(args.get("problem_json", ""))
    except (ValueError, TypeError):
        problem = None
    result = diagnose(problem, args.get("output_list"))
    return {"result_json": json.dumps(result, ensure_ascii=False)}
