# 开源课程知识库

EduPath 的生产知识库除教师上传资料外，还包含一组许可可追溯的开放课程语料。语料用于“数据结构与算法”和“计算机组成原理”两门课程，启动时经过结构感知切分、Embedding，并幂等写入 Chroma。

## 当前覆盖

| 课程 | 文档数 | 主要主题 |
| --- | ---: | --- |
| 数据结构与算法 | 60 | 复杂度、线性表、链表、栈队列、树、堆、哈希、完整排序体系、区间结构、图算法、网络流、动态规划与字符串 |
| 计算机组成原理 | 48 | 数制/补码、对齐端序、ALU/数据通路、ISA、流水线、存储层次、Cache、MMIO/DMA、特权级、安全与验证 |

来源分为 cp-algorithms（CC BY-SA 4.0）、OpenDSA（MIT）、RISC-V ISA Manual（CC BY 4.0）和 lowRISC Ibex（Apache 2.0）。每条文档记录固定提交版本、原文链接、许可证链接、中文适配说明和上游原文节选；完整清单见 `ai-agent-service/data/seed_corpus/open_source_manifest.json`。

## 深度教学结构

`edupath-open-corpus-v3` 不再把知识点作为简短词条，并增加 `foundation_core`、`course_core`、`advanced_extension` 三层课程标签。每篇文档都包含以下结构，供 KnowledgeAgent、ResourcePlannerAgent 和 EvaluationAgent 分别复用：

1. 中文教学导读与核心原理；
2. 可验证的学习目标；
3. 先修知识、后续关联和中英文检索别名；
4. 一个可逐步追踪状态的具体案例；
5. 方法选择、复杂度或硬件工程检查清单；
6. 自测题与答案组织框架；
7. 固定 Commit 的开源证据和许可证信息。

当前 108 篇文档全部达到深度教学结构，其中 40 篇专门补齐两门课程的基础主干，所有主题均有独立推演案例。结构感知切分会保留标题路径，使检索器可以按“原理、案例、工程检查、自测或证据”选择不同上下文角色。

## 更新与部署

使用 `ai-agent-service/scripts/build_open_course_corpus.py` 从三个本地上游检出版本重建 JSONL。生产 Docker Compose 将语料目录只读挂载到 AI 服务，因此更新后只需重建或重启 AI 服务；Retriever 启动时使用稳定 chunk id 执行 upsert，不会因重复启动产生副本。

## 质量门槛

- 不接收许可证为 `unknown` 的生产语料。
- 每条记录必须有 HTTPS 来源、40 位提交哈希、SPDX 风格许可证和适配声明。
- 每篇正文不少于 1,200 字符，并包含学习目标、知识关联、推演案例、工程检查和自测结构。
- 中文教学导读必须包含概念、复杂度或实现边界，不能只有链接或原文堆砌；推演案例覆盖率必须为 100%。
- 部署后分别对两门课程执行代表性查询，并检查来源、混合检索诊断和 Chroma 总切片数。
