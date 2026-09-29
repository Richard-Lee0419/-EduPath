import re


class ProfileAgent:
    name = "ProfileAgent"

    _weak_keywords = {
        "递归": "递归调用栈",
        "二叉树": "二叉树递归遍历",
        "cache": "Cache 映射方式",
        "Cache": "Cache 映射方式",
        "缓存": "Cache 映射方式",
        "图": "图的遍历",
        "最短路径": "最短路径",
    }
    _preference_keywords = {
        "图": "mindmap",
        "可视": "mindmap",
        "代码": "codelab",
        "实验": "codelab",
        "题": "quiz",
        "练习": "quiz",
        "动画": "animation_script",
    }

    def extract(self, student_id: str, message: str, course_ids: list[int]) -> dict[str, object]:
        weak_points = self._extract_values(message, self._weak_keywords)
        preferences = self._extract_values(message, self._preference_keywords)
        cognitive_style: list[str] = []
        if "mindmap" in preferences:
            cognitive_style.append("visual")
        if "codelab" in preferences:
            cognitive_style.append("example_first")
        if any(keyword in message for keyword in ("一步一步", "分步骤", "详细步骤")):
            cognitive_style.append("step_by_step")
        evidence_count = len(weak_points) + len(preferences) + len(cognitive_style)
        return {
            "student_id": student_id,
            "major": self._major(message),
            "grade": self._grade(message),
            "target_courses": course_ids,
            "knowledge_base": {
                "programming": self._level(message, ("代码", "编程", "实验")),
                "math": self._level(message, ("数学", "离散")),
                "digital_logic": self._level(message, ("数电", "数字逻辑")),
                "algorithm": self._level(message, ("算法", "数据结构", "递归", "二叉树", "图")),
                "computer_organization": self._level(message, ("组成原理", "Cache", "缓存", "指令")),
            },
            "course_progress": [
                {"course_id": course_id, "status": "weak" if weak_points else "unknown"} for course_id in course_ids
            ],
            "learning_goal": message.strip() or "掌握课程核心知识点",
            "weak_points": weak_points,
            "resource_preference": preferences,
            "cognitive_style": cognitive_style,
            "mistake_patterns": [],
            "learning_pace": self._pace(message),
            "confidence_score": min(0.92, 0.55 + evidence_count * 0.06),
            "updated_reason": "ProfileAgent 根据对话抽取学习目标、薄弱点和资源偏好",
        }

    def _extract_values(self, message: str, mapping: dict[str, str]) -> list[str]:
        values: list[str] = []
        for keyword, value in mapping.items():
            if keyword in message and value not in values:
                values.append(value)
        return values

    def _major(self, message: str) -> str:
        for major in ("计算机科学与技术", "软件工程", "人工智能", "网络工程", "物联网工程"):
            if major in message:
                return major
        return ""

    def _grade(self, message: str) -> str:
        match = re.search(r"(大[一二三四]|研[一二三])", message)
        return match.group(1) if match else ""

    def _pace(self, message: str) -> str:
        match = re.search(r"(?:每天|每日)\s*(\d{1,3})\s*分钟", message)
        return f"每天 {match.group(1)} 分钟" if match else ""

    def _level(self, message: str, topics: tuple[str, ...]) -> str:
        if not any(topic in message for topic in topics):
            return "unknown"
        if any(word in message for word in ("很差", "薄弱", "不会", "不熟", "零基础")):
            return "beginner"
        if any(word in message for word in ("熟练", "高级", "竞赛", "深入")):
            return "advanced"
        return "medium"
