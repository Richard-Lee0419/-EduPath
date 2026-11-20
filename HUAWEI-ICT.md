# 华为 ICT 大赛版（赛题 2）

`huawei-ict-2026` 保存 Cache 地址拆分、二叉树遍历诊断和 AgentArts 适配。前端进入「学习评估」即可选择专项练习。两种练习按学生保存首次作答、讲义引用和变式复测结果。

## 实现与验收范围

- Cache 工作流用 AgentArts 进行知识检索和确定性规则诊断。v3 的五条固定问题已在控制台试运行通过，参赛运行时已有部署记录。
- EduPath 默认使用本地诊断。只有成功使用已部署端点时，产品才返回云端来源与执行标识。最近一次正式调用仍未通过 IAM 鉴权。
- 二叉树实现已在产品本地验收。平台接线可参考[二叉树节点说明](huawei-ict/docs/20-二叉树AgentArts节点接法.md)。

## 运行

按[项目 README](README.md)启动前端、Java API 和 AI 服务。若只验证诊断逻辑，在仓库根目录执行：

```bash
python huawei-ict/scripts/eval-dual-course.py
cd huawei-ict
python -m unittest discover -s tests -p test_cache_diagnosis.py
```

Cache 的 [AgentArts 代码节点](huawei-ict/submission/ict-final-integration/agentarts-cache-code-node.py)和[讲义](huawei-ict/agentarts/cache-address-diagnosis.md)可用于重建同一案例。平台调用配置见 [IAM 联调说明](docs/agentarts-cache-iam.md)。初版独立演示与资料见[比赛目录](huawei-ict/README.md)。
