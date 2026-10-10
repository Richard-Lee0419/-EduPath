# 文档切分与课程语料

本文说明当前公开代码中的切分实现，不使用早期私有资料或历史报告推断当前语料质量。当前检索边界见 [检索与个性化](learner-adaptive-rag.md)。

## 实际切分策略

源码：`ai-agent-service/app/rag/semantic_chunker.py`、`chunker.py`。

1. 文本按空行、标题等形成块；过长块继续按句子边界拆分。
2. 识别章节路径、课程概念锚点与教学角色，如定义、例子、算法、代码、复杂度。
3. 综合长度、标题变化、概念/词项连续性和教学角色迁移决定合并与切分。
4. 每个分块携带 ID、课程和知识点、`anchors`、`roles`、`heading_path`、签名与相邻块 ID；课程来源、许可等元数据继续传入索引。

默认长度参数为最小220、目标720、硬窗口1100字符，但遇到不可再按句子拆分的长块时不能把该值当作严格输出上限。现有实现会识别代码角色，**没有证明任意超长代码、表格和公式都完整保存为不可分原子**。需要完整内容时，应同时人工核对解析结果与源文档。

概念和角色规则是工程启发式；“概念—角色图”是实现组织方式，不代表经过研究验证的优越算法。

## 索引与 ID

local 和 Chroma 使用分块正文与相关元数据组织索引文本。内置语料 ID 包含文档身份摘要，避免同一知识点下不同文档相互覆盖；上传文档使用精确 `kb_<document_id>_` 前缀。本次维护增加替换和删除旧分块的保护，具体验证见 [验证记录](validation-report.md)。

章节路径、相邻关系与来源方便排查结果。它们被保存不等于检索入口已经执行邻接扩展、BM25 或 MMR；当前调用链见检索文档。

## 公开语料与新资料

- 默认公开 seed：`ai-agent-service/data/seed_corpus/seed_documents.jsonl`。
- 来源与许可清单：[开放知识库说明](open-source-knowledge-base.md)。
- 自有授权资料工作区：[课程语料 README](../ai-agent-service/data/course_corpus/README.md)。
- 知识点、目录与 golden query 位于 `ai-agent-service/data/course_corpus/`。

公开版本没有附教师私有教材和课件原件。旧“8/24覆盖”“已达到某检索阈值”等阶段说法不用于本公开语料。需要从自己的授权资料构建时，从 `ai-agent-service/` 运行：

```bash
python scripts/inventory_course_materials.py
python scripts/catalog_course_materials.py
python scripts/build_course_corpus.py
python scripts/evaluate_course_corpus.py
```

这些命令需要实际原文与目录相符；空工作区或缺失原件不会凭历史清单产生有效语料。README 的默认运行使用已公开 seed，不依赖作者电脑上的私有文件。

## embedding 模式与评测

默认 `EMBEDDING_PROVIDER=local` 使用确定性哈希表示，适合无密钥功能验证。若选择 Hugging Face 模型，需安装 `.[embedding]` 并设置 provider/model；下载、内存和实际质量依赖具体模型及环境。切换真实模型不自动证明检索更好。

可信评测应固定语料、查询和正确证据，记录版本、embedding 与向量模式，并报告失败查询。Hit@K、MRR 验证找到了标注目标；还需人工检查证据支持性。知识点覆盖、检索命中和学习效果属于不同结论。
