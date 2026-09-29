# 课程语料工作区

此目录保留两门课程的知识点目录、检索评测题与语料构建工具所需的结构。公开仓库不附带教师私有课件或授权尚未确认的原始文档；历史清单和评测报告也不作为公开语料发布。

接入自己的课程资料时，将有使用权的 PDF、DOCX、PPTX、Markdown 或文本文件放在 source_documents/，在 catalog.yaml 登记课程、知识点、来源和许可状态，然后从 ai-agent-service/ 目录运行：

~~~bash
python scripts/inventory_course_materials.py
python scripts/catalog_course_materials.py
python scripts/build_course_corpus.py
python scripts/evaluate_course_corpus.py
~~~

构建结果写入 data/seed_corpus/seed_documents.jsonl。正式使用前应复核许可证、知识点覆盖率与检索质量，不应把缺失原件的历史报告视为当前可复现结果。
