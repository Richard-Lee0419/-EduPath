"""Deterministic demo fixtures for the Huawei ICT / AgentArts edition.

The fixtures are intentionally small, inspectable and course-grounded.  They are
used when no AgentArts endpoint or EduPath AI service is configured, so that the
demo flow, MCP contract and evaluator can be exercised offline.  When a real
service is configured the bridge proxies to it and marks the source in the
response (``mode``), never silently mixing fixture content with model output.
"""

from __future__ import annotations

from datetime import datetime, timezone

DEMO_STUDENT_ID = "stu-demo-001"

COURSES = [
    {
        "id": 1,
        "code": "data_structures_algorithms",
        "name": "数据结构与算法",
        "knowledge_points": [
            {"id": 9344, "name": "二叉树基础", "difficulty": "basic"},
            {"id": 9345, "name": "二叉树遍历", "difficulty": "basic"},
            {"id": 9308, "name": "深度优先搜索", "difficulty": "basic"},
            {"id": 9307, "name": "广度优先搜索", "difficulty": "basic"},
            {"id": 9346, "name": "二叉搜索树操作", "difficulty": "medium"},
            {"id": 9305, "name": "线段树", "difficulty": "medium"},
        ],
    },
    {
        "id": 2,
        "code": "computer_organization",
        "name": "计算机组成原理",
        "knowledge_points": [
            {"id": 2101, "name": "存储器层次结构", "difficulty": "basic"},
            {"id": 2102, "name": "Cache 映射方式", "difficulty": "medium"},
            {"id": 2103, "name": "指令流水线", "difficulty": "medium"},
            {"id": 2104, "name": "总线与 I/O", "difficulty": "basic"},
        ],
    },
]

DEMO_PROFILE = {
    "student_id": DEMO_STUDENT_ID,
    "display_name": "林同学",
    "major": "计算机科学与技术",
    "grade": "大二",
    "target_courses": [1],
    "learning_goal": "三天内掌握二叉树递归遍历，能独立写出前序、中序、后序代码并解释调用栈",
    "weak_points": ["递归调用栈", "边界条件", "遍历顺序混淆"],
    "knowledge_base": {"数据结构与算法": "beginner", "计算机组成原理": "unknown"},
    "resource_preference": ["图解", "可运行代码", "短练习"],
    "cognitive_style": ["视觉化", "例子驱动"],
    "mistake_patterns": ["空节点未处理", "把中序写成前序"],
    "learning_pace": "每天 30 分钟，连续 3 天",
    "confidence_score": 0.82,
    "version": 3,
    "updated_reason": "上一次小测中“中序/前序混淆”出现 2 次，薄弱点权重上调",
    "updated_at": "2026-10-24T20:10:00+08:00",
}

# Course-grounded evidence.  Excerpts are shortened for the demo; the original
# source and licence are kept so that every generated claim can be traced.
EVIDENCE = [
    {
        "chunk_id": "E1",
        "course_id": 1,
        "knowledge_point_id": 9345,
        "knowledge_point": "二叉树遍历",
        "title": "前序、中序、后序与层序遍历",
        "content": (
            "前序按根—左—右访问，适合复制或序列化；中序在二叉搜索树上产生有序序列；"
            "后序先处理孩子再处理根，适合释放与自底向上计算；层序使用队列按深度展开。"
            "每种完整遍历都访问 n 个节点，时间 O(n)；递归空间由树高决定，退化树最坏 O(n)。"
        ),
        "score": 0.94,
        "source": "OpenDSA / BinaryTreeTraversal.rst",
        "source_url": "https://github.com/OpenDSA/OpenDSA/blob/f4e4afcee2fcc0b47a888ebb5648c8ebb659c53c/RST/en/Binary/BinaryTreeTraversal.rst",
        "license": "MIT",
        "attribution": "Adapted from OpenDSA contributors",
    },
    {
        "chunk_id": "E2",
        "course_id": 1,
        "knowledge_point_id": 9344,
        "knowledge_point": "二叉树基础",
        "title": "二叉树术语、性质与结构分类",
        "content": (
            "二叉树每个节点至多有左右两个孩子。根、叶子、深度、高度、子树是描述结构的基础；"
            "满二叉树、完全二叉树和平衡二叉树约束不同，不能混用。链式节点适合一般形态，"
            "完全二叉树可用数组表示。"
        ),
        "score": 0.89,
        "source": "OpenDSA / BinaryTreeIntro.rst",
        "source_url": "https://github.com/OpenDSA/OpenDSA/blob/f4e4afcee2fcc0b47a888ebb5648c8ebb659c53c/RST/en/Binary/BinaryTreeIntro.rst",
        "license": "MIT",
        "attribution": "Adapted from OpenDSA contributors",
    },
    {
        "chunk_id": "E3",
        "course_id": 1,
        "knowledge_point_id": 9308,
        "knowledge_point": "深度优先搜索",
        "title": "DFS：时间戳、边分类与递归栈",
        "content": (
            "DFS 沿一条路径深入后回溯，适合连通分量、拓扑关系、环检测与树上递归。"
            "三色标记中白色表示未访问、灰色表示仍在递归栈、黑色表示完成；"
            "深图可能导致递归栈溢出，应改为显式栈或提高栈空间。"
        ),
        "score": 0.85,
        "source": "cp-algorithms / depth-first-search.md",
        "source_url": "https://github.com/cp-algorithms/cp-algorithms/blob/4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b/src/graph/depth-first-search.md",
        "license": "CC-BY-SA-4.0",
        "attribution": "Adapted from cp-algorithms contributors",
    },
    {
        "chunk_id": "E4",
        "course_id": 1,
        "knowledge_point_id": 9307,
        "knowledge_point": "广度优先搜索",
        "title": "BFS：分层遍历与无权最短路",
        "content": (
            "BFS 从起点按距离分层扩展，队列保证先发现的节点具有更短的边数距离。"
            "邻接表实现为 O(V+E)；记录 parent 可恢复路径。"
        ),
        "score": 0.71,
        "source": "cp-algorithms / breadth-first-search.md",
        "source_url": "https://github.com/cp-algorithms/cp-algorithms/blob/4eba0d6abffca2dfbb41e4cd74a8bb9e453fb42b/src/graph/breadth-first-search.md",
        "license": "CC-BY-SA-4.0",
        "attribution": "Adapted from cp-algorithms contributors",
    },
    {
        "chunk_id": "E5",
        "course_id": 1,
        "knowledge_point_id": 9346,
        "knowledge_point": "二叉搜索树操作",
        "title": "BST：查找、插入、删除与退化",
        "content": (
            "BST 保持左子树关键字小于根、右子树大于根的有序不变式。中序遍历会产生有序序列；"
            "按有序数据插入可能退化为 O(n)。"
        ),
        "score": 0.68,
        "source": "OpenDSA / BST.rst",
        "source_url": "https://github.com/OpenDSA/OpenDSA/blob/f4e4afcee2fcc0b47a888ebb5648c8ebb659c53c/RST/en/Binary/BST.rst",
        "license": "MIT",
        "attribution": "Adapted from OpenDSA contributors",
    },
]

LECTURE_TITLE = "二叉树递归遍历：把“调用栈”画出来"
LECTURE_MARKDOWN = """# 二叉树递归遍历：把“调用栈”画出来

> 为林同学定制 · 依据 5 条课程证据 · 建议 12 分钟 · 难度：入门到中等

## 0. 先对齐一个事实

遍历不是“背三种顺序”，而是**决定什么时候访问根节点**。三种深度优先遍历走的是同一棵树的同一条路径，区别只在于“访问根”发生在递归的哪一步 [E1]。

## 1. 一个能记住的类比

把递归想成**叠盘子**：每进入一层函数，就往栈顶放一个盘子；每返回一层，就取走一个盘子。

- 前序：拿到盘子先登记（根→左→右）
- 中序：把左边所有盘子处理完，再登记当前盘子（左→根→右）
- 后序：左右都处理完，最后登记（左→右→根）

递归栈的深度等于树高；退化树最坏是 O(n)，所以极端情况下要改成显式栈 [E3]。

## 2. 递归三要素（写代码前先填这三格）

1. **终止条件**：节点为空时返回。空节点是递归的“出口”，不是异常。
2. **本层做什么**：把当前节点的值加入结果列表。
3. **下一层做什么**：分别递归左子树和右子树。

```python
def inorder(root):
    if root is None:          # 1. 出口
        return []
    return inorder(root.left) + [root.val] + inorder(root.right)
```

上面这段是“拼接版”，便于理解顺序；生产代码通常传一个 `result` 列表，避免反复创建列表。

## 3. 三种遍历的差别（只改一行）

| 遍历 | 访问根的位置 | 典型用途 |
| --- | --- | --- |
| 前序 | 递归左右之前 | 复制、序列化 |
| 中序 | 递归左之后 | BST 有序输出 |
| 后序 | 递归左右之后 | 释放、自底向上聚合 |

> 记忆钩子：**前序是“先登记再下楼”，中序是“走到最左再登记”，后序是“回到顶上才登记”**。

## 4. 你最容易踩的两个坑

- **空节点没处理**：`root is None` 必须是第一行，否则叶子节点的孩子会报错 [E2]。
- **中序写成前序**：把 `result.append` 放在两次递归之间，而不是之前。写完用一棵三节点小树手推一遍。

## 5. 30 秒自测

对树 `1(2,3)`：

- 前序是？中序是？后序是？
- 如果只写 `inorder(root.left)` 却忘了 `root.right`，结果会少哪些节点？

> 把你的答案发给我，我会按“概念—过程—边界”三个维度给你反馈，并把结果写回学习画像。
"""

MINDMAP = {
    "label": "二叉树遍历",
    "note": "5 条证据 · 3 个薄弱点",
    "children": [
        {
            "label": "深度优先 DFS",
            "children": [
                {"label": "前序", "note": "根 → 左 → 右 · 复制/序列化"},
                {"label": "中序", "note": "左 → 根 → 右 · BST 有序"},
                {"label": "后序", "note": "左 → 右 → 根 · 释放/聚合"},
            ],
        },
        {
            "label": "广度优先 BFS",
            "children": [
                {"label": "层序", "note": "队列 · 按深度展开"},
                {"label": "适用", "note": "无权最短路 / 分层输出"},
            ],
        },
        {
            "label": "递归三要素",
            "children": [
                {"label": "终止条件", "note": "空节点返回"},
                {"label": "本层动作", "note": "访问根"},
                {"label": "下一层", "note": "左、右子树"},
            ],
        },
        {
            "label": "边界与复杂度",
            "children": [
                {"label": "时间", "note": "O(n)"},
                {"label": "空间", "note": "O(h)，退化 O(n)"},
                {"label": "易错", "note": "空节点 / 顺序混淆"},
            ],
        },
    ],
}

QUIZ = {
    "title": "二叉树遍历 · 3 题查漏",
    "questions": [
        {
            "id": 1,
            "type": "single_choice",
            "difficulty": "basic",
            "knowledge_point": "二叉树遍历",
            "stem": "对二叉树 1(左 2, 右 3) 执行前序遍历，输出是？",
            "options": ["2,1,3", "1,2,3", "2,3,1", "1,3,2"],
            "answer": "B",
            "explanation": "前序是“根—左—右”，先访问 1，再访问左子树 2，最后右子树 3。",
            "evidence_chunk_ids": ["E1"],
        },
        {
            "id": 2,
            "type": "single_choice",
            "difficulty": "medium",
            "knowledge_point": "二叉树遍历",
            "stem": "下列哪种遍历在二叉搜索树上会得到递增序列？",
            "options": ["前序", "中序", "后序", "层序"],
            "answer": "B",
            "explanation": "BST 的左子树小于根、右子树大于根，中序“左—根—右”正好得到有序序列 [E5]。",
            "evidence_chunk_ids": ["E1", "E5"],
        },
        {
            "id": 3,
            "type": "single_choice",
            "difficulty": "medium",
            "knowledge_point": "递归与边界",
            "stem": "递归遍历函数的第一行通常要判断 root 是否为空，主要原因是？",
            "options": [
                "提高遍历速度",
                "避免访问空节点的孩子并形成终止条件",
                "让输出变成有序",
                "减少内存占用到 O(1)",
            ],
            "answer": "B",
            "explanation": "空节点判断是递归出口；缺少它会在叶子节点的孩子上继续访问而报错，深度优先的递归空间由树高决定 [E2][E3]。",
            "evidence_chunk_ids": ["E2", "E3"],
        },
    ],
}

PATH = {
    "path_id": "path-demo-001",
    "path_title": "三天补齐二叉树递归遍历",
    "target": DEMO_PROFILE["learning_goal"],
    "course_ids": [1],
    "daily_minutes": 30,
    "personalization_summary": "先补调用栈直觉，再用三种遍历对比，最后用边界题验收；每天 30 分钟，避免一次灌输。",
    "adjustment_strategy": "若小测正确率低于 60%，第二天插入“空节点与递归出口”补救任务；高于 80% 则提前进入 BST 应用。",
    "days": [
        {
            "day": 1,
            "theme": "把递归调用栈画出来",
            "difficulty": "basic",
            "reason": "你的薄弱点集中在“递归调用栈”，先建立可视图象，再写代码。",
            "expected_outcome": "能用手推图解释一次完整递归的入栈/出栈过程。",
            "tasks": [
                {"type": "lecture", "title": "阅读定制讲义：递归三要素", "minutes": 12},
                {"type": "mindmap", "title": "用思维导图复述三种遍历", "minutes": 8},
                {"type": "quiz", "title": "完成 3 题查漏", "minutes": 10},
            ],
            "evidence_chunk_ids": ["E1", "E3"],
        },
        {
            "day": 2,
            "theme": "三种遍历只差一行",
            "difficulty": "medium",
            "reason": "针对“中序/前序混淆”，用同一棵树对比三种访问时机。",
            "expected_outcome": "能不查资料写出前、中、后序，并说出各自适用场景。",
            "tasks": [
                {"type": "codelab", "title": "改写 inorder，对比三序输出", "minutes": 15},
                {"type": "quiz", "title": "BST 中序性质练习", "minutes": 10},
            ],
            "evidence_chunk_ids": ["E1", "E5"],
        },
        {
            "day": 3,
            "theme": "边界与迁移",
            "difficulty": "medium",
            "reason": "把“空节点/退化树”作为边界条件专项，避免只会背模板。",
            "expected_outcome": "能解释递归空间复杂度，并说出何时改用显式栈。",
            "tasks": [
                {"type": "reading", "title": "DFS 递归栈与退化树", "minutes": 10},
                {"type": "quiz", "title": "边界条件 3 题", "minutes": 10},
                {"type": "flowchart", "title": "画出递归出口判断流程", "minutes": 10},
            ],
            "evidence_chunk_ids": ["E2", "E3"],
        },
    ],
}

QUALITY = {
    "evaluator_version": "edupath-resource-quality-v1",
    "total_score": 88.0,
    "grade": "A",
    "gate_passed": True,
    "dimensions": {
        "课程一致性": {"score": 92, "weight": 0.30, "passed": True, "findings": ["5/5 段落有证据引用"]},
        "学情匹配": {"score": 90, "weight": 0.25, "passed": True, "findings": ["命中 3 个薄弱点中的 3 个"]},
        "结构完整性": {"score": 86, "weight": 0.20, "passed": True, "findings": ["含先修、例题、易错点、自测"]},
        "可练习性": {"score": 84, "weight": 0.15, "passed": True, "findings": ["3 道题均带解析与证据"]},
        "表达清晰度": {"score": 86, "weight": 0.10, "passed": True, "findings": ["类比与代码块搭配"]},
    },
    "issues": [],
    "recommendations": ["可补充一张三序对比图，降低纯文字阅读负担"],
}

SAFETY = {
    "passed": True,
    "risk_level": "low",
    "issues": [],
    "suggestions": [],
    "confidence": 0.96,
    "checks": [
        {"name": "学术诚信", "result": "pass", "detail": "未提供可直接提交的作业答案，练习附解析"},
        {"name": "事实性", "result": "pass", "detail": "关键结论均关联课程证据"},
        {"name": "隐私", "result": "pass", "detail": "仅使用脱敏演示画像"},
        {"name": "价值观", "result": "pass", "detail": "未发现不当内容"},
    ],
}

EVALUATION = {
    "overall_score": 67,
    "mastery": [
        {"knowledge_point": "二叉树遍历", "mastery_score": 67, "level": "一般", "correct_count": 2, "total_count": 3},
    ],
    "weak_points": ["递归出口", "中序访问时机"],
    "mistake_patterns": ["boundary_condition_missing"],
    "next_actions": ["重做第 3 题并口述递归出口", "用三节点树手推中序"],
    "evidence_chunk_ids": ["E1", "E2"],
    "summary": "概念已经建立，但边界条件仍不稳定；建议第二天插入 10 分钟补救任务，而不是直接进入 BST。",
}

TRACE_STEPS = [
    {
        "id": "intent",
        "agent": "主控 Agent",
        "name": "任务理解与路由",
        "tool": "intent.classify",
        "detail": "识别为“课程学习 + 资源生成”，选择 resource_learning 工作流",
        "duration_ms": 420,
    },
    {
        "id": "profile",
        "agent": "画像 Agent",
        "name": "读取版本化学习画像",
        "tool": "edupath_get_profile",
        "detail": "画像 v3：3 个薄弱点、每天 30 分钟、偏好图解",
        "duration_ms": 310,
    },
    {
        "id": "retrieve",
        "agent": "检索 Agent",
        "name": "课程知识库取证",
        "tool": "edupath_search_knowledge",
        "detail": "命中 5 条证据，最高分 0.94，课程隔离通过",
        "duration_ms": 760,
    },
    {
        "id": "plan",
        "agent": "教学设计 Agent",
        "name": "资源组合与难度决策",
        "tool": "edupath_plan_resource_bundle",
        "detail": "讲义 + 思维导图 + 3 题；难度 basic→medium",
        "duration_ms": 480,
    },
    {
        "id": "generate",
        "agent": "生成 Agent",
        "name": "并行生成三类资源",
        "tool": "edupath_generate_learning_resource",
        "detail": "1 份讲义、1 张导图、3 道题，全部带引用",
        "duration_ms": 2140,
    },
    {
        "id": "tutor",
        "agent": "辅导 Agent",
        "name": "补充调用栈类比",
        "tool": "edupath_answer_with_evidence",
        "detail": "用“叠盘子”类比解释入栈/出栈，引用 E3",
        "duration_ms": 1180,
    },
    {
        "id": "evaluate",
        "agent": "评估 Agent",
        "name": "质量门禁",
        "tool": "edupath_evaluate_resource",
        "detail": "88/100，A 级，5 个维度全部通过",
        "duration_ms": 640,
    },
    {
        "id": "safety",
        "agent": "安全 Agent",
        "name": "内容与隐私审核",
        "tool": "edupath_review_safety",
        "detail": "低风险，4 项检查通过，可发布",
        "duration_ms": 330,
    },
]

PLATFORM_AGENTS = [
    {"name": "知径学伴", "role": "主控 / Router", "model": "DeepSeek-V4-Flash", "temperature": 0.2},
    {"name": "画像师", "role": "Profile", "model": "DeepSeek-V4-Flash", "temperature": 0.1},
    {"name": "检索员", "role": "Retriever", "model": "pangu_embedding + pangu_rerank", "temperature": 0.0},
    {"name": "教学设计", "role": "Planner", "model": "DeepSeek-V4-Flash", "temperature": 0.3},
    {"name": "内容生成", "role": "Generator", "model": "DeepSeek-V4-Flash", "temperature": 0.4},
    {"name": "辅导教练", "role": "Tutor", "model": "DeepSeek-V4-Flash", "temperature": 0.3},
    {"name": "评估师", "role": "Evaluator", "model": "DeepSeek-V4-Flash", "temperature": 0.0},
    {"name": "安全员", "role": "Safety", "model": "DeepSeek-V4-Flash", "temperature": 0.0},
]


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def course_by_id(course_id: int) -> dict:
    for course in COURSES:
        if course["id"] == course_id:
            return course
    return COURSES[0]
