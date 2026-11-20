"""Deterministic AgentArts code-node implementation for Cache study drills."""

import json
import re


DOCUMENT = "cache-address-diagnosis"
EVIDENCE_MARKER = "EDUPATH-CACHE-ADDR-V1"
SEARCH_QUERY = "Cache 地址字段 地址拆分 组相联 总块数 组数 Index Offset Tag"

FIELD_PATTERNS = {
    "address_bits": r"(\d{1,3})\s*位\s*(?:物理|主存)?地址|地址(?:宽度)?\s*(?:为|是|[:：])?\s*(\d{1,3})\s*位",
    "capacity_bytes": r"(?:cache\s*)?(?:数据)?容量\s*(?:为|是|[:：])?\s*(\d{1,9})\s*(KiB|MiB|字节|B)(?![a-zA-Z])",
    "block_bytes": r"(?:块大小|块长|块容量|cache\s*行大小)\s*(?:为|是|[:：])?\s*(\d{1,9})\s*(KiB|MiB|字节|B)(?![a-zA-Z])",
}
UNITS = {"b": 1, "字节": 1, "kib": 1024, "mib": 1024 * 1024}
WAYS = {"一": 1, "二": 2, "两": 2, "四": 4, "八": 8, "十六": 16}
FIELDS = ("Tag", "Index", "Offset")
LABELS = {
    "address_bits": "地址宽度",
    "capacity_bytes": "Cache 数据容量",
    "block_bytes": "块大小",
    "associativity": "相联度（直接映射、全相联或具体路数）",
    "addressing": "寻址单位（是否按字节寻址）",
}


def _unique_value(values):
    values = set(values)
    if len(values) > 1:
        raise ValueError("题干包含互相冲突的参数，请指定要核对的一组。")
    return next(iter(values), None)


def _read_parameters(query):
    # Isolate the problem statement from a student's calculation or explanation.
    stem = re.split(r"我填|我的答案|我的算法|我算|我把|学生作答|学生的答案", query, maxsplit=1)[0]
    params = {}
    for field, pattern in FIELD_PATTERNS.items():
        matches = re.findall(pattern, stem, flags=re.IGNORECASE)
        if field == "address_bits":
            params[field] = _unique_value(int(left or right) for left, right in matches)
        else:
            params[field] = _unique_value(int(amount) * UNITS[unit.lower()] for amount, unit in matches)
    ways = [
        int(value) if value.isdecimal() else WAYS[value]
        for value in re.findall(r"(\d{1,6}|十六|一|二|两|四|八)\s*路(?:组)?相联", stem)
    ]
    if "直接映射" in stem:
        ways.append(1)
    if "全相联" in stem:
        ways.append("fully_associative")
    params["associativity"] = _unique_value(ways)
    if re.search(r"按(?:字|位)寻址|字寻址", stem):
        raise ValueError("此计算工具使用字节寻址，请提供按字节寻址的参数。")
    params["addressing"] = "byte" if re.search(r"字节寻址|按字节编址", stem) else None
    return params


def _geometry(params):
    width = params["address_bits"]
    capacity = params["capacity_bytes"]
    block = params["block_bytes"]
    ways = params["associativity"]
    if not 0 < width <= 128 or not 0 < block <= capacity or capacity % block:
        raise ValueError("请核对地址宽度和容量。数据容量应为正，且能整除块大小。")
    lines = capacity // block
    ways = lines if ways == "fully_associative" else ways
    if ways < 1 or lines % ways:
        raise ValueError("总块数必须能整除相联度。")
    sets = lines // ways
    if block & (block - 1) or sets & (sets - 1):
        raise ValueError("本题的按位拆分要求块大小和组数均为 2 的幂。")
    offset = block.bit_length() - 1
    index = sets.bit_length() - 1
    tag = width - index - offset
    if tag < 0:
        raise ValueError("给定的地址宽度不足以表示这组索引与偏移。")
    return {"sets": sets, "lines": lines, "ways": ways, "block": block, "width": width,
            "bits": dict(zip(FIELDS, (tag, index, offset)))}


def _student_bits(query):
    bits = {}
    for field in FIELDS:
        # Accept a value only where the student has paired it with a field name.
        found = re.findall(rf"\b{field}\s*(?:为|是|[:：=])?\s*([+-]?\d{{1,3}})\s*(?:位|bits?\b)",
                           query, flags=re.IGNORECASE)
        bits[field] = _unique_value(int(value) for value in found)
    return bits


def prepare(query):
    if not isinstance(query, str) or not query.strip():
        return SEARCH_QUERY, {"decision": "clarify", "reason": "请提供要检验的 Cache 地址拆分题。"}
    if not re.search(r"(?<![a-zA-Z])cache(?![a-zA-Z])|高速缓存", query, flags=re.IGNORECASE):
        return query, {"decision": "refuse"}
    try:
        params = _read_parameters(query)
        if params["address_bits"] is None and params["capacity_bytes"] is None and params["block_bytes"] is None:
            if not re.search(r"Tag|Index|Offset|位数|算出|核对", query, flags=re.IGNORECASE):
                return SEARCH_QUERY, {"decision": "concept"}
        missing = [LABELS[key] for key, value in params.items() if value is None]
        if missing:
            return SEARCH_QUERY, {"decision": "clarify", "reason": "请补充" + "、".join(missing) + "，再计算各字段。"}
        geometry = _geometry(params)
        supplied = _student_bits(query)
    except ValueError as issue:
        return SEARCH_QUERY, {"decision": "clarify", "reason": str(issue)}
    wrong = [field for field, value in supplied.items() if value is not None and value != geometry["bits"][field]]
    decision = "diagnose" if wrong else "confirm" if all(value is not None for value in supplied.values()) else "explain"
    mistake = ""
    if "Index" in wrong and "Offset" not in wrong:
        uses_line_count = supplied["Index"] == geometry["lines"].bit_length() - 1 and geometry["ways"] > 1
        mistake = "index_ignores_associativity" if uses_line_count else "index_bit_count"
    elif "Offset" in wrong and "Index" not in wrong:
        mistake = "offset_bit_count"
    elif wrong:
        mistake = "tag_or_multiple_fields"
    return SEARCH_QUERY, {"decision": decision, "geometry": geometry, "wrong": wrong, "mistake": mistake}


def _citation(hits):
    if not isinstance(hits, list):
        return None
    for hit in hits:
        if not isinstance(hit, dict):
            continue
        name = hit.get("document_name")
        content = hit.get("content")
        if name not in (DOCUMENT, DOCUMENT + ".md") or not isinstance(content, str):
            continue
        if EVIDENCE_MARKER not in content:
            continue
        normalized = " ".join(content.split())
        excerpt = re.search(r"总块数为 C ÷ B，组数 S 为 C ÷ \(B × W\)。.{0,180}?Tag = A − Index − Offset。", normalized)
        if excerpt is None:
            continue
        return {"marker": EVIDENCE_MARKER, "document": name, "section": "地址字段计算", "excerpt": excerpt.group(),
                "file_id": hit.get("file_id") or "", "knowledge_base_id": hit.get("knowledge_base_id") or "",
                "scope": "public_course", "source_version": "v1"}
    return None


def _response(decision, answer, mistake="", citation=None):
    return {"decision": decision, "answer": answer, "mistake": mistake,
            "citations_json": json.dumps([citation] if citation else [], ensure_ascii=False)}


def complete(state, hits):
    decision = state.get("decision")
    if decision == "clarify":
        return _response("clarify", state["reason"])
    if decision == "refuse":
        return _response("refuse", "本工作流用于 Cache 地址拆分。本课程资料范围内没有可用于该问题的证据。")
    if decision not in ("confirm", "diagnose", "explain", "concept"):
        return _response("tool_error", "计算节点返回异常，请重试。")
    citation = _citation(hits)
    if citation is None:
        return _response("refuse", "本次检索没有找到足以支撑 Cache 地址拆分的指定讲义片段。")
    source = (f"\n\n依据 [E1]《Cache 地址拆分与错因诊断》· {citation['section']}（{EVIDENCE_MARKER}）。"
              f"摘录「{citation['excerpt']}」")
    if decision == "concept":
        return _response("concept", "直接映射每组只有一块。组相联每组有多块。全相联只有一组。"
                         "按字节寻址且块大小与组数为 2 的幂时，块大小决定 Offset，组数决定 Index，其余地址位是 Tag。"
                         + source, citation=citation)
    result = state["geometry"]
    bits = result["bits"]
    breakdown = (
        f"总块数 {result['lines']}，每组 {result['ways']} 块，组数 {result['sets']}。"
        f"{result['block']} B 块内有 {result['block']} 个位置，Offset {bits['Offset']} 位，"
        f"Index {bits['Index']} 位，Tag {result['width']} − {bits['Index']} − {bits['Offset']} = {bits['Tag']} 位。"
        f"按 Tag / Index / Offset 顺序为 {bits['Tag']} / {bits['Index']} / {bits['Offset']}。"
    )
    mistake = state["mistake"]
    if decision == "confirm":
        prefix = "三个字段均正确。"
        practice = ""
    elif decision == "diagnose":
        prefix = "需核对的字段是 " + "、".join(state["wrong"]) + "。"
        practice = {
            "index_ignores_associativity": "\n\n你的 Index 与把总块数直接当成组数的结果一致。"
                "补救练习：保持容量和块大小，改成二路相联，先写组数，再求 Index。",
            "index_bit_count": "\n\n补救练习：先用总块数除以每组块数，再写出组号范围和位数。",
            "offset_bit_count": "\n\n补救练习：改为 32 B 块，先写最后一个字节的偏移，再求最少需要几位。",
            "tag_or_multiple_fields": "\n\n补救练习：先列出 Offset 与 Index，再从地址总宽度扣除这两项。",
        }[mistake]
    else:
        prefix = ""
        practice = ""
    return _response(decision, prefix + breakdown + practice + source, mistake, citation)

def main(args):
    _, state = prepare(args.get("query"))
    result = complete(state, args.get("output_list"))
    citations = json.loads(result["citations_json"])
    output = {
        "decision": result["decision"],
        "mistake": result["mistake"],
        "explanation": result["answer"].partition("\n\n依据 [E1]")[0],
        "citation": citations[0] if citations else None,
    }
    return {"answer": result["answer"], "result_json": json.dumps(output, ensure_ascii=False)}
