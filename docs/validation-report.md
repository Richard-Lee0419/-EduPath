# 公开版本验证记录

验证日期：2026-10-10 至 2026-10-11（Asia/Shanghai）。本地实现、测试和录像已提交于 [`a0dea72`](https://github.com/Richard-Lee0419/-EduPath/commit/a0dea7288af58728146ef95a708957816ddad722)，基于公开主分支 `b0007c063126cdca8e2ef4b970386592d925bc53`。对象不是原团队版本或线上实例；收尾改动公开在 [草稿 PR #1](https://github.com/Richard-Lee0419/-EduPath/pull/1)，未合并 main 或部署。

本记录区分实际执行、源码检查和未验证项。生成模式、版本变化及通过测试只证明相应工程流程，不证明教学效果或真实模型质量。

## 已执行的检查

| 检查 | 命令/方式 | 结果与范围 |
| --- | --- | --- |
| 前端行为断言 | `npm test` | 9 项资源解析、20 项事件测试通过；静态契约脚本退出 0 |
| 前端类型 | `npm run type-check` | 通过；知识库响应与页面修改后另行复核 |
| 前端构建 | `npm run build` | 通过；保留 Mermaid 等 chunk 大于 500 kB 的警告，没有隐藏警告 |
| Java | `mvn package` | 56 项通过，0 失败、0 错误、0 跳过；包含测试和可运行 JAR 打包 |
| AI 服务 | `python -m pytest -p no:cacheprovider --basetemp <新的工作区临时目录>` | 最初 77 项通过；增加实际失败的 Chroma 过滤回归后 82 项通过；1 条 TestClient 弃用警告；仅本地/模拟模型 |
| 已有模型 smoke 脚本单元检查 | `python -m pytest scripts/tests -p no:cacheprovider` | 1 项通过；不代表运行了真实模型 smoke |
| 学习闭环 | `scripts/smoke_learning_flow.py` | 真实 Java→Python HTTP，五个业务任务成功；证据见下文 |
| 知识库生命周期 | `scripts/smoke_knowledge_lifecycle.py` | 真实上传、检索、重建、拒绝和删除；证据见下文 |
| 前端 HTTP 入口与代理 | GET 前端 `/` 和 `/api/courses` | HTML 入口与 Vite→Java 请求通过；不代表页面已在浏览器渲染 |
| Chromium E2E | `npm run test:e2e` | 2 项通过（46.1 秒）：学生闭环、教师拒绝上传；无 HTTP mock |
| 学生流程录屏 | `npm run test:e2e:record` | 同一学生用例实际操作并通过后导出录像及截图；见下文 |
| 真实模型小样本 | `scripts/smoke_real_model_sample.py` | 两项任务：画像通过，讲义失败；整体退出 1，不记为全部通过 |
| Shell 语法 | Git Bash `bash -n` 检查验证/CI脚本，包括 Docker 启动器 | 通过 |
| 本地 CI 启动器 | Windows Git Bash `EDUPATH_BROWSER_TESTS=true bash scripts/ci-learning-flow.sh` | 启动新 H2/AI/Vite 服务，两份 HTTP smoke 与 2 项 E2E 均通过，退出 0 |
| 配置文件 | PyYAML 解析 Compose 与 CI YAML | 通过；不等于 Docker 启动成功或 GitHub Actions 运行成功 |
| GitHub Actions | [run 38067679122](https://github.com/Richard-Lee0419/-EduPath/actions/runs/38067679122) | 三组通过，提交 `a0dea72`，见下文 |
| 真实开发 Docker | Ubuntu 24.04 CI 启动 Compose | MySQL 8.4/18 次迁移、Chroma、HTTP 学习闭环和知识库生命周期通过；不等于生产部署 |
| 部署文件 | `bash scripts/production-readiness-check.sh` | 文件及静态配置检查通过；不能据此宣称生产就绪 |
| 缺依赖时失败 | 无 Maven / Docker 的脚本预检 | 返回 1 并提示缺失工具，没有跳过后宣称全部通过 |

Java 数量按 `backend-api/target/surefire-reports/TEST-*.xml` 汇总。测试执行需要本机回环网络；受限沙箱中的 TestClient/Maven 调用曾挂起，限定重跑后通过。首次 `npm ci` 因沙箱 DNS 错误失败，允许网络的重跑从锁文件成功安装依赖。失败尝试不计为通过。

本机环境：Windows 11，Node 24.14.0 / npm 11.9.0，JDK 17.0.19，Maven 3.9.16，Python 3.13.3。Java 用 H2 测试 profile；AI 用公开目录源码及已有 Python 虚拟环境中的依赖。未借用根目录的实现来替代公开源码测试。CI 独立安装本仓库依赖，使用 Python 3.12。

未配置 ESLint，因此没有“lint 已通过”的结论。`test:real-api` 是 `test:static-contract` 的兼容别名，只查源码，不发送 HTTP。

## 浏览器交互与核心流程视频

隔离服务使用 18080（Java/H2）、18000（确定性 AI）、15173（Vite），`VITE_PROXY_TARGET` 指向同一 Java 实例。Playwright 1.64.0 安装的 Chromium 156，桌面视口 1440×1000。

```powershell
$env:EDUPATH_BROWSER_URL='http://127.0.0.1:15173'
$env:EDUPATH_BACKEND_URL='http://127.0.0.1:18080'
$env:EDUPATH_AI_URL='http://127.0.0.1:18000'
cd frontend
npm run test:e2e
npm run test:e2e:record
```

两项 [浏览器测试](../frontend/e2e/learning-flow.spec.ts) 通过。学生用例注册新账号，在页面实际提交画像，生成讲义/思维导图/题库，打开正文与证据，生成路径，验证未完成小测不能提交，三题选错后获得 0 分，读到画像与路径 1→2，并刷新核对保存。教师用例实际上传审核拒绝文档，HTTP 200 中的失败字段被页面提示、列表与刷新正确呈现，最后删除资料。没有拦截请求或替换服务器返回。

初次运行曾因测试难度选择器定位不正确中止；修正定位后发现小测页面没有题目：前端错误地把异步任务回执当作同步结果。已让生成和提交按契约等待 SSE/轮询终态并使用 `result`，页面展示实际任务进度；之后两项均通过。另将教师质量管理面板按角色显示，学生不再请求无权限的管理接口。失败运行不计为通过。

录制版增加结果停留和截图，仍执行全部学生断言，成功后由脚本导出 [视频](evidence/core-learning-flow.webm)、[录制记录](evidence/browser-recording.json) 与三张截图。视频没有剪辑或加速，使用确定性模式，不证明真实模型质量。它仅覆盖一条桌面核心流程，不构成全部组件、移动端或并发行为验证。

## 已获准的真实模型小样本

只执行两项任务，没有追加任务或失败后重试。使用同一公开源码、另一个隔离 H2 实例（28080/28000），本地向量及哈希 embedding，已有私有配置中的 DeepSeek 密钥。私有配置没有复制进仓库，也未写进日志或报告。

```bash
python scripts/smoke_real_model_sample.py \
  --backend-url http://127.0.0.1:28080 --ai-url http://127.0.0.1:28000 \
  --confirm-paid-isolated-run --output docs/evidence/real-model-sample.json
```

[脱敏记录](evidence/real-model-sample.json) 显示：画像任务成功，`real_model_used=true`、`deepseek-chat`、两次调用、总计 1,159 tokens，Safety 通过，画像版本 1。该任务等待耗时 3.362 秒只属于这次调用，不是性能基准。

讲义任务终态失败。AI 任务的 LectureAgent 步骤及模型日志明确记录 `LLM JSON output was truncated; increase LLM_MAX_OUTPUT_TOKENS`。本次为限制费用设置 `LLM_MAX_OUTPUT_TOKENS=2200`、超时 45 秒、网络重试 0；项目默认上限为 8192。失败任务缺少完整 `model_runtime`，不虚构其调用次数或 token 成本，也不将未完成的 Safety 步骤记为审核拒绝。这个样本证明了真实画像/Safety 集成和模型错误上报，不能证明默认预算下讲义一定失败或一定成功。

脚本整体状态为 `partial_or_failed`、退出 1；没有把局部成功写成真实模型闭环通过。后续如需真实模型资源演示，应在另行允许费用后按默认预算验证，保留新旧两份记录。没有测试真实 embedding、开展生成质量基准或学习效果实验。

## 真实 HTTP 学习闭环

运行包：`backend-api/target/backend-api-1.0.0-rc.1.jar`；启动 `test` profile，日志确认 `jdbc:h2:mem:edupath_test`，18 次 Flyway 迁移成功。Python 从 `ai-agent-service/` 启动，local embedding / vector，LLM 配置为空。通过本机端口 18080、18000 访问，未写线上服务或调用付费模型。

```bash
python scripts/smoke_learning_flow.py \
  --backend-url http://127.0.0.1:18080 \
  --ai-url http://127.0.0.1:18000 \
  --output docs/evidence/local-learning-flow.json
```

脚本新建合成学生、登录，然后实际提交画像、资源、路径、小测和评估任务；请求经过 Java、AI HTTP 及数据库保存，不是只调用函数或伪造后端返回。

实际结果见 [脱敏运行记录](evidence/local-learning-flow.json)：

- 五个任务均为 `success`，模式均为 `deterministic_fallback`，模型调用 0。
- 讲义、思维导图、练习共三类资源落库，内容、检索证据、Safety 和质量门禁断言通过。
- 三日路径含有效任务时间，每日总时长不超过 30 分钟。
- 三道题在作答前没有答案或解析，证据引用属于本次检索结果。
- 空答案提交得到 Java 确定性 0 分，AI 评估未改写分数；画像版本 1→2、路径版本 1→2。
- 再读画像、路径、报告，确认变化已保存且来源关联一致。

这不代表所有模型回答正确，也不是浏览器端到端验证。该合成记录不是用户反馈或学习收益数据；服务停止后 H2 内存数据消失。

## 真实 HTTP 知识库生命周期

在同一隔离测试服务中执行：

```bash
python scripts/smoke_knowledge_lifecycle.py \
  --backend-url http://127.0.0.1:18080 \
  --ai-url http://127.0.0.1:18000 \
  --confirm-isolated-local --timeout 90 \
  --output docs/evidence/local-kb-lifecycle.json
```

实际结果见 [脱敏运行记录](evidence/local-kb-lifecycle.json)：安全合成文档上传后可检索；重建任务成功，分块 1→1，没有残留重复；风险表达文档审核拒绝、未进入搜索；经 Java 删除后目标向量消失，另一文档仍可检索。三份测试资料均完成清理，模型调用 0。

内存向量和数据库之间没有分布式事务；测试覆盖本次顺序操作，不构成任意并发与进程故障下的强一致性保证。Chroma 适配器另有模拟回归，未在本机启动真实 Chroma 服务。

## 新回归与实际风险

| 风险 | 对应证据 |
| --- | --- |
| 拒绝资料仍进入检索、缩短重建残留、错误删同前缀文档、部分写入失败 | [向量一致性测试](../ai-agent-service/tests/test_kb_vector_consistency.py)；真实 HTTP 生命周期 |
| 拒绝资源任务仍返回正文、混淆质量门禁与 Safety | [资源拒绝测试](../ai-agent-service/tests/test_resource_safety_gate.py)；Java 持久化断言 |
| 列表/详情/下载/交互泄露私人资源、教师越课程访问 | [Java API 回归](../backend-api/src/test/java/com/edupath/BackendApiContractTests.java) |
| Safety 缺失、字符串布尔或状态矛盾仍写资源 | 同上及 [HTTP Client 回归](../backend-api/src/test/java/com/edupath/ai/AiAgentClientTests.java) |
| 入库失败记录回滚丢失、重建假成功、删除无撤回确认 | Java API / Client 回归及真实 HTTP 生命周期 |
| 无关近邻耗尽 Chroma 候选预算，直接/关联知识点无证据 | [Chroma 过滤回归](../ai-agent-service/tests/test_chroma_search_filters.py)；Docker 临时集合 HTTP/ANN 验证 |

首批新增 Java 13 项、AI 15 项风险回归；远程失败后另补 5 项 Chroma 筛选回归。测试总数不是质量指标；新增检查针对拒绝、权限、持久化、撤回和召回结果，不是仅确认函数可调用。用同一干扰集合对照 `cc4cff5` 的适配器，三组知识点查询均返回 0 条证据；修复后均返回两条正确证据。这是合成场景的缺陷复现，不是检索质量基准。

## 独立复现与 CI

新克隆按 README 安装前端、Python 开发依赖、JDK 和 Maven。在无密钥的终端执行本地测试；`scripts/verify-all.sh` 预检失败会退出非零。它验证测试/构建，不自动启动业务服务。

跨服务检查可用以下方式在 macOS/Linux 或已准备对应环境的 Git Bash 中运行；需要端口 8080、8000 空闲，Python 环境已激活：

```bash
cd backend-api
mvn package
cd ..
bash scripts/ci-learning-flow.sh
```

该脚本启动确定性 AI 和 H2 Java 服务，等待健康检查，运行两份真实 HTTP smoke；设置 `EDUPATH_BROWSER_TESTS=true` 时同时管理 Vite 并执行浏览器验证，结束时关闭自己启动的进程。本机于 Windows Git Bash 实际运行完整启动器，HTTP 与两项 E2E 均通过（该轮浏览器用时 13.6 秒）。输出在忽略的 `output/validation/` 中，启动器退出 0。没有将这次 Windows 执行视为 Linux 或远程 Actions 验证。

[CI 工作流](../.github/workflows/ci.yml) 在 push、PR 和手工触发时执行前端测试/构建、Java/Python 测试、HTTP 与浏览器流程，并保存报告。独立 Docker 任务启动开发组合，验证 MySQL 和 Chroma 下的学习与知识库流程，保留容器日志；仅在一次性 CI 环境退出时删除本次项目卷。

## 已执行的 GitHub CI 与开发 Docker

[运行 38067679122](https://github.com/Richard-Lee0419/-EduPath/actions/runs/38067679122) 在 2026-10-11 00:26–00:29（Asia/Shanghai）执行，PR 头提交为 `a0dea7288af58728146ef95a708957816ddad722`，`frontend`、`backend-ai`、`docker-integration` 三项结论均为 `success`。同次 push 的重复运行被并发组取消，取消不计为失败或通过。

- Ubuntu 24.04.5 上从锁文件/依赖配置重新安装，前端 29 项行为断言及静态检查、类型检查、构建通过。
- Java 56 项（失败/错误/跳过均 0）；Python 77 项及脚本 1 项通过。真实 HTTP 与 2 项 Linux Chromium 用例通过。
- 开发 Docker 实际启动前端、Java、AI、MySQL 和 Chroma。容器日志记录 `jdbc:mysql://mysql:3306/edupath (MySQL 8.4)`，18 次 Flyway 迁移成功；MySQL 实际镜像版本为 8.4.11，Chroma 镜像为 0.5.23。
- Docker 学习流程的五个任务成功，三类资源与三题落库、评分及画像/路径 1→2 成功；知识库测试显式核对 `vector_provider=chroma`，上传可检索、重建不重复、Safety 拒绝不入索引、删除不影响另一文档和清理均通过。

脱敏快照见 [CI 汇总](evidence/github-ci.json)、[Docker 学习流程](evidence/docker-learning-flow.json)、[Docker 知识库生命周期](evidence/docker-kb-lifecycle.json)。完整日志、JUnit XML、浏览器报告在上述 run 的 artifacts 中；仓库不复制登录 token 或原始 trace。首轮 Docker 学习脚本的历史通用限制误写“未使用 MySQL/Chroma”；应以其容器上下文与另一个 Chroma 验证记录判断，后续脚本已改为明确记录向量提供方及“数据库类型由外部日志确认”。这属于证据措辞修正，没有改写首轮运行结果。

首轮成功后固定 CI runner 为 Ubuntu 24.04，并更新 artifact action 到已确认存在的 v7，避免 `ubuntu-latest` 即将迁移和 Node 20 弃用提醒；后续运行以 PR 检查为准。开发 Compose 通过不代表生产镜像、OSS、真实 embedding 或线上版本已验证。Flyway 对 MySQL 8.4 的版本提醒和前端 chunk 体积提醒保留。

第二轮 [38068139705](https://github.com/Richard-Lee0419/-EduPath/actions/runs/38068139705)（提交 `cc4cff5`）前端与 backend-ai 通过，但 Docker 在资源任务失败：`KnowledgeAgent did not retrieve course evidence`。代码检查及干扰片段回归重现了“先召回、后过滤”耗尽候选预算的机制。后续把课程/直接及关联知识点筛选移到 Chroma 召回前，保留旧 metadata 格式；本地完整 AI 测试变为 82 项通过，并在 Docker CI 加入 [真实 Chroma 干扰验证](../scripts/smoke_chroma_filters.py)，使用独立临时集合、30 条更相似的无关片段及跨课程片段，核对直接/关联知识点仍召回正确证据后删除集合。该失败没有计为通过；修复后的真实执行结果以 PR 后续检查为准。

## 仍无法确认的内容

- Docker 本机不可用；开发 Compose/MySQL/Chroma 已在 GitHub Linux runner 实际通过。生产镜像、OSS、云部署仍未验证。
- Tabbit CLI 的初次诊断受本机注册限制；未绕过检查或重启用户浏览器。后续使用项目独立安装的 Playwright Chromium 完成 E2E 与录屏。
- 真实模型只完成上述两项小样本；真实 embedding 没有运行。不宣称模型准确率、稳定延迟或检索提升。
- 本次没有创建真实学习效果实验、补造用户反馈或将合成数据视为真实学生。
- 在线实例可能与该工作区不同；本次仅发布分支和草稿 PR，没有合并或部署，不能把线上的行为当成优化后版本证据。
