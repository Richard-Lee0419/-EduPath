import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));

const read = (path) => readFileSync(join(root, path), 'utf8');

const checks = [
  {
    path: 'index.html',
    forbidden: ['edupath-mark.jpg'],
    required: ['edupath-mark.png', 'sizes="128x128"'],
  },
  {
    path: 'src/api/http.ts',
    forbidden: ['ApiMode', 'isMockMode', 'mockOrRequest', "envMode === 'real' ? 'real' : 'mock'"],
  },
  {
    path: 'src/api/auth.ts',
    forbidden: ['edupath_mock_user', 'createMockUser', 'getStoredMockUser', 'MockUser'],
  },
  {
    path: 'src/pages/Login.vue',
    forbidden: ['fillDefaultAccount', 'auth-demo-account', '默认体验账号', 'demo / 123456'],
    required: ['edupath-wordmark-white.png', 'fetchpriority="high"'],
  },
  {
    path: 'src/components/AuthLearningVisual.vue',
    forbidden: ['登录注册页面背景图.png'],
    required: ['auth-learning-bg.webp', 'width="1254"', 'fetchpriority="high"'],
  },
  {
    path: 'src/components/HomeHeroIllustration.vue',
    forbidden: ['招手小人图.png', 'learner-hero.webp'],
    required: ['home-hero-robot-svg', 'role="img"', 'aria-labelledby="homeRobotTitle homeRobotDescription"'],
  },
  {
    path: 'src/components/EduPathLogo.vue',
    forbidden: ['logo.png'],
    required: ['logo.webp', 'width="320"', 'decoding="async"'],
  },
  {
    path: 'src/pages/Dashboard.vue',
    forbidden: ['我想了解递归调用栈'],
  },
  {
    path: 'src/api/profile.ts',
    forbidden: ['../mock/data', 'mockProfile', 'isMockMode', 'mockOrRequest', 'EduPath Learner'],
  },
  {
    path: 'src/api/resource.ts',
    forbidden: ['../mock/data', 'resourceCards', 'initialAgentSteps', 'isMockMode', 'mockOrRequest'],
    required: ['model_runtime', 'ModelRuntime', 'event_id', '/resources/quality-metrics', '/resources/quality-regression-tasks', '/resources/quality-repairs', '/execute', '/comparison', '/publish', '/rollback', 'generation_mode', 'max_resources'],
  },
  {
    path: 'src/api/classInsight.ts',
    forbidden: ['../mock/data', 'mockOrRequest', 'isMockMode'],
    required: ['/classes', '/insights', 'window_days', 'course_id'],
  },
  {
    path: 'src/api/task.ts',
    forbidden: ['mockOrRequest', 'isMockMode'],
    required: ['/agent/tasks', '/cancel', '/retry', 'listAgentTasks', 'cancelAgentTask', 'retryAgentTask'],
  },
  {
    path: 'src/types/api.ts',
    required: ['quality_revision_rounds', 'safety_feedback_revision_rounds', 'quality_feedback_revision_rounds', 'quality_revised_resource_count', 'ResourceQualityRepairItem', 'queued_repair_ids'],
  },
  {
    path: 'src/api/learningPath.ts',
    forbidden: ['../mock/data', 'mockLearningPath', 'isMockMode', 'mockOrRequest', '个性化学习路径'],
    required: ['personalization_summary', "text(raw, 'reason')", 'completed_days', '/nodes/', 'adjustment_signal', 'replan_recommended', 'recommended_daily_minutes', '/path/replan/behavior', 'trigger_key'],
  },
  {
    path: 'src/api/tutor.ts',
    forbidden: ['../mock/data', 'mockTutorAnswer', 'isMockMode', 'answer_${Date.now()}', '0.88'],
    required: ['answer_markdown', 'citations', 'answer_fragment', 'model_runtime', 'generation_mode', 'session_id', 'learning_update'],
  },
  {
    path: 'src/api/evaluation.ts',
    forbidden: ['../mock/data', 'mockEvaluationReport', 'isMockMode', 'quiz_submit_', '继续完成练习并刷新学习路径'],
    required: ['submitQuiz', 'taskId: result.taskId'],
  },
  {
    path: 'src/api/quiz.ts',
    forbidden: ['question_count: payload.questionCount ?? 5', '../mock/data', 'mockOrRequest'],
    required: ['questionCount ?? 3', 'questionCount > 3', "apiPost<RawMap>('/quiz/generate'", 'model_runtime', 'evidence_chunk_ids', 'path_update', 'source_evaluation_task_id'],
  },
  {
    path: 'src/pages/ProfileChat.vue',
    forbidden: ['startMockReply', '?? 0.72'],
    required: ['model_runtime', 'modelRuntimeSummary', 'SafetyAgent 已独立复核通过'],
  },
  {
    path: 'src/pages/ResourceGenerate.vue',
    forbidden: ['调用栈内存分配', '视觉化 / 步骤追踪'],
    required: ['resource.subtitle', 'modelRuntimeTitle', '真实模型调用', '确定性回退模式', '安全反馈修订', '质量定向修订'],
  },
  {
    path: 'src/pages/LearningPath.vue',
    forbidden: ['测试正确率低于 70% 时插入补救资源'],
    required: ['day.reason', 'personalizationSummary', 'model_runtime', 'modelRuntimeSummary', 'RAG 证据'],
  },
  {
    path: 'src/components/ResourceQualityDashboard.vue',
    forbidden: ['mock', 'Math.random', 'setInterval'],
    required: ['getResourceQualityMetrics', 'createResourceQualityRegressionTask', 'waitForAgentTask', 'metrics.source.source_table', '执行小批量回归', 'audit_only'],
  },
  {
    path: 'src/App.vue',
    required: ["resourceCenter: '/resources'", "['/resources', 'resourceCenter']", "currentPage === 'resourceCenter'", "taskCenter: '/task-center'", "currentPage === 'taskCenter'", 'defineAsyncComponent', "import('./pages/ResourceCenter.vue')"],
  },
  {
    path: 'src/components/RichContentRenderer.vue',
    forbidden: ["import hljs from 'highlight.js';"],
    required: ["highlight.js/lib/core", "registerLanguage('java'", "import('mermaid')"],
  },
  {
    path: 'src/pages/LearningPath.vue',
    required: ['行为驱动调整信号', 'adjustmentSignal', 'cooldown', 'pacingSummary', '执行受控路径调整', 'runBehaviorReplan'],
  },
  {
    path: 'src/components/AppTopNav.vue',
    required: ["'resourceCenter'", '资源中心'],
  },
  {
    path: 'src/pages/KnowledgeBase.vue',
    forbidden: ['99.9% uptime', '递归 调用栈 RAG 证据', '二叉树遍历', 'Cache 映射', 'SafetyAgent 复核'],
    required: ['searchKnowledgeBase', 'searchQuery', 'corpusModeLabel', 'external_documents', 'licenseStatus'],
  },
  {
    path: 'src/api/kb.ts',
    forbidden: ['mockKnowledge', 'isMockMode', 'mockOrRequest'],
    required: ['/kb/search', 'KnowledgeCorpusSummary', 'knowledge_point_id', 'license_status'],
  },
  {
    path: 'src/pages/Tutor.vue',
    forbidden: ['为什么递归函数调用自身', '可视化追踪指南', '调用栈的交互式动画', '函数调用', '基准情形', '内存层级'],
    required: ['modelRuntimeSummary', 'SafetyAgent 已独立复核通过', 'answer.citations', 'item.source'],
  },
  {
    path: 'src/pages/Quiz.vue',
    forbidden: ['基于默认课程薄弱点生成', "['递归调用栈']"],
    required: ['questionCount: 3', 'SafetyAgent 已通过', 'EvaluationAgent 结论', 'PathAgent 动态重规划', '最多 3 题'],
  },
  {
    path: 'src/pages/UserCenter.vue',
    forbidden: ['line-chart', '近7天', '近30天', '近90天', '大学生', '清新绿', '导出记录', '智能辅导问答记录'],
  },
  {
    path: 'src/components/ResourceCard.vue',
    forbidden: ['fitReasons', '把递归调用栈拆成可阅读的步骤'],
  },
  {
    path: 'src/pages/UserCenter.vue',
    forbidden: ["../mock/data"],
  },
];

const failures = [];

for (const path of ['src/api/demo.ts']) {
  if (existsSync(join(root, path))) {
    failures.push(`${path} must remain removed from the production frontend`);
  }
}

for (const check of checks) {
  const content = read(check.path);
  for (const token of check.forbidden || []) {
    if (content.includes(token)) {
      failures.push(`${check.path} still contains ${token}`);
    }
  }
  for (const token of check.required || []) {
    if (!content.includes(token)) {
      failures.push(`${check.path} is missing ${token}`);
    }
  }
}

if (failures.length > 0) {
  console.error(failures.join('\n'));
  process.exit(1);
}
