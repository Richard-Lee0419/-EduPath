from app.rag.retriever import RagEvidence
from app.schemas.resource_schema import GeneratedResource
from app.services.personalization import PersonalizationContext


class AnimationScriptAgent:
    name = "AnimationScriptAgent"

    def generate(
        self,
        topic: str,
        difficulty: str,
        knowledge_points: list[str],
        evidence: list[RagEvidence],
        personalization: PersonalizationContext,
    ) -> GeneratedResource:
        scenes = [
            (
                "镜头 1｜认识问题",
                "0–8 秒",
                f"画面：屏幕中央出现“{topic}”，旁边摆出待处理的输入对象；目标词逐字亮起。",
                "旁白：今天我们用一个小例子，看清楚这个知识点到底解决什么问题。先别急着记结论，跟着画面一步一步观察。",
                "屏幕文字：本节目标：看懂过程、说出每一步为什么发生。",
            ),
            (
                "镜头 2｜准备开始",
                "8–18 秒",
                "画面：把起点、已知条件和终点用不同颜色标出；镜头从整体缩放到第一个待处理对象。",
                "旁白：先确认从哪里开始、要记录什么。接下来每一次变化都会留下痕迹，我们可以随时回看。",
                "屏幕文字：先确定起点，再记录当前状态。",
            ),
            (
                "镜头 3｜核心步骤",
                "18–38 秒",
                "画面：指针沿着当前关系移动；当前节点放大，已经处理的部分变为浅色，下一步候选对象闪烁。",
                "旁白：现在只做一件事：选择当前规则允许的下一步。看清楚，指针移动之前，当前状态先被记录下来。",
                "屏幕文字：观察 → 选择 → 记录状态。",
            ),
            (
                "镜头 4｜暂停预测",
                "38–50 秒",
                "画面：动画暂停，候选路径保留在画面上，出现“你认为下一步会怎样？”的提问卡片。",
                "旁白：轮到你预测了。暂停三秒，想一想下一步应该访问谁、保留什么信息，或者为什么需要回到上一步。",
                "屏幕文字：暂停预测：下一步会发生什么？",
            ),
            (
                "镜头 5｜状态回放",
                "50–68 秒",
                "画面：以时间轴回放刚才的变化；访问顺序、临时状态和返回位置同步出现，关键节点用描边强调。",
                "旁白：把刚才的过程连起来看，答案不只在最后一步，而在每次状态变化和返回时机里。",
                "屏幕文字：过程 = 状态变化 + 返回时机。",
            ),
            (
                "镜头 6｜带走方法",
                "68–80 秒",
                "画面：画面收束为三步卡片：确定起点、执行规则、检查边界；右下角出现“再练一题”按钮。",
                "旁白：记住这三个动作，再换一个输入重新走一遍。能解释每一步，比背下最终顺序更重要。",
                "屏幕文字：确定起点 → 执行规则 → 检查边界。",
            ),
        ]
        if "dfs" in topic.lower() or "深度优先" in topic:
            scenes = [
                (
                    "镜头 1｜从起点出发",
                    "0–8 秒",
                    f"画面：绘制一个小型无向图，起点 {topic} 的首个节点以绿色脉冲高亮，其他节点保持浅色。",
                    "旁白：我们从绿色起点出发。深度优先搜索会沿着一条路尽量走深，再回头寻找下一条路。",
                    "屏幕文字：从起点出发，沿一条路径走深。",
                ),
                (
                    "镜头 2｜进入递归",
                    "8–20 秒",
                    "画面：当前节点被放大，右侧递归栈从底部压入节点编号；已访问节点出现勾选标记。",
                    "旁白：访问一个节点时，先把它记为已访问，再把它压入递归栈，准备继续探索相邻节点。",
                    "屏幕文字：访问节点 → 标记已访问 → 入栈。",
                ),
                (
                    "镜头 3｜沿边深入",
                    "20–36 秒",
                    "画面：一条边变成亮色，指针沿边移动到下一个未访问节点；栈顶和遍历序号同步更新。",
                    "旁白：还有未访问的相邻节点，就沿这条边继续深入。注意看，遍历顺序和栈顶变化是同步的。",
                    "屏幕文字：选择未访问邻居，继续深入。",
                ),
                (
                    "镜头 4｜暂停预测",
                    "36–50 秒",
                    "画面：动画暂停，当前栈和两个候选邻居停在画面上，弹出“下一步访问谁？”的选择卡片。",
                    "旁白：暂停三秒，预测下一步。如果当前节点没有未访问邻居，算法会怎样做？",
                    "屏幕文字：暂停预测：继续深入，还是开始回溯？",
                ),
                (
                    "镜头 5｜回溯与出栈",
                    "50–68 秒",
                    "画面：当前节点的邻居全部处理后，栈顶节点向上弹出，指针退回上一个节点；回溯路径用橙色短暂标记。",
                    "旁白：当一条路走到尽头，就从栈顶出栈并回到上一个节点，再检查是否还有新的方向。",
                    "屏幕文字：无路可走 → 出栈 → 回到上一步。",
                ),
                (
                    "镜头 6｜完整回放",
                    "68–82 秒",
                    "画面：时间轴回放访问序列、递归栈和回溯路径，最后显示完整遍历顺序。",
                    "旁白：把访问、入栈、深入、回溯和出栈连起来，深度优先搜索的完整过程就清楚了。",
                    "屏幕文字：访问顺序：按时间轴逐步核对。",
                ),
            ]
        scene_text = "\n\n".join(
            f"### {name}（{duration}）\n- {visual}\n- {narration}\n- {on_screen}"
            for name, duration, visual, narration, on_screen in scenes
        )
        content = (
            f"## {topic}｜学生学习动画脚本\n\n"
            f"**适用方式：** 建议边看边暂停，在第 4 个镜头先说出自己的预测。\n\n"
            f"**本次学习重点：** {personalization.reason_for('animation_script', topic)}\n\n"
            "### 分镜脚本\n\n"
            f"{scene_text}\n\n"
            "### 片尾互动\n\n"
            "- 交互提示：支持暂停、回放和逐镜头查看。\n"
            "- 学生任务：重新选择一个输入，口头说明每次状态变化的原因。\n"
            "- 完成标准：能够说出起点、关键步骤和边界条件。\n\n"
            "### 制作依据\n\n"
            "本脚本的知识表述应以当前课程证据片段为准；制作时请在片尾或教师备注中保留对应来源。"
        )
        return GeneratedResource(
            title=f"{topic}动画脚本",
            resource_type="animation_script",
            content_format="markdown",
            content=content,
            summary=personalization.reason_for("animation_script", topic),
            difficulty=difficulty,
            knowledge_points=knowledge_points,
            personalized_reason=personalization.reason_for("animation_script", topic),
            estimated_minutes=personalization.estimated_minutes,
            profile_fingerprint=personalization.fingerprint,
        )
