# 知径 EduPath API Contract Draft

本文档是知径 EduPath 第 1 阶段接口契约初稿，依据项目计划书第 12 章整理。当前版本用于前端、Java 后端和 AI 服务并行开发时对齐接口边界，后续可演进为 OpenAPI/Swagger 文档。

## 1. 总体约定

### 1.1 服务边界

- 前端统一访问 Java 后端的 `/api/*` 接口。
- Java 后端负责鉴权、业务数据、任务状态、资源存储和统一 API。
- AI 服务由 Java 后端编排调用，前端不直接依赖 AI 服务内部接口。
- 长任务必须返回 `task_id`，并支持状态查询和 SSE 流式进度。
- 需要调用 AI 服务、OSS、向量库等外部服务时，Java 后端通过 Adapter/Client 边界调用；外部服务不可用时任务应进入 `failed`，同步接口应返回明确错误，不应静默改成本地生成结果。
- 当前公开 API 版本为 `/api/*`。破坏性变更需要新增版本前缀，例如 `/api/v2/*`，并同步更新 Swagger、前端类型和本文档。
- Swagger/OpenAPI 入口：`/api-docs`，交互式文档：`/swagger-ui.html`。

### 1.2 通用响应格式

成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

错误响应：

```json
{
  "code": 40001,
  "message": "参数错误",
  "data": null
}
```

### 1.3 常用枚举

任务状态：

```text
pending | running | success | failed | cancelled
```

资源类型：

```text
lecture | mindmap | quiz | codelab | animation_script | flowchart | reading
```

难度：

```text
basic | medium | advanced
```

资源发布状态：

```text
draft | published | archived | deleted
```

知识库解析/索引状态：

```text
parse_status: storage_reserved | pending | parsed | failed
index_status: not_indexed | indexing | indexed | failed
```

课程建议编码：

```text
data_structures_algorithms | computer_organization
```

### 1.4 鉴权与角色

除课程列表、资源公开查询、健康检查、登录和刷新接口外，业务接口需要携带 JWT：

```http
Authorization: Bearer <token>
```

SSE 连接也支持 `access_token` 查询参数，用于浏览器 `EventSource` 无法设置 Header 的场景。

当前角色：

| 角色 | 说明 |
|---|---|
| student | 学生，可创建学习任务、答题、查看资源和画像 |
| teacher | 教师，可管理资源和知识库文档 |
| admin | 管理员，可执行教师管理能力 |

### 1.5 错误码

| code | HTTP | 含义 |
|---:|---:|---|
| 40001 | 400 | 参数错误 |
| 40100 | 401 | 未登录或 JWT 无效 |
| 40403 | 403 | 当前角色无权访问 |
| 40404 | 404 | 资源不存在 |
| 50200 | 502 | 外部服务调用失败 |
| 50000 | 500 | 服务端内部错误 |

## 2. 用户与认证

### POST `/api/auth/login`

用户登录。`username` 字段支持用户名或注册邮箱。

请求：

```json
{
  "username": "demo",
  "password": "123456"
}
```

响应 `data`：

```json
{
  "token": "jwt-token",
  "refresh_token": "refresh-token",
  "expires_at": "2026-06-15T21:46:09+08:00",
  "user": {
    "id": 1,
    "username": "demo",
    "role": "student"
  }
}
```

邮箱注册用户的 `user` 会额外返回 `email`；历史种子账号可能不包含该字段。

### POST `/api/auth/register`

公开使用邮箱注册学生账号，并返回与登录接口一致的 JWT 会话。默认角色为 `student`。后端会自动生成内部用户名，注册成功后按 QQ SMTP 配置发送注册邮件。

请求：

```json
{
  "email": "new_student@qq.com",
  "password": "123456"
}
```

响应 `data` 在 `/api/auth/login` 基础上额外包含邮件投递状态：

```json
{
  "token": "jwt-token",
  "refresh_token": "refresh-token",
  "expires_at": "2026-06-15T21:46:09+08:00",
  "user": {
    "id": 4,
    "username": "new_student",
    "email": "new_student@qq.com",
    "role": "student"
  },
  "email_delivery": {
    "sent": true,
    "provider": "qq-smtp",
    "message": "注册邮件已发送"
  }
}
```

如果邮箱已注册，返回 400。生产环境开启 `EMAIL_ENABLED=true` 时，必须配置 QQ 邮箱 SMTP 账号和授权码。

### POST `/api/auth/verify`

校验当前 JWT，并返回用户信息。

### POST `/api/auth/refresh`

使用 `refresh_token` 换取新的访问令牌。旧 refresh token 会被撤销。

### POST `/api/auth/logout`

退出登录。后端会将当前 access token 的 `jti` 写入黑名单，并撤销当前用户的 active refresh token。

## 3. 课程接口

### GET `/api/courses`

返回课程列表。

响应 `data`：

```json
[
  {
    "id": 1,
    "code": "data_structures_algorithms",
    "name": "算法与数据结构",
    "description": "线性表、树、图、排序、查找、递归、动态规划等核心知识"
  },
  {
    "id": 2,
    "code": "computer_organization",
    "name": "计算机组成原理",
    "description": "数据表示、运算器、指令系统、CPU、存储系统、Cache、I/O、流水线"
  }
]
```

### GET `/api/courses/{courseId}/knowledge-points`

返回课程知识点树。

响应 `data`：

```json
[
  {
    "id": 1,
    "name": "算法与数据结构",
    "children": [
      {
        "id": 11,
        "name": "二叉树",
        "difficulty": "medium",
        "children": [
          {
            "id": 111,
            "name": "递归遍历",
            "difficulty": "basic"
          }
        ]
      }
    ]
  }
]
```

### GET `/api/courses/{courseId}/team-members`

查询课程团队成员。

权限：课程 `manager` 或 `admin`。

### POST `/api/courses/{courseId}/team-members`

添加课程团队成员。

权限：课程 `manager` 或 `admin`。

请求：

```json
{
  "user_id": 2,
  "role": "manager"
}
```

### DELETE `/api/courses/{courseId}/team-members/{userId}`

移除课程团队成员。

权限：课程 `manager` 或 `admin`。

### POST `/api/classes`

创建班级，可同时绑定课程。

权限：`teacher` 或 `admin`；绑定课程时还必须是该课程 `manager` 或 `admin`。

请求：

```json
{
  "name": "A3 数据结构一班",
  "description": "软件杯训练班",
  "course_id": 1
}
```

### POST `/api/classes/{classId}/members`

添加班级成员。

请求：

```json
{
  "user_id": 1,
  "role": "student"
}
```

### GET `/api/classes/{classId}/insights`

教师或管理员查询班级聚合学习洞察。`course_id` 可选但必须已经绑定到班级，`window_days` 支持 7～90 天；教师还必须是所选课程的 `manager`。学生访问返回 `403`。

响应从 `class_members`、`learning_events`、`knowledge_mastery`、`quiz_attempts` 和 `resource_interactions` 实时确定性聚合，包含：

- 班级人数、活跃率、掌握度覆盖率、平均掌握度、近期测验均分；
- 每日活动趋势和班级共性薄弱知识点；
- 学生 `critical`、`attention`、`insufficient_data`、`steady` 四级风险信号及触发原因；
- 基于固定阈值生成的教师干预建议。

该接口不调用大模型，不会把证据缺失直接当作低分；`insufficient_data` 与真实高风险状态分离。响应保留 `source.source_tables`、窗口起点与生成时间，便于答辩解释指标口径。

## 4. 知识库接口

### POST `/api/kb/upload`

上传课程文档。请求使用 `multipart/form-data`。

权限：`teacher` 或 `admin`。

表单字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| courseId | number | 是 | 课程 ID |
| file | file | 是 | 课程文档文件 |

响应 `data`：

```json
{
  "document_id": 1001,
  "parse_status": "parsed",
  "index_status": "indexed",
  "storage_provider": "local",
  "bucket": "edupath-demo",
  "object_key": "edupath/knowledge-base/uuid-file.pdf",
  "object_url": null,
  "storage_status": "stored",
  "signed_download_url": {
    "url": "/api/storage/signed?key=edupath/knowledge-base/uuid-file.pdf&expires=1781508152&signature=...",
    "expires_at": "2026-06-15T15:22:32+08:00"
  }
}
```

说明：`storage_provider=local` 时，后端会把上传内容写入 `edupath.storage.local-root` 下的对象路径，并返回本地签名下载链接；`storage_provider=oss` 时，后端通过阿里云 OSS SDK 上传、读取、删除并生成签名下载 URL。上传后 Java 后端会调用 AI 服务 `/kb/ingest`，AI 服务解析 text/PDF/DOCX/PPTX、语义切分、Embedding 并写入当前向量库。

### GET `/api/kb/documents`

查询知识库文档元数据。

查询参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| courseId | number | 否 | 课程 ID |
| status | string | 否 | `parse_status` 过滤 |

### PATCH `/api/kb/documents/{documentId}/status`

更新文档解析/索引状态。

权限：`teacher` 或 `admin`。

请求：

```json
{
  "parse_status": "parsed",
  "index_status": "indexed"
}
```

### POST `/api/kb/documents/{documentId}/reindex`

创建知识库重建索引任务，返回 `task_id`。任务会重新读取对象存储中的文件，调用 AI 服务执行解析、语义切分、Embedding 和向量索引写入，并替换后端 chunk 记录。

权限：`teacher` 或 `admin`。

### GET `/api/kb/documents/{documentId}/download-url`

获取知识库文档签名下载链接。

权限：课程 `manager` 或 `admin`。

### DELETE `/api/kb/documents/{documentId}`

软删除知识库文档。

权限：`teacher` 或 `admin`。

### POST `/api/kb/search`

检索课程知识库。

请求：

```json
{
  "course_id": 1,
  "query": "二叉树递归遍历",
  "top_k": 5
}
```

响应 `data`：

```json
{
  "course_id": 1,
  "query": "二叉树递归遍历",
  "top_k": 5,
  "corpus": {
    "mode": "course_corpus",
    "documents": 66,
    "external_documents": 58,
    "chunks": 1183,
    "vector_store": "local",
    "embedding": "local-hash-v1"
  },
  "results": [
    {
      "chunk_id": "c_001",
      "course_id": 1,
      "knowledge_point_id": 131,
      "knowledge_point": "二叉树递归遍历",
      "related_knowledge_point_ids": [16, 18],
      "title": "二叉树遍历",
      "content": "二叉树遍历包括前序、中序、后序和层序遍历...",
      "score": 0.86,
      "source": "data-structures-algorithms/04-tree-binary-tree.md",
      "author": "课程资料作者或课程组",
      "source_url": "",
      "license": "校内课程资料，仅限获授权的比赛展示与学习使用",
      "license_status": "pending",
      "document_type": "lecture",
      "contains_examples": true,
      "heading_path": ["树与二叉树", "递归遍历"]
    }
  ]
}
```

`corpus.mode=course_corpus` 表示 AI 服务已加载外部课程语料；`builtin_demo` 表示仅加载内置演示片段。前端应展示该状态，不能把演示语料标记为真实课程知识库。`license_status=pending` 的资料可用于本地联调，但比赛交付前必须确认授权边界。

## 5. 学习画像接口

### POST `/api/profile/chat`

用于对话式画像构建。建议支持 SSE 流式输出；普通请求先返回任务 ID。

Java 后端只把本轮消息和课程范围发送到 AI 服务；AI 服务在调用外部模型前必须移除内部 `student_id`、姓名、手机号、邮箱和身份证号。`ProfileAgent` 返回严格结构化画像增量，独立 `SafetyAgent` 对照去标识化原话检查过度推断、身份信息和矛盾。审查未通过时任务失败且不得持久化画像版本。模型异常或结构校验连续失败时不得静默使用模板。

请求：

```json
{
  "message": "我是大二学生，递归不太会，想学好二叉树和 Cache",
  "course_ids": [1, 2]
}
```

响应 `data`：

```json
{
  "task_id": "task_profile_001"
}
```

任务成功后，通过 `GET /api/agent/tasks/{taskId}` 获取的 `result` 包含画像版本以及真实模型运行证据：

```json
{
  "profile": {},
  "version": 3,
  "ai_task_id": "ai_profile_001",
  "generation_mode": "real_model",
  "safety": {
    "passed": true,
    "risk_level": "low",
    "issues": [],
    "suggestions": [],
    "confidence": 0.94
  },
  "model_runtime": {
    "configured": true,
    "real_model_used": true,
    "mode": "real_model",
    "provider": "deepseek",
    "model": "deepseek-chat",
    "call_count": 2,
    "total_tokens": 1200,
    "duration_ms": 8500,
    "calls": []
  }
}
```

### 评估驱动的动态重规划

学生提交小测后，Java 后端在确定性判分、EvaluationAgent 评估和画像回写完成后，自动调用 AI 内部接口 `POST /path/replan`。该接口不直接暴露给前端，且只允许重规划未来 `1～3` 天，防止一次测评推翻整条长期路径。

重规划输入必须包含权威 `overall_score`、实际错题得出的 `weak_points`、`mistake_patterns`、`next_actions`、评估 `source_task_id`、当前路径和最新去标识化画像。低于 70 分时，PathAgent 必须把真实薄弱点放在第一天且不得从 `advanced` 难度开始；所有新任务仍需引用 RAG 证据并通过 SafetyAgent。成功后保存新的 `learning_paths.version`，同时记录 `previous_path_id`、`previous_version`、`source_evaluation_task_id`、`replan_trigger=quiz_evaluation` 和结构化 `replan_changes`。

### GET `/api/profile/current`

获取当前学生画像。

响应 `data`：

```json
{
  "profile": {
    "student_id": "demo",
    "major": "计算机科学与技术",
    "grade": "大二",
    "target_courses": ["data_structures_algorithms", "computer_organization"],
    "knowledge_base": {
      "programming": "medium",
      "math": "medium",
      "digital_logic": "beginner",
      "algorithm": "medium",
      "computer_organization": "beginner"
    },
    "course_progress": [
      {
        "course": "算法与数据结构",
        "chapter": "二叉树",
        "status": "weak"
      }
    ],
    "learning_goal": "两周内掌握二叉树、递归和 Cache 基础",
    "cognitive_style": ["visual", "example_first", "step_by_step"],
    "weak_points": ["递归调用栈", "Cache 映射方式"],
    "mistake_patterns": ["concept_confusion", "process_gap"],
    "resource_preference": ["mindmap", "code", "quiz", "animation"],
    "learning_pace": "每天 40 分钟",
    "confidence_score": 0.82,
    "updated_reason": "由画像对话生成"
  },
  "version": 3
}
```

## 6. Agent 任务接口

### POST `/api/agent/resource-task`

创建资源生成任务。

请求：

```json
{
  "course_id": 1,
  "knowledge_points": ["二叉树", "递归遍历"],
  "goal": "理解二叉树递归遍历并能写代码",
  "resource_types": ["lecture", "mindmap", "quiz", "codelab", "animation_script"],
  "difficulty": "basic"
}
```

Java 后端会根据当前登录用户自动读取最新画像版本，并把 `student_profile` 快照传给 AI 服务。前端不得自行提交或覆盖 `student_id`。画像快照包含学习目标、知识基础、薄弱点、错因、资源偏好、认知风格、学习节奏和最近测验结果；空画像保持为空，不使用演示用户默认值。外部模型只接收学习目标、薄弱点、资源偏好、认知风格、错因、学习节奏、最近测验结果等去标识化字段，不发送用户 ID、姓名、手机号或邮箱。

Java 后端通过 AI 服务内部 `POST /resource/tasks` 创建异步任务，再轮询 `GET /tasks/{task_id}`，把实际 Agent 步骤映射到外部任务；兼容接口 `POST /resource/generate` 仅保留给同步调试。`ResourcePlannerAgent` 根据画像调整资源顺序和有效难度，Lecture、Mindmap、Quiz、Codelab、AnimationScript 等资源 Agent 分别调用模型并执行结构校验；所有资源返回 `personalized_reason`、`estimated_minutes`、`evidence_chunk_ids` 与不含敏感信息的 `profile_fingerprint`。SafetyAgent 使用同一批 RAG 证据独立复核。真实模型模式下，若首次复核未通过，编排器最多执行一轮 SafetyAgent 反馈驱动的完整资源返修，并再次独立复核。最终资源由 `resource-quality-v1` 确定性评估器计算证据覆盖、结构完整、知识一致、难度匹配和安全审查五项分数，形成 `quality_evaluation`。当 SafetyAgent 已通过但部分资源未通过质量门禁时，编排器只把这些资源的失败维度、发现和建议反馈给对应资源 Agent，最多执行一轮定向修订；已合格资源保持不变。定向修订后必须再次执行 SafetyAgent 和五维量化复评。只有 SafetyAgent 通过、质量总分不低于 75，且五个维度均不低于 60 的资源才通过质量门禁并自动发布；否则只能保存为 `draft`。Java 后端持久化完整评分快照，便于回归比较。

响应 `data`：

```json
{
  "task_id": "task_resource_9f6d0c1b7a2e4f2d8c1a0b3e4d5f6a7b"
}
```

随后通过 `GET /api/agent/tasks/{taskId}` 查询到任务完成时，响应还包含 `result`。其中 `generation_mode` 和 `model_runtime` 用于证明本次任务是否真正调用外部模型；不得依据“已配置 API Key”推断真实调用成功。

```json
{
  "task_id": "task_resource_9f6d0c1b7a2e4f2d8c1a0b3e4d5f6a7b",
  "status": "success",
  "progress": 100,
  "result": {
    "resources": [
      {
        "resource_id": "res_001",
        "title": "二叉树递归遍历个性化讲义",
        "quality_evaluation": {
          "evaluator_version": "resource-quality-v1",
          "total_score": 92.4,
          "grade": "A",
          "gate_passed": true,
          "dimensions": {
            "evidence_coverage": {"score": 95, "weight": 0.3, "passed": true, "findings": []},
            "structural_completeness": {"score": 90, "weight": 0.2, "passed": true, "findings": []},
            "knowledge_consistency": {"score": 100, "weight": 0.2, "passed": true, "findings": []},
            "difficulty_alignment": {"score": 80, "weight": 0.15, "passed": true, "findings": []},
            "safety_review": {"score": 99, "weight": 0.15, "passed": true, "findings": []}
          },
          "issues": [],
          "recommendations": []
        }
      }
    ],
    "generation_mode": "real_model",
    "ai_task_id": "task_ai_resource_a21f7c9d",
    "safety": {
      "passed": true,
      "risk_level": "low",
      "issues": [],
      "suggestions": [],
      "confidence": 0.96
    },
    "model_runtime": {
      "configured": true,
      "real_model_used": true,
      "mode": "real_model",
      "provider": "deepseek",
      "model": "deepseek-v4-flash",
      "call_count": 6,
      "total_tokens": 8420,
      "duration_ms": 18450,
      "quality_revision_rounds": 2,
      "safety_feedback_revision_rounds": 1,
      "quality_feedback_revision_rounds": 1,
      "quality_revised_resource_count": 1,
      "calls": [
        {
          "agent": "LectureAgent",
          "provider": "deepseek",
          "model": "deepseek-v4-flash",
          "duration_ms": 3210,
          "total_tokens": 1630
        }
      ]
    }
  }
}
```

### GET `/api/agent/tasks`

查询当前登录用户创建的任务，供任务中心统一展示、定位失败和恢复执行。普通用户只能看到自己的任务；管理员仍需通过明确的任务 ID 才能排障其他用户任务。

查询参数：`status`（可选，`pending`、`running`、`success`、`failed`、`cancelled`）、`page`（默认 1）、`size`（默认 20，最大 50）。

响应 `data`：

```json
{
  "items": [
    {
      "task_id": "task_resource_9f6d0c1b7a2e4f2d8c1a0b3e4d5f6a7b",
      "domain": "resource",
      "operation_type": "resource_task",
      "status": "failed",
      "progress": 42,
      "current_agent": null,
      "error_message": "AI 服务暂时不可用",
      "actor_username": "demo",
      "retryable": true,
      "created_at": "2026-07-16T19:00:00+08:00",
      "updated_at": "2026-07-16T19:00:04+08:00"
    }
  ],
  "total": 1,
  "page": 1,
  "size": 20
}
```

### GET `/api/agent/tasks/{taskId}`

查询任务状态。

响应 `data`：

```json
{
  "task_id": "task_resource_9f6d0c1b7a2e4f2d8c1a0b3e4d5f6a7b",
  "status": "running",
  "progress": 65,
  "current_agent": "QuizAgent",
  "steps": [
    {
      "agent": "ProfileAgent",
      "status": "success",
      "message": "已读取学生画像"
    },
    {
      "agent": "KnowledgeAgent",
      "status": "success",
      "message": "已检索课程知识库"
    },
    {
      "agent": "LectureAgent",
      "status": "success",
      "message": "已生成个性化讲义"
    },
    {
      "agent": "QuizAgent",
      "status": "running",
      "message": "正在生成分层题库"
    }
  ]
}
```

### GET `/api/agent/tasks/{taskId}/stream`

SSE 流式返回任务进度。

事件示例：

```text
event: agent_step
data: {"agent":"KnowledgeAgent","status":"running","message":"正在检索课程知识库"}

event: done
data: {"task_id":"task_resource_9f6d0c1b7a2e4f2d8c1a0b3e4d5f6a7b"}
```

### POST `/api/agent/tasks/{taskId}/cancel`

取消未结束任务。已进入 `success`、`failed`、`cancelled` 的任务不会再变更。

### POST `/api/agent/tasks/{taskId}/retry`

重试失败或已取消任务。当前支持 `resource_task`、`profile_chat`、`path_generate`、`tutor_chat`、`kb_reindex`、`quality_regression`，重试会创建新的 `task_id`，不会覆盖原任务记录和既有业务结果。任务创建者与管理员可以按任务 ID 操作，其他用户返回 403。

## 7. 资源接口

### GET `/api/resources`

获取资源列表。

查询参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| courseId | number | 否 | 课程 ID |
| course_id | number | 否 | 课程 ID，兼容 snake_case |
| type | string | 否 | 资源类型 |
| status | string | 否 | `draft`、`published`、`archived` |
| keyword | string | 否 | 标题/摘要关键词 |
| sort | string | 否 | `created_at`、`updated_at`、`title` |
| page | number | 否 | 页码 |
| size | number | 否 | 每页条数 |

响应 `data`：

```json
{
  "items": [
    {
      "resource_id": "res_001",
      "title": "二叉树递归遍历个性化讲义",
      "resource_type": "lecture",
      "course_id": 1,
      "knowledge_points": ["二叉树", "递归遍历"],
      "difficulty": "basic",
      "summary": "优先补强递归调用栈；匹配你偏好的思维导图形式；最近测验 48 分，采用基础梯度。",
      "status": "published",
      "created_at": "2026-06-05T00:00:00+08:00",
      "object_key": "edupath/resources/res_001.md",
      "object_url": null,
      "storage_status": "stored",
      "generation_mode": "real_model"
    }
  ],
  "page": 1,
  "size": 10,
  "total": 1
}
```

携带有效学生令牌时，只返回当前学生自己生成的个性化资源；教师、管理员和未登录的公开课程资源查询保持原有范围。

### GET `/api/resources/quality-metrics`

从 Java 持久化的 `resources.quality_evaluation` 质量快照聚合资源质量趋势、分布和确定性回归告警，不使用前端模拟数据。学生仅统计自己的资源，教师和管理员按可见范围统计。

查询参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| course_id | number | 否 | 课程 ID |
| resource_type | string | 否 | 资源类型 |
| generation_mode | string | 否 | `real_model`、`deterministic_fallback` 或 `unknown`；历史资源未记录时为 `unknown` |
| window_days | number | 否 | 统计窗口，默认 30 天，范围 1-365 天 |

响应 `data`：

```json
{
  "source": {
    "source_table": "resources.quality_evaluation",
    "grain": "一条已生成资源的一次确定性质量评分快照",
    "freshness_at": "2026-07-16T10:30:00+08:00",
    "window_days": 30,
    "eligible_resources": 12,
    "evaluated_resources": 12
  },
  "summary": {
    "total_resources": 12,
    "evaluated_resources": 12,
    "evaluation_coverage": 1.0,
    "average_score": 88.4,
    "gate_pass_rate": 0.92,
    "alert_count": 1
  },
  "trend": [
    {"date": "2026-07-16", "resource_count": 5, "average_score": 89.2, "gate_pass_rate": 1.0}
  ],
  "by_resource_type": [
    {"key": "lecture", "resource_count": 4, "average_score": 91.5, "gate_pass_rate": 1.0}
  ],
  "by_generation_mode": [
    {"key": "real_model", "resource_count": 10, "average_score": 89.1, "gate_pass_rate": 0.9}
  ],
  "dimensions": [
    {"dimension": "evidence_coverage", "label": "证据覆盖", "resource_count": 12, "average_score": 86.5, "pass_rate": 0.92}
  ],
  "alerts": [
    {
      "code": "DIMENSION_SCORE_LOW",
      "severity": "warning",
      "title": "证据覆盖维度低于基线",
      "message": "该维度连续样本均值偏低，建议检查生成提示词、RAG 证据和结构校验。",
      "observed_value": 68.5,
      "threshold": 70.0,
      "dimension": "evidence_coverage"
    }
  ],
  "filters": {"course_id": 1, "window_days": 30},
  "metric_definitions": [
    {"key": "average_score", "label": "平均质量分", "definition": "评测样本 total_score 的算术平均值"}
  ]
}
```

告警规则保持确定性：至少 3 条资源时评测覆盖率低于 80%或门禁通过率低于 70%触发告警；同一质量维度至少 2 个样本且均分低于 70 触发维度告警；前后半窗口各至少 2 个样本且近期均分下降 5 分以上触发回归告警。样本不足时不把波动误报为回归。

### POST `/api/resources/quality-regression-tasks`

基于当前质量告警创建小批量异步回归任务。任务从真实资源表中按低分优先选择最多 5 条资源（请求可调整但硬上限为 10），使用 AI 服务中的当前 `ResourceQualityEvaluator` 重新评分，再与持久化的生成时基线比较。该操作采用 `audit_only` 策略，只读评测，不修改资源正文、状态或发布结果。

普通学生只能在自己的资源触发真实告警时执行；没有告警返回 `409`。教师或管理员可以传 `force: true` 执行人工基线核验。

请求：

```json
{
  "course_id": 1,
  "resource_type": "lecture",
  "generation_mode": "real_model",
  "window_days": 30,
  "max_resources": 5,
  "force": false
}
```

响应：

```json
{
  "task_id": "task_quality_regression_..."
}
```

通过 `GET /api/agent/tasks/{taskId}` 或 SSE 查询进度，Agent 顺序为 `QualityMonitorAgent → QualityEvaluator → RegressionGuardAgent`。成功任务的 `result`：

```json
{
  "trigger_alert_codes": ["DIMENSION_SCORE_LOW"],
  "inspected_resources": 5,
  "regression_count": 1,
  "stable_count": 4,
  "action_required": true,
  "regressed_resource_ids": ["res_001"],
  "queued_repair_ids": ["repair_6f1d..."],
  "automation_policy": "audit_only",
  "policy_description": "任务只重新评测和比较，不修改资源内容、状态或发布结果。",
  "results": [
    {
      "resource_id": "res_001",
      "evaluator_version": "resource-quality-v1",
      "score_delta": -8.5,
      "gate_changed": true,
      "evaluator_changed": false,
      "regressed_dimensions": ["evidence_coverage"],
      "regression_detected": true,
      "baseline_evaluation": {},
      "current_evaluation": {}
    }
  ],
  "completed_at": "2026-07-16T11:00:00+08:00"
}
```

回归判定保持确定性：总分下降至少 5 分、门禁从通过变为不通过，或任一维度下降至少 5 分/从通过变为不通过，任一条件成立即标记该资源发生回归。任务失败后可复用通用 `/api/agent/tasks/{taskId}/retry` 接口重试。

检测到回归时，Java 后端使用“资源 ID + 基线评测 + 当前评测 + 回归维度”生成稳定去重键，并将该条结果写入受控修复队列。重复执行相同回归不会重复创建修复项。

### GET `/api/resources/quality-repairs`

查询当前用户可见的资源质量修复队列。学生只能查看自己资源的队列项；教师只能查看其负责课程；管理员可查看全部。教师和管理员对 `pending_review` 项获得 `can_review: true`。

查询参数：`course_id`（可选）、`status`（可选，支持 `pending_review`、`approved`、`rejected`、`in_progress`、`completed`、`failed`、`published`、`rolled_back`）、`page`、`size`（最大 20）。

响应 `data`：

```json
{
  "items": [
    {
      "repair_id": "repair_6f1d...",
      "resource_id": "res_001",
      "resource_title": "二叉树递归遍历个性化讲义",
      "resource_type": "lecture",
      "source_task_id": "task_quality_regression_...",
      "course_id": 1,
      "trigger_alert_codes": ["DIMENSION_SCORE_LOW"],
      "regressed_dimensions": ["evidence_coverage"],
      "baseline_evaluation": {"total_score": 92.4, "gate_passed": true},
      "current_evaluation": {"total_score": 82.4, "gate_passed": false},
      "score_delta": -10.0,
      "status": "pending_review",
      "can_review": true,
      "can_execute": false,
      "execution_task_id": null,
      "candidate_quality_evaluation": {},
      "candidate_generation_mode": null,
      "publish_ready": false,
      "candidate_base_version": null,
      "can_compare": false,
      "can_publish": false,
      "can_rollback": false,
      "execution_error": null,
      "executed_at": null,
      "reviewed_by": null,
      "review_note": null,
      "reviewed_at": null,
      "created_at": "2026-07-16T11:05:00+08:00",
      "updated_at": "2026-07-16T11:05:00+08:00"
    }
  ],
  "page": 1,
  "size": 10,
  "total": 1
}
```

### PATCH `/api/resources/quality-repairs/{repairId}/decision`

教师或管理员对其有管理权限的课程修复项执行人工批准或驳回。每个修复项只能从 `pending_review` 审核一次，重复审核返回 `409`。驳回时 `note` 必填，最长 500 字。

请求：

```json
{
  "decision": "approve",
  "note": "确认评测回归，允许进入定向修复流程。"
}
```

响应 `data` 返回更新后的 `item`、`next_action`（批准为 `ready_for_controlled_repair`，驳回为 `closed`）和 `safety_notice`。人工批准只改变修复项状态，不修改资源正文、发布状态或历史质量快照。

### POST `/api/resources/quality-repairs/{repairId}/execute`

教师或管理员为其有课程管理权限且状态为 `approved` 的单个修复项创建异步定向修复任务。学生无权启动；同一修复项被占用、已完成或未批准时返回 `409`。响应：

```json
{
  "repair_id": "repair_6f1d...",
  "task_id": "task_quality_repair_...",
  "status": "in_progress"
}
```

任务 Agent 顺序为 `RepairPlannerAgent → 对应资源 Agent → SafetyAgent → QualityEvaluator`。Java 后端调用 AI 服务内部 `POST /resource/repair`，只发送原资源、原始 RAG 证据、基线/当前评测和已确认的回归维度。AI 服务基于原资源执行单资源定向修订，并重新运行 SafetyAgent 与 `resource-quality-v1` 五维门禁。

任务成功后的 `result`：

```json
{
  "repair_id": "repair_6f1d...",
  "resource_id": "res_001",
  "candidate_title": "二叉树递归遍历个性化讲义",
  "candidate_summary": "已补充证据引用与边界例题",
  "quality_evaluation": {"total_score": 94.2, "gate_passed": true},
  "safety": {"passed": true, "risk_level": "low"},
  "model_runtime": {"mode": "real_model", "real_model_used": true},
  "recovery_score_delta": 10.3,
  "improved_dimensions": ["evidence_coverage"],
  "target_dimensions_passed": true,
  "publish_ready": true,
  "original_resource_unchanged": true,
  "next_action": "manual_publish_review"
}
```

候选版本必须同时满足：SafetyAgent 通过、五维质量门禁通过、全部目标回归维度恢复，并且总分相对当前评测恢复至少 5 分或达到原基线分数，才可标记 `publish_ready=true`。候选正文、审查结果、评测和模型运行信息独立存入修复队列；无论是否通过，均不得覆盖原资源、改变发布状态、增加资源版本号或写入对象存储。未通过时 `next_action=inspect_quality_rejection`，原资源保持不变。

### GET `/api/resources/quality-repairs/{repairId}/comparison`

教师或管理员读取原资源与隔离候选的人工发布对比。响应包含 `original_resource`、`candidate_resource`、当前/候选质量评测、`changed_fields`、`current_resource_version`、`candidate_base_version`、`stale` 和 `can_publish`。若当前资源版本已经偏离候选生成时的源版本，返回 `stale=true` 且禁止发布。

### POST `/api/resources/quality-repairs/{repairId}/publish`

教师或管理员确认对比后发布 `publish_ready=true` 的候选版本。请求：

```json
{
  "expected_resource_version": 3,
  "note": "已核对正文、证据与五维质量变化。"
}
```

发布操作使用源版本乐观锁，并在同一数据库事务内保存发布前完整快照、写入资源历史版本、更新候选正文/安全审查/质量评测和修复项审计状态。任一状态或版本不一致均返回 `409`，不覆盖他人修改。成功响应包含 `status=published`、`from_version`、`to_version`、`atomic=true`。

### POST `/api/resources/quality-repairs/{repairId}/rollback`

教师或管理员可回滚该修复发布。只有资源仍停留在该修复产生的发布版本时才允许回滚；系统把发布前完整快照恢复为一个新的递增版本，不倒退版本号。若发布后已有其他更新则返回 `409`。成功响应包含 `status=rolled_back`、原发布版本和新的回滚版本，并保留发布人与回滚人的备注和时间审计记录。

### GET `/api/resources/{resourceId}`

获取资源详情。

响应 `data`：

```json
{
  "resource_id": "res_001",
  "title": "二叉树递归遍历个性化讲义",
  "resource_type": "lecture",
  "content_format": "markdown",
  "content": "## 二叉树递归遍历\n\n...",
  "evidence": [
    {
      "chunk_id": "c_001",
      "title": "二叉树遍历",
      "score": 0.86
    }
  ],
  "safety": {
    "passed": true,
    "risk_level": "low",
    "issues": [],
    "suggestions": [],
    "confidence": 0.88
  },
  "quality_evaluation": {
    "evaluator_version": "resource-quality-v1",
    "total_score": 92.4,
    "grade": "A",
    "gate_passed": true,
    "dimensions": {
      "evidence_coverage": {"score": 95, "weight": 0.3, "passed": true, "findings": []},
      "structural_completeness": {"score": 90, "weight": 0.2, "passed": true, "findings": []},
      "knowledge_consistency": {"score": 100, "weight": 0.2, "passed": true, "findings": []},
      "difficulty_alignment": {"score": 80, "weight": 0.15, "passed": true, "findings": []},
      "safety_review": {"score": 99, "weight": 0.15, "passed": true, "findings": []}
    },
    "issues": [],
    "recommendations": []
  },
  "object_key": "edupath/resources/res_001.md",
  "object_url": null,
  "storage_status": "stored",
  "generation_mode": "real_model"
}
```

资源列表与详情同时返回以下个性化字段：

- `personalized_reason`：本资源与当前生成画像的匹配依据；
- `estimated_minutes`：预计学习时间；
- `profile_fingerprint`：非敏感画像维度生成的稳定指纹，用于验证个性化差异；
- `quality_evaluation`：资源生成时的确定性质量评分快照，包含总分、等级、门禁结果、五个维度及改进建议；资源列表和详情均返回该字段。
- `generation_mode`：该资源生成时实际记录的模型执行模式；迁移前历史资源明确返回 `unknown`。

### POST `/api/resources/{resourceId}/interactions`

记录当前登录用户的真实学习行为。学生只能操作自己的资源。

```json
{
  "action": "view | start | complete | skip | favorite | rate",
  "rating": 5,
  "progress_percent": 100,
  "event_id": "client-event-uuid（可选，用于客户端重试幂等）"
}
```

返回当前用户在该资源上的累计交互次数、最高进度、最近评分、最后交互时间和 `learning_update`。资源完成会生成掌握度证据；`view`、`favorite`、`rate` 等行为只留审计事件，不直接提高掌握度。同一 `event_id` 或同一资源、动作、进度组合重复提交时，`duplicate_events` 增加但不重复加权。

### PATCH `/api/resources/{resourceId}/status`

更新资源发布状态。

权限：课程 `manager`、`admin` 或资源 owner。

请求：

```json
{
  "status": "published"
}
```

### POST `/api/resources/{resourceId}/archive`

归档资源。

权限：课程 `manager`、`admin` 或资源 owner。

### DELETE `/api/resources/{resourceId}`

软删除资源。

权限：课程 `manager`、`admin` 或资源 owner。

### GET `/api/resources/{resourceId}/download-url`

获取资源对象签名下载链接。

## 8. 学习路径接口

### POST `/api/path/generate`

生成学习路径。

请求：

```json
{
  "course_ids": [1, 2],
  "target": "两周内掌握二叉树和 Cache 基础",
  "days": 14,
  "daily_minutes": 40
}
```

Java 后端同样会自动附加当前登录用户的最新 `student_profile`，路径生成不接受前端伪造画像。

AI 服务只向外部模型发送去标识化画像维度和本次检索的课程证据。PathAgent 必须返回严格结构化的连续日计划，每天任务总时长不得超过 `daily_minutes`，并保存真实 `evidence_chunk_ids`。独立 SafetyAgent 检查时间预算、个性化依据、课程事实与证据引用；未通过时 Java 任务失败且不得持久化新路径版本。真实模型运行记录通过任务结果返回，不得根据“已配置 Key”推断调用成功。

响应 `data`：

```json
{
  "task_id": "task_path_001"
}
```

任务成功后的 `result` 包含新路径版本和模型运行证据：

```json
{
  "path_id": "path_001",
  "version": 2,
  "generation_mode": "real_model",
  "safety": {
    "passed": true,
    "risk_level": "low",
    "issues": [],
    "suggestions": [],
    "confidence": 0.95
  },
  "model_runtime": {
    "configured": true,
    "real_model_used": true,
    "mode": "real_model",
    "provider": "deepseek",
    "model": "deepseek-v4-flash",
    "call_count": 2,
    "total_tokens": 2600,
    "duration_ms": 15000,
    "calls": []
  }
}
```

### GET `/api/path/current`

获取当前学习路径。

响应 `data`：

```json
{
  "path_id": "path_001",
  "path_title": "14 天二叉树与 Cache 补强路径",
  "target": "两周内掌握二叉树和 Cache 基础",
  "personalization_summary": "优先处理递归调用栈、Cache 映射方式；偏好思维导图、代码实验；最近测验48分",
  "daily_plan": [
    {
      "day": 1,
      "theme": "递归基础与调用栈",
      "difficulty": "basic",
      "reason": "最近测验未达 70 分，首日先安排补救；优先补强递归调用栈；匹配你偏好的思维导图形式。",
      "tasks": [
        {
          "type": "lecture",
          "resource_id": "res_001",
          "title": "递归调用栈图解",
          "estimated_minutes": 15
        },
        {
          "type": "quiz",
          "resource_id": "res_002",
          "title": "递归基础 5 题",
          "estimated_minutes": 10
        }
      ],
      "expected_outcome": "能画出简单递归函数的调用栈",
      "evidence_chunk_ids": ["ai_chunk_1_131_example"]
    }
  ],
  "adjustment_strategy": "如果递归题正确率低于 70%，自动插入补救资源。",
  "evidence_chunk_ids": ["ai_chunk_1_131_example"],
  "completed_days": [1],
  "adjustment_signal": {
    "path_available": true,
    "status": "watch",
    "should_replan": false,
    "replan_candidate": true,
    "risk_score": 4,
    "reasons": ["最近连续 3 次有效学习证据低于 65 分", "仍处于路径调整冷却期"],
    "metrics": {
      "consecutive_low_evidence": 3,
      "low_mastery_point_count": 1,
      "inactivity_hours": 2,
      "completion_rate": 33
    },
    "pacing": {
      "action": "reduce",
      "current_daily_minutes": 40,
      "recommended_daily_minutes": 35
    },
    "cooldown": {
      "eligible": false,
      "remaining_minutes": 180,
      "window_minutes": 360
    },
    "evaluated_at": "2026-07-15T12:00:00+08:00"
  }
}
```

`adjustment_signal` 是确定性规则判定结果，不调用大模型。状态为 `stable`、`watch` 或 `replan_recommended`：风险分达到 3 且 6 小时冷却期结束时，`should_replan=true`。当前规则综合最近有效学习证据、低掌握度知识点、连续未学习时长，以及路径运行超过 48 小时但完成率低于 35% 的进度滞后。后续受控重规划模块只能消费 `should_replan=true` 的信号，并需再次校验幂等与冷却期。

### GET `/api/path/adjustment-signal`

独立查询当前学生的行为驱动路径调整判定。响应 `data` 与 `GET /api/path/current` 中的 `adjustment_signal` 完全一致；尚未生成路径时返回 `path_available=false`、`status=stable` 和 `should_replan=false`。

### POST `/api/path/replan/behavior`

根据确定性行为信号创建受控的路径重规划任务。仅当服务端重新计算得到 `should_replan=true` 时接受请求；前端不能提交或伪造风险分。响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "task_id": "task_path_7d4f...",
    "reused": false,
    "trigger_key": "behavior_replan:student:path_001:v1:...",
    "signal": {
      "status": "replan_recommended",
      "should_replan": true,
      "risk_score": 5
    }
  }
}
```

`trigger_key` 由学生、当前路径 ID/版本和确定性风险快照生成。同一触发键对应固定 `task_id`，重复请求返回 `reused=true`，不会再次调用模型。任务流程为 `BehaviorSignalAgent → ProfileAgent → KnowledgeAgent → PathAgent → SafetyAgent`。

执行前会再次校验信号和路径版本；AI 服务必须回传相同的 `behavior_trigger_key`、`trigger=behavior_signal`、结构化三日以内路径、模型运行记录和通过的 SafetyAgent 结果。模型失败、SafetyAgent 拒绝、信号失效或路径版本并发变化时，任务进入 `failed`，原路径保持不变。只有全部校验通过后，才以新版本写入 `learning_paths`。

### POST `/api/path/nodes/{day}/complete`

完成当前路径中的指定日节点。后端以 `path_id + day` 作为幂等边界，记录 `path_node_completion` 事件，并用中等权重更新该节点主题对应的掌握度。响应包含 `completed_days` 和统一的 `learning_update`；重复完成不会重复加权。

### GET `/api/learning/events?page=1&size=20`

查询当前学生的学习事件时间线。事件包括资源阅读、代码实验、辅导提问/追问和路径节点完成。仅后端业务接口可以创建掌握度事件，不开放通用前端写入接口，防止客户端伪造学习证据。

连续掌握度使用确定性加权证据：测验每次权重为 `10`，资源完成、代码实验、路径完成和辅导追问权重为 `1-3`。计算形式为 `(测验正确证据分 + 行为证据分) / (测验权重 + 行为权重)`；测验仍是最高权重证据，行为只能连续校准，不能替代评估。

生产环境的画像抽取、资源生成、学习路径与智能辅导必须调用已配置的 OpenAI-compatible LLM，并将去标识化用户画像与必要的 RAG 证据片段传入模型。模型异常、返回非 JSON、结构不合法或超时必须让任务失败并返回错误，不允许静默退回统一模板。确定性 Agent 仅供未配置 LLM 的开发环境和自动化测试使用；其结果必须标记为 `deterministic_fallback`，不得作为“真实模型演示”证据。所有生成结果仍须通过 SafetyAgent 审查。

## 9. 智能辅导接口

### POST `/api/tutor/chat`

创建课程内智能辅导任务。

`answer_mode` 可选值为 `step_by_step`、`summary`、`code_first`、`hint`、`socratic`。问题在发送给外部模型前必须去标识化；执行顺序固定为 `KnowledgeAgent → TutorAgent → SafetyAgent`。

请求：

```json
{
  "course_id": 1,
  "question": "为什么二叉树前序遍历要先访问根节点？",
  "answer_mode": "step_by_step",
  "session_id": "tutor_a91f...（追问时传入，首次提问省略）"
}
```

响应 `data`：

```json
{
  "task_id": "task_tutor_001"
}
```

辅导任务结果可通过 `GET /api/agent/tasks/{taskId}` 和 `GET /api/agent/tasks/{taskId}/stream` 查询。

任务成功时，`result` 至少包含：

```json
{
  "session_id": "tutor_a91f...",
  "source_task_id": "ai_tutor_001",
  "answer": "前序遍历按照根、左子树、右子树的顺序访问。",
  "answer_markdown": "前序遍历按照根、左子树、右子树的顺序访问。",
  "steps": ["确认遍历定义", "跟踪递归调用", "用空树检查递归出口"],
  "citations": [
    {
      "answer_fragment": "前序遍历按照根、左子树、右子树的顺序访问",
      "evidence_chunk_ids": ["ai_chunk_1_131_example"]
    }
  ],
  "confidence": 0.9,
  "evidence": [
    {
      "chunk_id": "ai_chunk_1_131_example",
      "title": "二叉树递归遍历例题",
      "content": "前序遍历先访问根节点，再递归访问左子树和右子树。",
      "score": 0.94,
      "source": "courseware/tree-traversal.pdf"
    }
  ],
  "safety": {
    "passed": true,
    "risk_level": "low",
    "issues": [],
    "suggestions": [],
    "confidence": 0.95
  },
  "generation_mode": "real_model",
  "model_runtime": {
    "mode": "real_model",
    "provider": "deepseek",
    "model": "deepseek-chat",
    "call_count": 2,
    "total_tokens": 1200,
    "duration_ms": 8000,
    "calls": []
  },
  "learning_update": {
    "recorded_events": 1,
    "duplicate_events": 0,
    "event_ids": ["learn_..."],
    "mastery_updates": [],
    "profile_update": {"updated": false}
  }
}
```

`citations[].answer_fragment` 必须逐字存在于 `answer_markdown`，其 `evidence_chunk_ids` 必须来自本次真实检索结果。首次提问只记录 `tutor_question` 审计事件；携带本人同课程 `session_id` 的有效追问记录轻量掌握度证据。AI 服务返回的结构、引用、隐私或 SafetyAgent 结果不合格时任务必须失败，Java 后端不得创建辅导会话或保存助手回答。未配置模型的开发环境结果必须标记为 `deterministic_fallback`。

## 10. 测试与评估接口

### POST `/api/quiz/generate`

创建基于课程 RAG 的小测生成任务。当前黄金闭环每次只允许 `1-3` 道单选题，前端默认请求 3 道；超过 3 道必须返回参数错误，不允许静默扩大题量。

请求：

```json
{
  "course_id": 1,
  "knowledge_points": ["二叉树", "递归遍历"],
  "difficulty": "basic",
  "question_count": 3
}
```

响应 `data`：

```json
{
  "task_id": "task_quiz_001"
}
```

任务结果通过 `GET /api/agent/tasks/{taskId}` 或 SSE 查询。成功结果包含 `quiz_id`、`title`、恰好 1-3 个 `questions`、`evidence`、`safety`、`generation_mode`、`model_runtime` 和 AI `source_task_id`。学生作答前的题目结构不得返回 `answer` 或 `explanation`；每题必须返回用于审计的 `evidence_chunk_ids`。

生成顺序固定为 `KnowledgeAgent → QuizAgent → SafetyAgent`。QuizAgent 输出必须经过封闭结构、题量、选项唯一性、单一正确答案和真实 chunk 引用校验；不合格结果不得落库。

### POST `/api/quiz/submit`

提交答案并创建评分—评估—画像回写任务。客观题分数由 Java 确定性评分计算，EvaluationAgent 无权修改分数、各知识点正确数或薄弱点集合。

请求：

```json
{
  "quiz_id": 1001,
  "answers": [
    {
      "question_id": 1,
      "answer": "A"
    },
    { "question_id": 2, "answer": "B" },
    { "question_id": 3, "answer": "D" }
  ]
}
```

响应 `data`：

```json
{
  "task_id": "task_evaluation_001"
}
```

成功任务结果至少包含：

```json
{
  "quiz_id": 1001,
  "score": 67,
  "correct_count": 2,
  "total_count": 3,
  "question_results": [
    {
      "question_id": 1,
      "knowledge_point": "二叉树递归遍历",
      "submitted_answer": "A",
      "correct_answer": "A",
      "correct": true,
      "explanation": "课程证据支持该访问顺序。"
    }
  ],
  "evaluation": {
    "overall_score": 67,
    "mastery": [],
    "weak_points": ["递归调用栈"],
    "mistake_patterns": ["process_gap"],
    "next_actions": ["复习递归调用栈证据并完成错题复盘"],
    "evidence_chunk_ids": ["ai_chunk_1_131_example"]
  },
  "profile_update": {
    "updated": true,
    "version": 3,
    "latest_quiz_score": 67,
    "weak_points": ["递归调用栈"]
  },
  "path_update": {
    "updated": true,
    "path_id": "path_replanned_001",
    "version": 4,
    "previous_version": 3,
    "trigger": "quiz_evaluation",
    "source_evaluation_task_id": "ai_evaluation_001",
    "changes": [
      {
        "action": "insert_remediation",
        "knowledge_point": "递归调用栈",
        "reason": "本次测评得分 67，将薄弱点提前到未来三天内补救并复测。"
      }
    ]
  },
  "generation_mode": "real_model",
  "model_runtime": {},
  "safety": { "passed": true }
}
```

提交顺序为 `ScoringEngine → KnowledgeAgent → EvaluationAgent → SafetyAgent → ProfileAgent → PathAgent`。仅当评估结构、两阶段 SafetyAgent 审查及未来三天路径约束均通过后，Java 才能在同一事务中保存作答记录、更新掌握度/错题本、创建画像版本并保存路径新版本；任一阶段失败时不得留下半完成的自适应状态。

### GET `/api/evaluation/report`

获取当前学生最近一次真实小测评估与累计掌握度报告。`overall_score`、`weak_points`、`mistake_patterns` 和建议来自最近一次已持久化评估；`mastery` 来自确定性答题统计，不再返回静态演示结论。

响应 `data`：

```json
{
  "report_id": "eval_001",
  "student_id": "demo",
  "quiz_id": 1001,
  "overall_score": 67,
  "mastery": [
    {
      "knowledge_point": "递归调用栈",
      "mastery_score": 58,
      "level": "一般"
    },
    {
      "knowledge_point": "二叉树前序遍历",
      "mastery_score": 82,
      "level": "良好"
    }
  ],
  "weak_points": ["递归调用栈"],
  "mistake_patterns": ["process_gap"],
  "next_actions": [
    "完成递归调用栈图解资源",
    "重新生成 3 天补救学习路径"
  ],
  "profile_updated": true
}
```

## 11. 后端运营接口

### 用户管理

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/admin/users` | admin | 查询用户，支持 `keyword`、`role`、`status`、`page`、`size` |
| POST | `/api/admin/users` | admin | 创建用户并写入 PBKDF2 密码哈希 |
| PATCH | `/api/admin/users/{id}/status` | admin | 启用/禁用用户 |
| PATCH | `/api/admin/users/{id}/role` | admin | 更新用户角色 |
| PATCH | `/api/admin/users/{id}/password` | admin | 重置密码 |
| POST | `/api/admin/users/{id}/course-roles` | admin | 授予课程管理权限 |

### 课程与知识点管理

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/courses` | admin | 创建课程 |
| PATCH | `/api/courses/{courseId}` | admin | 更新课程 |
| POST | `/api/courses/{courseId}/knowledge-points` | admin | 创建知识点 |
| PATCH | `/api/courses/knowledge-points/{pointId}` | admin | 更新知识点 |

### 测验运营

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/quiz/history` | student | 查询当前学生答题历史 |
| GET | `/api/quiz/wrong-book` | student | 查询当前学生错题本 |
| GET | `/api/quiz/mastery` | student | 查询当前学生知识点掌握度 |
| GET | `/api/quiz/questions` | teacher/admin | 查询题库 |
| POST | `/api/quiz/questions` | teacher/admin | 新增题库题目 |
| PATCH | `/api/quiz/questions/{id}/status` | teacher/admin | 更新题库题目状态 |

### 资源、辅导与审计

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| PATCH | `/api/resources/{resourceId}` | teacher/admin | 更新资源内容并生成版本记录 |
| GET | `/api/resources/{resourceId}/versions` | teacher/admin | 查询资源版本记录 |
| GET | `/api/tutor/sessions` | student | 查询当前学生辅导会话 |
| GET | `/api/admin/audit-logs` | admin | 查询请求审计日志 |
| GET | `/api/system/runtime` | 登录用户 | 查询服务运行摘要 |

## 12. 比赛演示模式

### GET `/api/demo/status`

教师、管理员查询软件杯演示场景是否启用、数据完整性、最近准备记录、演示账号用途和推荐答辩步骤。学生访问返回 `403`。生产环境默认关闭；比赛环境需显式设置 `DEMO_MODE_ENABLED=true`。

完整性检查至少覆盖：6 类演示资源、画像版本、7 天学习路径、诊断测验及评估、8 条学习事件、3 个掌握度知识点、RAG 辅导消息和演示班级。响应 `ready=true` 后才建议开始导览。

### POST `/api/demo/prepare`

教师必须同时拥有两门演示课程的管理权限，管理员可直接执行。请求：

```json
{
  "scenario_key": "software_cup_a3"
}
```

接口同步重建专用 `demo` 账号的演示数据并返回 `run_id`、完整性计数和推荐演示步骤。操作范围严格限制为 `demo_prepare`、`demo_showcase_*`、固定演示测验/辅导会话，不删除其他用户、普通资源或真实学习记录。连续执行使用相同业务标识重建，资源、路径、测验和事件计数保持稳定；每次成功准备写入 `demo_scenario_runs` 审计记录。

### GET `/api/demo/preflight`

教师、管理员执行无模型额度消耗的演示前聚合检查。接口检查 Java 后端、业务数据库、专用演示数据、Python AI 服务、两门课程知识库以及真实模型配置，并返回：

- `overall_status`：`ready`、`degraded` 或 `blocked`；
- `recommended_mode`：`live_ai`、`prepared_data_only` 或 `fix_before_demo`；
- `prepared_demo_available` 与 `live_ai_available`；
- 各检查项状态、耗时、处置建议和降级模式下不可用的实时能力。

`degraded` 代表可安全展示已准备的画像、资源、路径、辅导记录、评估和教师洞察，但不应现场触发实时生成。真实模型检查只验证服务连通性与配置完整性，不发送消耗模型额度的探测请求。

### 演示端约定

- `demo`：学生闭环，包括画像、6 类资源、资源中心、动态路径、RAG 辅导和评估；
- `teacher`：教师闭环，包括班级聚合洞察和资源质量治理；
- 前端使用 `edupath_demo_mode` 本地状态显示持久化演示导览条，不改变后端权限；
- 演示准备不伪造 Agent 实时任务，现场需要展示真实模型调用时仍通过正式资源生成接口执行。

## 13. 持久化与可观测性

Java 后端使用 Flyway 管理基础表结构，核心表包括：

| 模块 | 表 |
|---|---|
| 用户与权限 | `users`、`refresh_tokens`、`jwt_revocations`、`user_course_roles` |
| Agent 任务 | `agent_tasks`、`agent_task_steps` |
| 课程目录 | `courses`、`knowledge_points` |
| 资源中心 | `resources`、`resource_versions`、`resource_review_records`、`resource_interactions` |
| 知识库 | `knowledge_documents`、`knowledge_document_chunks`、`knowledge_index_jobs` |
| 测验评估 | `quizzes`、`quiz_questions`、`quiz_attempts`、`quiz_attempt_items`、`wrong_question_book`、`knowledge_mastery`、`question_bank` |
| 学习画像 | `profile_versions` |
| 学习路径、辅导与行为 | `learning_paths`、`tutor_sessions`、`tutor_messages`、`learning_events` |
| 可观测性 | `audit_logs` |

请求日志会写入 `audit_logs`，同时输出 trace id、actor、method、path、status、duration。AI 调用失败会返回 `50200` 或将异步任务置为 `failed`。

## 14. 后续待补充

- 真实 OSS SDK 上传和下载签名 URL。
- 真实向量库索引和删除重建流程。
- 更细粒度的 RBAC 权限矩阵，例如班级、课程团队和资源 owner 维度。
- AI 服务内部接口可按 OpenAPI 单独发布。
