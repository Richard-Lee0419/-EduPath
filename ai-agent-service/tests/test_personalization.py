from app.services.personalization import PersonalizationContext


def test_profiles_produce_different_personalization_strategies():
    visual_beginner = PersonalizationContext.from_profile(
        {
            "student_id": "student_visual",
            "learning_goal": "通过期末考试",
            "weak_points": ["递归调用栈"],
            "resource_preference": ["mindmap", "animation_script"],
            "cognitive_style": ["visual", "step_by_step"],
            "knowledge_base": {"algorithm": "beginner"},
            "latest_quiz_score": 42,
            "learning_pace": "每天 30 分钟",
        },
        "medium",
    )
    code_advanced = PersonalizationContext.from_profile(
        {
            "student_id": "student_code",
            "learning_goal": "完成竞赛项目",
            "weak_points": ["最短路径"],
            "resource_preference": ["codelab", "quiz"],
            "cognitive_style": ["example_first"],
            "knowledge_base": {"algorithm": "advanced"},
            "latest_quiz_score": 88,
            "learning_pace": "每天 60 分钟",
        },
        "medium",
    )

    assert visual_beginner.effective_difficulty == "basic"
    assert code_advanced.effective_difficulty == "advanced"
    assert visual_beginner.preferred_resource_types[0] == "mindmap"
    assert code_advanced.preferred_resource_types[0] == "codelab"
    assert visual_beginner.estimated_minutes != code_advanced.estimated_minutes
    assert visual_beginner.fingerprint != code_advanced.fingerprint
    assert visual_beginner.summary != code_advanced.summary


def test_empty_profile_does_not_invent_demo_attributes():
    context = PersonalizationContext.from_profile({}, "medium")

    serialized = str(context)
    assert "二叉树" not in serialized
    assert "Cache" not in serialized
    assert "大二" not in serialized
    assert context.weak_points == []
    assert context.preferences == []
    assert context.effective_difficulty == "medium"
