from collections import defaultdict

from app.schemas.evaluation_schema import EvaluationReport, QuizQuestionResult


class EvaluationAgent:
    name = "EvaluationAgent"

    def report(self) -> dict[str, object]:
        return {
            "report_id": "ai_eval_local",
            "overall_score": 76,
            "mastery": [
                {"knowledge_point": "递归调用栈", "mastery_score": 58, "level": "一般"},
                {"knowledge_point": "二叉树前序遍历", "mastery_score": 84, "level": "良好"},
                {"knowledge_point": "Cache 直接映射", "mastery_score": 63, "level": "待巩固"},
            ],
            "weak_points": ["递归调用栈", "Cache 直接映射"],
            "mistake_patterns": ["concept_confusion", "process_gap"],
            "next_actions": ["补充 RAG 证据资源", "生成补救学习路径", "安排一轮错题复盘"],
        }

    def analyze(
        self,
        score: int,
        question_results: list[QuizQuestionResult],
        evidence_chunk_ids: list[str],
    ) -> EvaluationReport:
        counters: dict[str, list[int]] = defaultdict(lambda: [0, 0])
        for item in question_results:
            counters[item.knowledge_point][1] += 1
            if item.correct:
                counters[item.knowledge_point][0] += 1

        mastery = []
        weak_points = []
        for knowledge_point, (correct_count, total_count) in counters.items():
            mastery_score = round(correct_count * 100 / total_count)
            if mastery_score < 70:
                weak_points.append(knowledge_point)
            mastery.append(
                {
                    "knowledge_point": knowledge_point,
                    "mastery_score": mastery_score,
                    "level": _level(mastery_score),
                    "correct_count": correct_count,
                    "total_count": total_count,
                }
            )
        return EvaluationReport(
            overall_score=score,
            mastery=mastery,
            weak_points=weak_points,
            mistake_patterns=[] if not weak_points else ["concept_confusion"],
            next_actions=(
                ["继续完成一道迁移练习并保持当前节奏"]
                if not weak_points
                else [f"复习 {weak_points[0]} 的课程证据并完成错题复盘"]
            ),
            evidence_chunk_ids=evidence_chunk_ids,
        )


def _level(score: int) -> str:
    if score >= 85:
        return "优秀"
    if score >= 70:
        return "良好"
    if score >= 50:
        return "一般"
    return "待补救"
