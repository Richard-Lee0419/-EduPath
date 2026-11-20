"""Run the public, offline dual-course regression without models or remote services."""

import json
import re
import sys
from collections import Counter
from pathlib import Path
from random import Random
from statistics import median
from time import perf_counter

ROOT = Path(__file__).resolve().parents[1]
AI = ROOT.parent / "ai-agent-service"
sys.path.insert(0, str(AI))

from app.rag import cache_diagnosis, tree_diagnosis  # noqa: E402

CASES = ROOT / "tests" / "phase3-dual-course-cases.jsonl"
RESULTS = ROOT / "evidence" / "phase3-dual-course-results.jsonl"
REVIEWER = ROOT / "submission" / "phase3-reviewer-items.md"
DOCUMENT_NAMES = {"cache": cache_diagnosis.DOCUMENT + ".md", "tree": tree_diagnosis.DOCUMENT}


def documents():
    return {
        course: {"document_name": name, "content": (AI / "app" / "rag" / name).read_text(encoding="utf-8")}
        for course, name in DOCUMENT_NAMES.items()
    }


def evidence_for(case, handouts):
    course = case["course"]
    match case["evidence"]:
        case "same":
            return [handouts[course]]
        case "none":
            return []
        case "other_course":
            return [handouts["tree" if course == "cache" else "cache"]]
        case "same_unrelated_section":
            content = handouts[course]["content"]
            section = "## 复算方法" if course == "cache" else "## 固定左右子树顺序"
            excerpt = content.split(section, 1)[1].strip().split("\n## ", 1)[0]
            return [{"document_name": DOCUMENT_NAMES[course], "content": excerpt}]
        case other:
            raise ValueError(f"Unknown evidence fixture {other}")


def execute(case, hits):
    if case["course"] == "tree":
        # Run the same serialized entry point available to an AgentArts code node.
        arguments = {"problem_json": json.dumps(case["input"], ensure_ascii=False), "output_list": hits}
        return json.loads(tree_diagnosis.main(arguments)["result_json"])

    result = cache_diagnosis.main({"query": case["input"], "output_list": hits})
    citations = json.loads(result["citations_json"])
    bits = re.search(r"按 Tag / Index / Offset 顺序为 (\d+) / (\d+) / (\d+)", result["answer"])
    actual = {"decision": result["decision"], "mistake": result["mistake"], "explanation": result["answer"]}
    if bits:
        actual["expected"] = [int(value) for value in bits.groups()]
    if citations:
        actual["citation"] = citations[0]
    return actual


def judge(case, actual, handouts):
    expect = case["expects"]
    if actual["decision"] != expect["decision"]:
        return False
    if case["bucket"] != "answerable":
        return "expected" not in actual and "citation" not in actual
    citation = actual.get("citation", {})
    excerpt = citation.get("excerpt")
    original = handouts[case["course"]]
    return (
        actual.get("expected") == expect["expected"]
        and actual.get("mistake") == expect["mistake"]
        and citation.get("document") == original["document_name"]
        and citation.get("section") == expect.get("section", "地址字段计算")
        and citation.get("source_version") == "v1"
        and citation.get("scope") == "public_course"
        and bool(excerpt)
        and " ".join(excerpt.split()) in " ".join(original["content"].split())
    )


def write_reviewer_sheet(cases, handouts):
    labels = {"cache": "A", "tree": "B"}
    evidence_labels = {
        "same": lambda course: labels[course],
        "other_course": lambda course: labels["tree" if course == "cache" else "cache"],
        "same_unrelated_section": lambda course: labels[course] + "2",
        "none": lambda _course: "未提供资料",
    }
    title = [
        "# EduPath 双课程独立标注题单",
        "",
        "每题只用注明的资料。请独立记录每题的编号、可答／需澄清／证据不足、理由。",
        "可答题写下预期结果、可能错因和支持结论的原句。需澄清题写缺少的条件。证据不足题写缺少哪类依据。",
        "提交题号与标注即可。遇到歧义可标出两种理解。",
        "",
    ]
    cases = list(cases)
    Random(20261119).shuffle(cases)
    for case in cases:
        marker = evidence_labels[case["evidence"]](case["course"])
        title.extend([f"## {case['id']}", "", f"提供的资料：{marker}", ""])
        question = case["input"]
        if isinstance(question, str):
            title.append(question)
        else:
            order = {"inorder": "中序", "preorder": "前序", "postorder": "后序"}
            title.append("遍历方式：" + order.get(question.get("order"), "未给出"))
            if question.get("nodes") is None:
                title.append("树结构：未给出")
            else:
                title.extend(["", "| 节点 | 左孩子 | 右孩子 |", "| --- | --- | --- |"])
                for node in question["nodes"]:
                    title.append(f"| {node['id']} | {node.get('left') or '无'} | {node.get('right') or '无'} |")
            title.append("")
            title.append("学生序列：" + (" → ".join(question.get("answer", [])) or "未给出"))
        title.append("")

    title.extend(["## 可用资料", ""])
    for course, label in labels.items():
        name = "Cache 地址拆分" if course == "cache" else "二叉树递归遍历"
        excerpt = evidence_for({"evidence": "same_unrelated_section", "course": course}, handouts)[0]["content"]
        content = handouts[course]["content"].strip()
        content = re.sub(r"^(#+) ", r"###\1 ", content, flags=re.MULTILINE)
        title.extend([f"### {label} · {name}讲义", "", content, "", f"### {label}2 · 节选", "", excerpt, ""])
    REVIEWER.write_text("\n".join(title), encoding="utf-8", newline="\n")


def main():
    handouts = documents()
    totals = Counter()
    passes = Counter()
    rows = []
    ids = set()
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines()]
    for case in cases:
        if case["id"] in ids:
            raise ValueError(f"Duplicate case ID {case['id']}")
        ids.add(case["id"])
        hits = evidence_for(case, handouts)
        started = perf_counter()
        actual = execute(case, hits)
        latency_ms = (perf_counter() - started) * 1000
        passed = judge(case, actual, handouts)
        key = case["course"], case["bucket"]
        totals[key] += 1
        passes[key] += passed
        rows.append({
            "id": case["id"], "course": case["course"], "bucket": case["bucket"],
            "evidence_fixture": case["evidence"], "target": case["expects"],
            "actual": actual, "passed": passed, "local_ms": round(latency_ms, 3),
        })

    assert all(totals[(course, bucket)] == count for course in DOCUMENT_NAMES for bucket, count in
               {"answerable": 6, "insufficient_evidence": 3, "missing_parameter": 3}.items())
    RESULTS.parent.mkdir(parents=True, exist_ok=True)
    with RESULTS.open("w", encoding="utf-8", newline="\n") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False) + "\n")
    write_reviewer_sheet(cases, handouts)
    for (course, bucket), count in sorted(totals.items()):
        print(f"{course:5} {bucket:24} {passes[(course, bucket)]}/{count}")
    print(f"Total {sum(passes.values())}/{len(rows)}; local median {median(row['local_ms'] for row in rows):.3f} ms")
    return int(sum(passes.values()) != len(rows))


if __name__ == "__main__":
    raise SystemExit(main())
