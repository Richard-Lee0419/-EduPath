from app.rag.retriever import RagEvidence
from app.schemas.resource_schema import GeneratedResource
from app.schemas.quiz_schema import QuizDraft, QuizQuestionDraft
from app.services.personalization import PersonalizationContext


class QuizAgent:
    name = "QuizAgent"

    def generate(
        self,
        topic: str,
        difficulty: str,
        knowledge_points: list[str],
        evidence: list[RagEvidence],
        personalization: PersonalizationContext,
    ) -> GeneratedResource:
        questions = self.questions(topic, evidence, personalization)
        content = "## " + topic + "分层练习题\n\n> " + personalization.learning_instruction() + "。\n\n" + "\n\n".join(questions)
        return GeneratedResource(
            title=f"{topic}分层练习题",
            resource_type="quiz",
            content_format="markdown",
            content=content,
            summary=personalization.reason_for("quiz", topic),
            difficulty=difficulty,
            knowledge_points=knowledge_points,
            personalized_reason=personalization.reason_for("quiz", topic),
            estimated_minutes=personalization.estimated_minutes,
            profile_fingerprint=personalization.fingerprint,
        )

    def questions(
        self, topic: str, evidence: list[RagEvidence], personalization: PersonalizationContext
    ) -> list[str]:
        source = evidence[0] if evidence else None
        source_title = source.title if source else topic
        return [
            (
                f"### 基础题\n"
                f"问题：{topic} 的核心判断依据是什么？\n\n"
                "A. 只看最终答案  B. 依据定义和执行规则  C. 忽略边界条件  D. 随机选择\n\n"
                "答案：B\n\n"
                f"解析：依据 `{source_title}` 的证据，必须先确认定义、规则和边界条件。"
            ),
            (
                "### 迁移题\n"
                f"问题：当输入规模变大时，学习 {topic} 应优先追踪哪类状态变化？\n\n"
                "答案：追踪每一步的中间状态、返回时机或地址字段变化。"
            ),
            (
                "### 综合题\n"
                f"问题：设计一个最小反例，说明 {topic} 中"
                f"{('、'.join(personalization.mistake_patterns[:2]) or '常见误解')}为什么会导致错误结果。\n\n"
                "答案要点：给出边界样例，标出错误规则，再用正确规则重新推演。"
            ),
        ]

    def generate_quiz(
        self,
        topic: str,
        difficulty: str,
        question_count: int,
        evidence: list[RagEvidence],
    ) -> QuizDraft:
        questions: list[QuizQuestionDraft] = []
        for index in range(question_count):
            item = evidence[index % len(evidence)]
            key_sentence = item.content.strip().split("。", 1)[0].strip()
            if len(key_sentence) < 8:
                key_sentence = f"应依据 {item.knowledge_point} 的定义、过程和边界条件作答"
            questions.append(
                QuizQuestionDraft(
                    question_order=index + 1,
                    difficulty=difficulty,
                    knowledge_point=item.knowledge_point,
                    stem=f"根据课程材料，关于 {item.knowledge_point} 的下列说法哪项正确？",
                    options=[
                        key_sentence,
                        "只需记忆最终结论，不必检查推导过程",
                        "所有输入都可以忽略边界条件",
                        "可以脱离课程定义随机选择处理步骤",
                    ],
                    answer="A",
                    explanation=f"课程证据《{item.title}》直接给出了相关定义或过程，应以该证据为准。",
                    evidence_chunk_ids=[item.chunk_id],
                )
            )
        return QuizDraft(title=f"{topic} · 3 题以内专项小测", questions=questions)
