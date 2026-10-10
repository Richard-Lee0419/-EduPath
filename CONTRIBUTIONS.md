# 项目来源与个人贡献

## 仓库关系

EduPath 是软件杯 A3 团队项目。原团队仓库为 [Moon-Meld/EduPath](https://github.com/Moon-Meld/EduPath)，本仓库是 Richard-Lee0419 整理的个人公开版本，保留可公开源码、配置与语料，移除了私有资料及凭据。

公开版本重新上传过项目快照，因此这里的少量发布提交不代表全部开发历史，也不代表项目由一人从零完成。以下提交来自原团队历史；提交作者能证明参与了对应改动，不能单独证明文件内全部代码的唯一作者身份。

## 职责

| 参与者 | 作者确认的主要职责 | 历史支持与范围 |
| --- | --- | --- |
| Richard-Lee0419 | 前端与 UI 设计、主要 AI 模块、部分后端 | 页面与交互、课程检索、模型流水线、辅导、评估驱动路径等实质改动，见下表 |
| Moon_Meld | 主要部署工作；亦参与框架、后端与 AI 实现 | 最初框架和后台基础在 Richard 的首个前端提交之前已存在；后续有韧性与部署改动 |

职责表来自作者说明与 Git 核对。Java、AI 等目录有双方改动，应在面试中指明自己实际设计、实现和能维护的功能；不使用“独立完成全部后端与部署”的表述。

## 可核对的代表性改动

| 作者 | 提交 | 可见内容 |
| --- | --- | --- |
| Moon_Meld | [3d4070f](https://github.com/Moon-Meld/EduPath/commit/3d4070f) | 初始化 Monorepo |
| Moon_Meld | [7e9e467](https://github.com/Moon-Meld/EduPath/commit/7e9e467) | 后端与 AI/RAG 平台基础 |
| Richard-Lee0419 | [5aba2fd](https://github.com/Moon-Meld/EduPath/commit/5aba2fdfe2bcb563ce242a59531389d3b51ee8ec) | 前端页面、视觉与交互、初始演示数据及接口文档；该阶段包含 mock |
| Richard-Lee0419 | [5d6ef43](https://github.com/Moon-Meld/EduPath/commit/5d6ef430ae7d52b55af826ffc5e381ab701b4089) | 可追溯课程检索、文档处理、语料工具、知识库接口与测试 |
| Richard-Lee0419 | [bcb5921](https://github.com/Moon-Meld/EduPath/commit/bcb5921d7e69a90de5d171c6f8894a3118a43c13) | 模型网关、结构化资源生成、任务处理与运行信息 |
| Richard-Lee0419 | [e0940e2](https://github.com/Moon-Meld/EduPath/commit/e0940e296522f72742958a81d47c7616f7a42815) | RAG 辅导、引用结构、服务与前端连接 |
| Richard-Lee0419 | [deb806c](https://github.com/Moon-Meld/EduPath/commit/deb806c76ed87a3be3c4119d137e6b8e85230454) | 评估驱动路径重规划及闭环接口 |
| Moon_Meld | [d76b64a](https://github.com/Moon-Meld/EduPath/commit/d76b64a) | Agent 韧性与部署资产 |

表内链接指向原团队仓库，而非本仓库的重新上传历史。若原仓库访问范围变化，需由作者提供可公开的提交导出或对应 diff；不能把暂不可访问链接说成外部已验证证据。

## 框架、工具与成果界限

Vue、Spring Boot、FastAPI、Chroma 等框架与依赖属于第三方基础；课程语料来源与授权见 [开放知识库说明](docs/open-source-knowledge-base.md)。本项目的工程贡献体现在学习流程、模块连接、结构化契约、页面交互、业务状态及验证上。

开发可以使用 AI 辅助，但仓库历史不能统计 AI 参与比例。作者应能解释关键代码、复现检查、定位错误并维护成果。当前收尾含 AI 辅助修复与文档整理，新的改动不能自动写成作者原先独立完成的历史成果。

简历建议使用：“团队项目，主要负责前端/UI与 AI 模块，参与后端；部署由队友主导。”具体能力与验证边界见 [验证记录](docs/validation-report.md)。
