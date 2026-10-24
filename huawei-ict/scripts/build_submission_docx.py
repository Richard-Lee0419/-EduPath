"""Fill the official Huawei ICT submission template with EduPath content.

Usage:
    python scripts/build_submission_docx.py

Reads the template from the path given by ``EDUPATH_ICT_TEMPLATE`` (or the
default Desktop path), writes ``submission/作品信息收集表-...docx`` and embeds
two demo screenshots.  The source template is never modified.
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Inches, Pt

ROOT = Path(__file__).resolve().parent.parent
TEMPLATE = Path(
    os.getenv(
        "EDUPATH_ICT_TEMPLATE",
        str(
            Path.home()
            / "Desktop"
            / "知径EduPath其他相关作品投递"
            / "华为ICT"
            / "【赛题2-模板1】创新赛-作品信息收集表-学校-队名-队长姓名-手机号 （初赛阶段）.docx"
        ),
    )
)
OUT = ROOT / "submission" / "作品信息收集表-知径EduPath-华为ICT2026.docx"
FONT = "微软雅黑"


def unique_cells(row):
    seen = set()
    result = []
    for cell in row.cells:
        if id(cell._tc) in seen:
            continue
        seen.add(id(cell._tc))
        result.append(cell)
    return result


def clear_cell(cell):
    for paragraph in cell.paragraphs[1:]:
        paragraph._element.getparent().remove(paragraph._element)
    first = cell.paragraphs[0]
    for run in list(first.runs):
        run._element.getparent().remove(run._element)
    # The template's first paragraph may carry list numbering ("1、").
    p_pr = first._p.get_or_add_pPr()
    for num_pr in p_pr.findall(qn("w:numPr")):
        p_pr.remove(num_pr)
    return first


def set_cell(cell, blocks, size: float = 10.5, align=None):
    first = clear_cell(cell)
    for index, block in enumerate(blocks):
        paragraph = first if index == 0 else cell.add_paragraph()
        paragraph.paragraph_format.space_before = Pt(0)
        paragraph.paragraph_format.space_after = Pt(2)
        paragraph.paragraph_format.line_spacing = 1.18
        if align is not None:
            paragraph.alignment = align
        text, bold = block if isinstance(block, tuple) else (block, False)
        run = paragraph.add_run(text)
        run.bold = bold
        run.font.name = FONT
        run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), FONT)
        run.font.size = Pt(size)


def add_images(cell, images, width_inches: float = 2.75):
    paragraph = cell.add_paragraph()
    paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
    paragraph.paragraph_format.space_before = Pt(4)
    for image in images:
        run = paragraph.add_run()
        run.add_picture(str(image), width=Inches(width_inches))


def allow_row_split(table):
    for row in table.rows:
        tr_pr = row._tr.get_or_add_trPr()
        if tr_pr.find(qn("w:cantSplit")) is not None:
            tr_pr.remove(tr_pr.find(qn("w:cantSplit")))


def main() -> None:
    if not TEMPLATE.exists():
        raise SystemExit(f"template not found: {TEMPLATE}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(TEMPLATE, OUT)
    document = Document(OUT)
    table = document.tables[0]
    allow_row_split(table)

    # Team information: placeholders must be replaced by the team before submission.
    set_cell(unique_cells(table.rows[1])[1], ["【待填写：学校全称】"], size=11)
    set_cell(unique_cells(table.rows[2])[1], ["【待填写：队伍名称】"], size=11)
    members = unique_cells(table.rows[4])
    set_cell(members[1], ["【待填】"], size=10)
    set_cell(members[2], ["【待填】"], size=10)
    set_cell(members[3], ["【待填】"], size=10)
    set_cell(members[4], ["【待填】"], size=10)
    set_cell(unique_cells(table.rows[5])[1], ["【待填写：队长手机号】"], size=11)
    set_cell(unique_cells(table.rows[6])[1], ["【待填写：队长邮箱】"], size=11)

    photo_cell = unique_cells(table.rows[7])[1]
    photo_cell.add_paragraph().add_run("（请在此处插入 1 张约 2MB 的团队合照）").font.size = Pt(9)

    set_cell(
        unique_cells(table.rows[9])[1],
        [("赛题2：基于华为云AgentArts智能体开发平台的Agent设计和应用", True)],
        size=10.5,
    )
    set_cell(
        unique_cells(table.rows[10])[1],
        [("知径 EduPath——基于华为云 AgentArts 的课程学习多智能体", True)],
        size=11,
    )

    set_cell(
        unique_cells(table.rows[11])[1],
        [
            ("行业赋能型（教育）+ 创新探索型（多 Agent 协同 / MCP 工具调用）。", True),
            "面向高校《数据结构与算法》《计算机组成原理》等专业基础课，解决大班教学中“统一进度无法匹配个体薄弱点、通用 AI 内容缺少课程依据、练习结果不回流学习决策”的问题。服务对象包括学生、任课教师与课程团队；方案可复制到其他高校课程与职业教育场景。",
        ],
        size=10,
    )

    set_cell(
        unique_cells(table.rows[12])[1],
        [
            ("1. 平台与角色：以华为云 AgentArts 为智能体构建、编排、知识与发布平台，创建“知径学伴”主智能体，以及画像、检索、教学设计、资源生成、辅导、评估、安全 7 个角色智能体。", False),
            ("2. 工作流编排：意图识别 → 课程/知识点确认 → 画像与检索并行 → 资源规划 → 讲义/导图/题库并行生成 → 质量门禁 → 安全审核 → 发布/落库 → 路径规划；测验分支进入“评估→路径重规划”。", False),
            ("3. 知识接入：课程语料经清洗、结构分段（标题层级 3、约 500 字）和元数据标注后接入 AgentArts 平台知识库，使用 pangu_embedding + pangu_rerank；生成内容强制引用 chunk，弱证据拒答。", False),
            ("4. 工具编排：通过自定义 MCP 服务把 EduPath 既有课程、画像、题库、路径能力注册为 10 个工具，实现低侵入复用，原项目代码零改动。", False),
            ("5. 模型与发布：使用 MaaS 的 DeepSeek-V4-Flash / 盘古模型与 Token Plan 代金券；通过 AgentArts 发布 API/Web，前端以 SSE 展示 Agent 协作轨迹、证据与产物。", False),
        ],
        size=10,
    )

    set_cell(
        unique_cells(table.rows[13])[1],
        [
            "高校专业基础课普遍面临“大班统一进度”与“个体差异”的矛盾：学生不清楚自己弱在哪，教师难以为每人生成练习，通用 AI 又常给出脱离教材、无法核验的内容。知径 EduPath 以华为云 AgentArts 为底座，把学习画像、课程证据、资源生成、路径规划、智能辅导与测验评估串成可执行闭环。",
            "画像 Agent 从学生自述中提取目标、薄弱点、偏好与时间预算；检索 Agent 在课程知识库中取证；教学设计与生成 Agent 依据证据产出讲义、思维导图、题库和代码实验；评估与安全 Agent 分别做质量门禁和内容审核；测验结果再回流画像与路径，形成持续调整。所有生成内容标注来源，Agent 协作过程可视化、可追溯。",
            "项目已完成两门课程、108 篇公开语料与完整工程验证，可低侵入接入 AgentArts 并复制到其他课程。",
        ],
        size=9.5,
    )

    tech_cell = unique_cells(table.rows[14])[1]
    set_cell(
        tech_cell,
        [
            ("一、场景定义", True),
            "面向高校专业基础课的学生、任课教师与课程团队。学生端解决“不知道弱在哪、下一步学什么”；教师端解决“重复备课、难以分层”；课程团队解决“换课成本高”。选择《数据结构与算法》《计算机组成原理》两门课做验证，因为知识点结构清晰、公开语料可追溯。",
            ("二、编排思路", True),
            "采用“主控 Agent + 7 个角色”的工作流，而不是单体 ChatBot。画像、检索、教学设计、生成、辅导、评估、安全职责与失败边界不同：检索失败要拒答，生成失败要重试，评估失败要降级，安全失败要拦截；拆开才能独立重试、独立评测、独立替换模型。",
            ("三、工作流架构", True),
            "12 个节点：开始 → 意图识别 → 分支 → 课程/知识点校验 → 画像/检索并行 → 资源规划 → 三路并行生成 → 质量门禁（五维加权，≥80 且全维度通过）→ 安全审核（学术诚信/隐私/事实性/价值观）→ 发布/落库 → 路径规划 → 结束。测验分支：评估 → 插入补救/调整难度 → 重规划。所有生成内容带 [E#] 引用，发布前校验引用是否存在。",
            ("四、知识库/语料", True),
            "2 门课、108 篇公开语料（cp-algorithms、OpenDSA、RISC-V ISA Manual、lowRISC Ibex），保留固定 commit、许可证、归属与中文适配说明。结构感知分段保留标题路径；平台知识库配置 pangu_embedding + pangu_rerank，top_k=5、阈值 0.35；命中测试含 3 条强命中 + 1 条弱命中（用于验证“该拒答时拒答”）。",
            ("五、自定义 MCP / Skill", True),
            "10 个工具：画像读取、课程检索、资源规划、资源生成、路径规划、证据辅导、出题、测验分析、安全审核、课程大纲。支持 stdio、HTTP JSON-RPC 与 HTTP+SSE；返回结构化 JSON，并标注运行来源（fixture / EduPath AI / AgentArts），不把演示数据说成模型输出。",
            ("六、模型与资产", True),
            "主模型 DeepSeek-V4-Flash（MaaS 预置，备选盘古）；embedding/rerank 用 pangu_embedding/pangu_rerank；温度按角色设置（画像/评估/安全 0–0.1，规划 0.3，生成 0.4）；JSON Schema 校验，失败重试 1 次后降级模板。",
            ("七、效果与指标", True),
            "演示链路：9 步 Agent 轨迹、5 条课程证据、3 类资源、质量 88/100（A）、安全 4 项通过、3 天路径。工程验证（原项目公开记录）：AI 82 项、Java 56 项、前端 29 项断言、2 项 Chromium E2E 通过；真实 HTTP 五任务闭环；知识库上传→检索→重建→拒绝→删除。以上证明工程流程，不宣称学习成绩提升；学习效果需后续班级试点。",
            ("八、材料与复现", True),
            "提供可视化 Demo（零构建，离线可跑）、3.5 分钟视频脚本、5 张演示截图、AgentArts 配置包（提示词/工作流/知识库/评测集）、部署文档与 GitHub 比赛分支。评委可按文档在 AgentArts 内复建，并用 24 条评测集回归。",
        ],
        size=9.2,
    )

    show_cell = unique_cells(table.rows[15])[1]
    set_cell(
        show_cell,
        [
            ("在线 Demo：", True),
            "本地运行 python -m bridge.gateway --port 8765；正式在线地址待部署后补充。",
            ("演示视频：", True),
            "3.5 分钟，按 docs/06 脚本录制；正式链接待补充。",
            ("代码与配置：", True),
            "https://github.com/Richard-Lee0419/-EduPath/tree/huawei-ict-2026/huawei-ict",
            ("分享帖：", True),
            "华为云开发者社区，草稿见 docs/07；正式链接待发布。",
        ],
        size=9.5,
    )
    add_images(show_cell, [ROOT / "evidence" / "docx-showcase.png"], width_inches=5.4)

    document.save(OUT)
    print(f"written: {OUT}")
    print(f"size: {OUT.stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    main()
