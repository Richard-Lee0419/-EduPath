# 测验反馈与路径调整

当前公开版本将实际作答接入评估、画像与路径，能够验证数据如何回流；没有完成正式教育效果验证。早期“知识追踪 Agent＋混合检索/MMR＋独立 intervention 接口”叙述不符合当前调用链。

## 实际流程

```text
POST /api/quiz/generate
  → 生成1–3道题，校验题型、答案、证据与 SafetyAgent 结果
POST /api/quiz/submit
  → Java 根据保存答案确定性评分
  → Python EvaluationPipeline 分析作答、提供下一步建议
  → Java 校验评分和薄弱点没有被模型任意改写
  → 保存作答、错题与掌握度记录
  → 更新画像版本
  → LearningPathService.replanAfterEvaluation 生成新路径版本
GET /api/evaluation/report
  → 读取该学生最新作答与掌握度记录
```

入口为 `QuizService`、`QuizRepository`、`ProfileService`、`LearningPathService`、`EvaluationService` 和 `ai-agent-service/app/services/evaluation_pipeline.py`。

`QuizService.submit` 校验评估返回的 `overall_score` 与 Java 评分一致，薄弱点与错题知识点集合一致；路径重规划另行校验审核结果及结构。画像、路径和作答使用真实业务记录，但演示账号中的预置数据属于样例。

当前不存在 `POST /api/evaluation/intervention` 或独立 `KnowledgeTracingAgent`；此流程不应被称为已经实现了这些接口或算法。

## 掌握度与错因的解释边界

- Java 使用作答正确性、作答次数及行为加权信号维护掌握度；它是业务规则信号。
- Python 评估使用确定性基线或配置模型形成诊断与建议。诊断不等于经过实验验证的因果归因。
- 无作答时，评估接口返回空状态，不能把没有数据的0分理解为实际成绩。
- 当前没有 Beta/BKT/DKT 等正式知识追踪模型或其效果比较。
- 画像与路径改变可以证明反馈机制响应；不能证明学生理解、保持或迁移能力提高。

检索是课程与知识点约束的向量适配器，没有在该链路调用混合召回或 Adaptive MMR。具体见 [检索边界](learner-adaptive-rag.md)。

## 风险与验证

重点检查生成题目的证据、评分稳定性、跨学生数据隔离、评估失败时的数据状态、路径版本一致性。Java 的 `@Transactional` 约束数据库写入，但事务外的模型调用不能由数据库回滚撤销，外部请求的耗时与失败仍需要处理。

当前测试和本地跨服务流程的实际执行结果见 [验证记录](validation-report.md)。测试验证业务与接口结果，不等于所有错因诊断准确，也不能替代真实模型质量评价。

演示时至少保留一次提交前后的画像和路径版本、作答结果、调整理由及 generation mode；见 [演示指南](demo-guide.md)。如未来要验证教学效果，应另行设计基线、评价方式和数据收集，本次工程收尾不包装为科研结论。
