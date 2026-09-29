import re


_DIRECT_IDENTIFIER_PATTERNS = (
    r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}",
    r"(?<!\d)1[3-9]\d{9}(?!\d)",
    r"(?<!\d)\d{17}[\dXx](?!\d)",
    r"(?:我叫|我的名字是|姓名(?:是|为))\s*[\u4e00-\u9fff·]{2,10}",
)


def deidentify_text(value: object) -> str:
    text = str(value or "").strip()
    for pattern in _DIRECT_IDENTIFIER_PATTERNS:
        text = re.sub(pattern, "", text)
    return re.sub(r"\s+", " ", text).strip(" ，,；;")


def contains_direct_identifier(value: object) -> bool:
    text = str(value or "")
    return any(re.search(pattern, text) for pattern in _DIRECT_IDENTIFIER_PATTERNS)
