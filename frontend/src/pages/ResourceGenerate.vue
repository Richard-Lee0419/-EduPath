<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import ResourceQualityDashboard from '../components/ResourceQualityDashboard.vue';
import RichContentRenderer from '../components/RichContentRenderer.vue';
import DfsTraversalVisualizer from '../components/resource/DfsTraversalVisualizer.vue';
import SplitText from '../components/SplitText.vue';
import ResourceDetailRenderer from '../components/ResourceDetailRenderer.vue';
import { getCourses } from '../api/course';
import { getCurrentProfile } from '../api/profile';
import { generateResources, getResourceDetail, listResources, recordResourceInteraction, resourcesFromTask } from '../api/resource';
import { isAbortError, waitForAgentTask } from '../api/task';
import { clearActiveTask, persistActiveTask, restoreActiveTask } from '../api/taskPersistence';
import { observeExposure, shouldEmitExposure, track } from '../research';
import type { AgentStatus, PageKey, Profile, ResourceCard } from '../types';
import type { ModelRuntime, ResourceDetail } from '../types/api';

const RESEARCH_PAGE = 'resource-generate';
const RESOURCE_TASK_SCOPE = 'resource-generation';
const terminalTaskStatuses = new Set<AgentStatus>(['success', 'failed', 'cancelled']);
const SELECTABLE_RESOURCE_TYPES: Array<{ value: ResourceCard['type']; label: string }> = [
  { value: 'lecture', label: '个性化讲义' },
  { value: 'mindmap', label: '思维导图' },
  { value: 'quiz', label: '题库' },
  { value: 'codelab', label: '代码实验' },
  { value: 'flowchart', label: '流程图' },
  { value: 'reading', label: '拓展阅读' },
];
const SELECTABLE_DIFFICULTIES: Array<{ value: 'basic' | 'medium' | 'advanced'; label: string }> = [
  { value: 'basic', label: '基础' },
  { value: 'medium', label: '中等' },
  { value: 'advanced', label: '进阶' },
];

defineEmits<{
  navigate: [page: PageKey];
}>();

interface ResourceStage {
  title: string;
  agent: string;
  status: AgentStatus;
  detail: string;
}

interface ResourceItem {
  id: string;
  type: string;
  title: string;
  reason: string;
  difficulty: string;
  minutes?: number;
  agent: string;
  safety: string;
  qualityScore?: number;
  qualityGrade?: 'A' | 'B' | 'C' | 'D';
  qualityGatePassed?: boolean;
  tone: 'teal' | 'amber' | 'coral' | 'blue';
}

const profile = ref<Profile | null>(null);
const isGenerating = ref(false);
const progress = ref(0);
const activeTaskId = ref('');
const errorMessage = ref('');
const detailLoading = ref(false);
const detailError = ref('');
const selectedResource = ref<ResourceItem | null>(null);
const selectedDetail = ref<ResourceDetail | null>(null);
const courses = ref<Array<{ id: number; name: string }>>([]);
const selectedCourseId = ref<number | null>(null);
const modelRuntime = ref<ModelRuntime | null>(null);
const qualityRefreshToken = ref(0);
const selectedResourceTypes = ref<Array<ResourceCard['type']>>(SELECTABLE_RESOURCE_TYPES.map((item) => item.value));
const selectedDifficulty = ref<'basic' | 'medium' | 'advanced'>('medium');

const stages = ref<ResourceStage[]>([
  {
    title: '画像理解中',
    agent: 'ProfileAgent',
    status: 'pending',
    detail: '等待创建真实资源生成任务。',
  },
  {
    title: '检索知识库',
    agent: 'KnowledgeAgent',
    status: 'pending',
    detail: '等待后端检索 RAG 证据。',
  },
  {
    title: '规划资源包',
    agent: 'PlannerAgent',
    status: 'pending',
    detail: '等待多智能体规划资源组合。',
  },
  {
    title: '多智能体生成中',
    agent: 'LectureAgent / QuizAgent / CodeAgent',
    status: 'pending',
    detail: '等待生成图解、练习和可视化材料。',
  },
  {
    title: '安全检查',
    agent: 'SafetyAgent',
    status: 'pending',
    detail: '检查事实一致性、课程证据和难度匹配。',
  },
  {
    title: '输出资源',
    agent: 'ResourceAgent',
    status: 'pending',
    detail: '整理为可学习、可展示的资源包。',
  },
]);

const resources = ref<ResourceItem[]>([]);

const primaryWeakPoint = computed(() => profile.value?.weakPoints[0] || '');
const selectedCourseName = computed(() => courses.value.find((course) => course.id === selectedCourseId.value)?.name || '');
const course = computed(() => selectedCourseName.value || '课程加载中');
const targetPoint = computed(() =>
  primaryWeakPoint.value ? `为“${primaryWeakPoint.value}”生成专项资源包` : '基于当前课程生成资源包',
);
const evidence = computed(() => [
  {
    title: '学习画像数据',
    body: profile.value
      ? `薄弱点：${profile.value.weakPoints.join('、') || '暂无'}；偏好：${profile.value.resourcePreference.join('、') || '暂无'}。`
      : '正在读取学习画像。',
  },
  {
    title: '课程知识库检索',
    body: primaryWeakPoint.value
      ? `将围绕「${primaryWeakPoint.value}」检索 RAG 证据，并由 SafetyAgent 检查一致性。`
      : '将按当前课程检索 RAG 证据，并由 SafetyAgent 检查一致性。',
  },
  {
    title: '资源组合策略',
    body: profile.value?.learningPace
      ? `按 ${profile.value.learningPace} 生成讲义、练习和实验材料。`
      : '画像同步后会结合学习节奏生成讲义、练习和实验材料。',
  },
]);

let taskWaitController: AbortController | undefined;
const reasonObservers = new Map<string, () => void>();

const activeStage = computed(() => stages.value.find((stage) => stage.status === 'running') ?? stages.value[0]);
const detailEvidence = computed(() => selectedDetail.value?.evidence?.slice(0, 3) ?? []);

const toggleResourceType = (value: ResourceCard['type']) => {
  if (selectedResourceTypes.value.includes(value)) {
    if (selectedResourceTypes.value.length === 1) return;
    selectedResourceTypes.value = selectedResourceTypes.value.filter((item) => item !== value);
    return;
  }
  selectedResourceTypes.value = [...selectedResourceTypes.value, value];
};
const qualityDimensionLabels: Record<string, string> = {
  evidence_coverage: '证据覆盖',
  structural_completeness: '结构完整',
  knowledge_consistency: '知识一致',
  difficulty_alignment: '难度匹配',
  safety_review: '安全审查',
};
const detailQualityDimensions = computed(() =>
  Object.entries(selectedDetail.value?.quality_evaluation?.dimensions ?? {}).map(([key, item]) => ({
    key,
    label: qualityDimensionLabels[key] ?? key,
    ...item,
  })),
);
const modelRuntimeTitle = computed(() => {
  if (!modelRuntime.value) return '等待任务结果';
  if (modelRuntime.value.mode === 'real_model') {
    return `${modelRuntime.value.provider || 'LLM'} / ${modelRuntime.value.model || '已配置模型'}`;
  }
  return '确定性回退模式';
});
const modelRuntimeDetail = computed(() => {
  if (!modelRuntime.value) return '完成生成后将显示真实 Provider、模型与调用次数。';
  if (modelRuntime.value.mode === 'real_model') {
    const safetyRevision = modelRuntime.value.safety_feedback_revision_rounds
      ? `；安全反馈修订 ${modelRuntime.value.safety_feedback_revision_rounds} 轮`
      : '';
    const qualityRevision = modelRuntime.value.quality_feedback_revision_rounds
      ? `；质量定向修订 ${modelRuntime.value.quality_feedback_revision_rounds} 轮、涉及 ${modelRuntime.value.quality_revised_resource_count ?? 0} 个资源`
      : '';
    return `真实模型调用 ${modelRuntime.value.call_count ?? 0} 次，共 ${modelRuntime.value.total_tokens ?? 0} Token${safetyRevision}${qualityRevision}。`;
  }
  return '当前未配置模型 Key，本次资源没有冒充真实模型输出。';
});
const formattedDetailContent = computed(() => {
  const content = selectedDetail.value?.content?.trim();
  if (content) return content;
  if (!selectedResource.value) return '';
  return [
    `## ${selectedResource.value.title}`,
    '',
    selectedResource.value.reason,
    '',
    `- 类型：${selectedResource.value.type}`,
    selectedResource.value.difficulty ? `- 难度：${selectedResource.value.difficulty}` : '',
    selectedResource.value.minutes ? `- 学习时长：${selectedResource.value.minutes} 分钟` : '',
    `- 生成智能体：${selectedResource.value.agent}`,
  ].filter(Boolean).join('\n');
});
const showDfsVisualizer = computed(() => {
  const detail = selectedDetail.value;
  const searchable = [
    selectedResource.value?.title,
    selectedResource.value?.reason,
    detail?.title,
    detail?.summary,
    detail?.content,
    ...(detail?.knowledge_points ?? []),
  ].filter(Boolean).join(' ');
  return /(?:\bDFS\b|深度优先(?:搜索|遍历)?)/i.test(searchable);
});
const isLectureResource = computed(() => {
  const type = selectedDetail.value?.resource_type
    || selectedDetail.value?.type
    || selectedResource.value?.type;
  return type === 'lecture' || type === '个性化讲义';
});

const typeLabels: Record<ResourceCard['type'], string> = {
  lecture: '个性化讲义',
  mindmap: '思维导图',
  quiz: '题库',
  codelab: '代码实验',
  animation_script: '动画脚本（历史资源）',
  flowchart: '流程图',
  reading: '拓展阅读',
};

const agentTitle = (agent: string) => {
  if (agent.includes('Profile')) return '画像理解';
  if (agent.includes('Knowledge')) return '检索知识库';
  if (agent.includes('Planner')) return '规划资源包';
  if (agent.includes('Safety')) return '安全检查';
  if (agent.includes('Resource')) return '输出资源';
  return agent;
};

const difficultyLabel = (value: string) => {
  if (value === 'advanced') return '进阶';
  if (value === 'medium') return '中等';
  return '基础';
};

const toResourceItem = (resource: ResourceCard, index: number): ResourceItem => ({
  id: resource.id,
  type: typeLabels[resource.type],
  title: resource.title,
  reason: resource.subtitle,
  difficulty: resource.difficulty ? difficultyLabel(resource.difficulty) : '',
  minutes: resource.minutes,
  agent: resource.type === 'quiz' ? 'QuizAgent' : resource.type === 'codelab' ? 'CodeAgent' : 'ResourceAgent',
  safety: resource.confidence ? `SafetyAgent ${Math.round(resource.confidence * 100)}%` : '',
  qualityScore: resource.qualityScore,
  qualityGrade: resource.qualityGrade,
  qualityGatePassed: resource.qualityGatePassed,
  tone: ['teal', 'amber', 'coral', 'blue'][index % 4] as ResourceItem['tone'],
});

const applyTaskSteps = (taskSteps: Array<{ agent: string; status: AgentStatus; message: string }>) => {
  if (!taskSteps.length) return;
  stages.value = taskSteps.map((step) => ({
    title: agentTitle(step.agent),
    agent: step.agent,
    status: step.status,
    detail: step.message,
  }));
};

const loadExistingResources = async () => {
  if (!selectedCourseId.value) return;
  const data = await listResources({ page: 1, size: 50, courseId: selectedCourseId.value });
  resources.value = data.items.filter((resource) => resource.type !== 'animation_script').map(toResourceItem);
};

const openResource = async (item: ResourceItem) => {
  selectedResource.value = item;
  selectedDetail.value = null;
  detailError.value = '';
  detailLoading.value = true;
  track('resource_opened', {
    page: RESEARCH_PAGE,
    resourceType: item.type,
    metadata: { resource_id: item.id, resource_type: item.type },
  });
  try {
    selectedDetail.value = await getResourceDetail(item.id);
    void recordResourceView(item.id);
    const evidenceCount = selectedDetail.value?.evidence?.length ?? 0;
    if (evidenceCount > 0 && shouldEmitExposure(`evidence:detail:${item.id}`)) {
      track('evidence_exposed', {
        page: RESEARCH_PAGE,
        metadata: { resource_id: item.id, evidence_count: evidenceCount, exposure_key: `evidence:detail:${item.id}` },
      });
    }
  } catch (error) {
    detailError.value = error instanceof Error ? error.message : '资源详情加载失败';
  } finally {
    detailLoading.value = false;
  }
};

const recordResourceView = async (resourceId: string) => {
  try {
    await recordResourceInteraction(resourceId, { action: 'view' });
  } catch {
    // 业务详情不因埋点/交互记录失败而阻断。
  }
};

const closeResource = () => {
  selectedResource.value = null;
  selectedDetail.value = null;
  detailError.value = '';
};

const registerReasonElement = (element: unknown, resourceId: string) => {
  if (!(element instanceof Element)) return;
  reasonObservers.get(resourceId)?.();
  reasonObservers.set(
    resourceId,
    observeExposure(element, `reason:${resourceId}`, 'reason_exposed', () => ({
      page: RESEARCH_PAGE,
      metadata: { reason_id: resourceId },
    })),
  );
};

const resetStagesForTask = () => {
  progress.value = 3;
  stages.value = stages.value.map((stage, index) => ({
    ...stage,
    status: index === 0 ? 'running' : 'pending',
  }));
};

const monitorResourceTask = async (taskId: string) => {
  taskWaitController?.abort();
  const controller = new AbortController();
  taskWaitController = controller;
  activeTaskId.value = taskId;
  isGenerating.value = true;
  try {
    const task = await waitForAgentTask(
      taskId,
      (snapshot) => {
        progress.value = snapshot.progress;
        applyTaskSteps(snapshot.steps);
        if (terminalTaskStatuses.has(snapshot.status)) {
          track('generation_state_changed', {
            page: RESEARCH_PAGE,
            executionState: snapshot.status,
            taskRef: taskId,
            metadata: { task_id: taskId, status: snapshot.status, step: snapshot.current_agent || undefined },
          });
          clearActiveTask(RESOURCE_TASK_SCOPE, taskId);
        }
      },
      { signal: controller.signal },
    );
    clearActiveTask(RESOURCE_TASK_SCOPE, taskId);
    const result = resourcesFromTask(task);
    modelRuntime.value = result.modelRuntime ?? null;
    progress.value = 100;
    stages.value = result.agentTrace.map((step) => ({
      title: agentTitle(step.agent),
      agent: step.agent,
      status: step.status,
      detail: step.message,
    }));
    resources.value = result.resources.map(toResourceItem);
    qualityRefreshToken.value += 1;
    if (!resources.value.length) await loadExistingResources();
  } catch (error) {
    if (isAbortError(error)) return;
    errorMessage.value = error instanceof Error ? error.message : '资源生成失败';
    stages.value = stages.value.map((stage) => (stage.status === 'running' ? { ...stage, status: 'failed' } : stage));
  } finally {
    if (taskWaitController === controller) {
      taskWaitController = undefined;
      isGenerating.value = false;
    }
  }
};

const runGeneration = async () => {
  taskWaitController?.abort();
  isGenerating.value = true;
  errorMessage.value = '';
  activeTaskId.value = '';
  modelRuntime.value = null;
  resetStagesForTask();
  try {
    if (!selectedCourseId.value) throw new Error('课程列表尚未加载，无法创建资源任务');
    if (!selectedResourceTypes.value.length) throw new Error('请至少选择一类资源');
    const created = await generateResources({
      courseId: selectedCourseId.value,
      knowledgePoints: primaryWeakPoint.value ? [primaryWeakPoint.value] : [],
      goal: targetPoint.value,
      resourceTypes: selectedResourceTypes.value,
      difficulty: selectedDifficulty.value,
    });
    track('generation_requested', {
      page: RESEARCH_PAGE,
      courseId: selectedCourseId.value,
      taskRef: created.taskId,
      metadata: { task_id: created.taskId, resource_types: selectedResourceTypes.value, difficulty: selectedDifficulty.value },
    });
    persistActiveTask(RESOURCE_TASK_SCOPE, created.taskId);
    await monitorResourceTask(created.taskId);
  } catch (error) {
    if (isAbortError(error)) return;
    errorMessage.value = error instanceof Error ? error.message : '资源生成失败';
    stages.value = stages.value.map((stage) => (stage.status === 'running' ? { ...stage, status: 'failed' } : stage));
    isGenerating.value = false;
  }
};

onMounted(async () => {
  try {
    const [courseRows, profileResult] = await Promise.all([getCourses(), getCurrentProfile().catch(() => null)]);
    courses.value = courseRows.map((item) => ({ id: item.id, name: item.name }));
    selectedCourseId.value = courseRows[0]?.id ?? null;
    profile.value = profileResult?.profile ?? null;
    const pendingTaskId = restoreActiveTask(RESOURCE_TASK_SCOPE);
    if (pendingTaskId) void monitorResourceTask(pendingTaskId);
  } finally {
    void loadExistingResources();
  }
});

onUnmounted(() => {
  taskWaitController?.abort();
  reasonObservers.forEach((cleanup) => cleanup());
  reasonObservers.clear();
});
</script>

<template>
  <div class="resource-redesign ai-page-bg page-enter">
    <section class="resource-stage">
      <div class="resource-brief-card interactive-card">
        <div class="resource-card-icon" aria-hidden="true">
          <svg viewBox="0 0 32 32">
            <path d="M8 17c4-8 10-11 18-9-1 8-6 13-15 15l-5 5 2-11Z" />
            <path d="M13 16l4 4M18 10l4 4" />
          </svg>
        </div>
        <SplitText tag="h1" text="个性化资源生成任务" />
        <div class="brief-columns">
          <div>
            <span>目标课程</span>
            <strong>{{ course }}</strong>
          </div>
          <div>
            <span>知识点目标</span>
            <strong>{{ targetPoint }}</strong>
          </div>
          <div>
            <span>已识别薄弱点</span>
            <em>{{ profile?.weakPoints.join('、') || '暂无画像薄弱点' }}</em>
          </div>
          <div>
            <span>学习者偏好</span>
            <em class="preference">{{ profile?.resourcePreference.join(' / ') || '暂无资源偏好' }}</em>
          </div>
        </div>
        <div class="resource-request-controls">
          <div class="brief-tags" aria-label="选择资源类型">
            <button
              v-for="item in SELECTABLE_RESOURCE_TYPES"
              :key="item.value"
              class="resource-type-chip"
              :class="{ selected: selectedResourceTypes.includes(item.value) }"
              type="button"
              :aria-pressed="selectedResourceTypes.includes(item.value)"
              @click="toggleResourceType(item.value)"
            >
              {{ item.label }}
            </button>
          </div>
          <label class="difficulty-control">
            <span>难度</span>
            <select v-model="selectedDifficulty" :disabled="isGenerating">
              <option v-for="item in SELECTABLE_DIFFICULTIES" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
        </div>
        <button class="primary-action hover-scale" type="button" :disabled="isGenerating" @click="runGeneration">
          {{ isGenerating ? '多智能体生成中' : '生成个性化资源包' }}
        </button>
        <details v-if="activeTaskId" class="technical-details">
          <summary>查看任务技术信息</summary>
          <code>task_id: {{ activeTaskId }}</code>
        </details>
        <p v-if="errorMessage" class="task-error-line">{{ errorMessage }}</p>
      </div>

      <aside class="generation-evidence-card interactive-card">
        <div class="side-title">
          <span></span>
          <h2>生成依据</h2>
          <i></i>
        </div>
        <article v-for="item in evidence" :key="item.title" class="evidence-line">
          <strong>{{ item.title }}</strong>
          <p>{{ item.body }}</p>
        </article>
        <div class="model-runtime-card" :class="{ 'is-real': modelRuntime?.mode === 'real_model' }">
          <span>模型执行状态</span>
          <strong>{{ modelRuntimeTitle }}</strong>
          <p>{{ modelRuntimeDetail }}</p>
        </div>
        <div class="active-mini">
          <span>当前阶段</span>
          <strong>{{ activeStage.title }}</strong>
          <p>{{ activeStage.detail }}</p>
        </div>
      </aside>
    </section>

    <section class="orchestration-rail" aria-label="智能体生成轨道">
      <div class="rail-line agent-flow-line" aria-hidden="true"></div>
      <article
        v-for="stage in stages"
        :key="stage.title"
        class="rail-node"
        :class="[`is-${stage.status}`, { 'agent-active-pulse': stage.status === 'running' }]"
      >
        <div class="rail-icon">
          <svg viewBox="0 0 28 28">
            <circle cx="14" cy="14" r="9" />
            <path d="M9 14h10M14 9v10" />
          </svg>
        </div>
        <strong>{{ stage.title }}</strong>
        <span>{{ stage.agent }}</span>
        <p>{{ stage.detail }}</p>
      </article>
    </section>

    <ResourceQualityDashboard :course-id="selectedCourseId" :refresh-token="qualityRefreshToken" />

    <section class="resource-pack">
      <div class="section-title-row">
        <div>
          <h2>生成的个性化资源包</h2>
          <p>每张卡片对应一种学习材料，附带适配理由、生成智能体和安全审查状态。</p>
        </div>
        <div class="progress-chip" :aria-label="`当前生成进度 ${progress}%`">
          <span>生成进度</span>
          <strong>{{ progress }}%</strong>
        </div>
      </div>

      <div class="resource-card-grid">
        <article
          v-for="item in resources"
          :key="item.id"
          class="generated-resource-card interactive-card"
          :class="`tone-${item.tone}`"
          tabindex="0"
          role="button"
          :aria-label="`查看资源：${item.title}`"
          @click="openResource(item)"
          @keydown.enter.prevent="openResource(item)"
        >
          <div class="resource-card-top">
            <span>{{ item.type }}</span>
            <em v-if="item.qualityScore !== undefined">
              质量 {{ item.qualityGrade }} · {{ Math.round(item.qualityScore) }} 分
            </em>
            <em v-else-if="item.safety">{{ item.safety }}</em>
          </div>
          <h3>{{ item.title }}</h3>
          <p :ref="(element) => registerReasonElement(element, item.id)">{{ item.reason }}</p>
          <div class="resource-card-meta">
            <span v-if="item.minutes">{{ item.minutes }} min</span>
            <span v-if="item.difficulty">{{ item.difficulty }}</span>
            <span>{{ item.agent }}</span>
          </div>
          <button class="resource-open-button" type="button" @click.stop="openResource(item)">查看资源</button>
        </article>
      </div>
    </section>

    <Teleport to="body">
      <div v-if="selectedResource" class="resource-detail-backdrop" @click.self="closeResource">
        <article class="resource-detail-panel" role="dialog" aria-modal="true" :aria-label="selectedResource.title">
          <button class="resource-detail-close" type="button" aria-label="关闭资源详情" @click="closeResource">×</button>
          <div class="resource-detail-head">
            <span>{{ selectedResource.type }}</span>
            <h2>{{ selectedResource.title }}</h2>
            <p>{{ selectedDetail?.summary || selectedResource.reason }}</p>
          </div>

          <div class="resource-detail-meta">
            <span v-if="selectedResource.minutes">{{ selectedResource.minutes }} min</span>
            <span v-if="selectedDetail?.difficulty || selectedResource.difficulty">{{ selectedDetail?.difficulty || selectedResource.difficulty }}</span>
            <span>{{ selectedResource.agent }}</span>
            <span>{{ selectedDetail?.safety?.passed ? 'SafetyAgent 已通过' : selectedResource.safety }}</span>
            <span v-if="selectedDetail?.quality_evaluation">
              {{ selectedDetail.quality_evaluation.gate_passed ? '质量门禁已通过' : '质量门禁需复核' }}
            </span>
          </div>

          <p v-if="detailLoading" class="resource-detail-state">正在读取资源正文...</p>
          <p v-else-if="detailError" class="resource-detail-error">{{ detailError }}</p>
          <template v-else>
            <DfsTraversalVisualizer v-if="showDfsVisualizer && isLectureResource" />
            <ResourceDetailRenderer v-if="selectedDetail" :detail="selectedDetail" />
            <section v-else class="resource-detail-content">
              <RichContentRenderer :content="formattedDetailContent" content-format="markdown" />
            </section>
            <section v-if="showDfsVisualizer && !isLectureResource" class="dfs-support-section">
              <header>
                <span>辅助理解</span>
                <h3>图结构与递归栈同步演示</h3>
                <p>先完成当前类别的主要学习内容，再用交互演示核对节点访问、入栈和回溯过程。</p>
              </header>
              <DfsTraversalVisualizer />
            </section>

            <section v-if="selectedDetail?.quality_evaluation" class="resource-quality-panel">
              <div class="resource-quality-head">
                <div>
                  <span>资源质量评测</span>
                  <strong>{{ selectedDetail.quality_evaluation.grade }} · {{ Math.round(selectedDetail.quality_evaluation.total_score) }} 分</strong>
                </div>
                <em :class="{ passed: selectedDetail.quality_evaluation.gate_passed }">
                  {{ selectedDetail.quality_evaluation.gate_passed ? '允许发布' : '需要复核' }}
                </em>
              </div>
              <div class="resource-quality-grid">
                <div v-for="dimension in detailQualityDimensions" :key="dimension.key">
                  <span>{{ dimension.label }}</span>
                  <strong>{{ Math.round(dimension.score) }}</strong>
                  <i><b :style="{ width: `${dimension.score}%` }" /></i>
                </div>
              </div>
            </section>

            <section class="resource-detail-evidence">
              <h3>RAG 证据</h3>
              <article v-for="item in detailEvidence" :key="item.chunk_id || item.chunkId || item.title">
                <strong>{{ item.title }}</strong>
                <p>{{ item.content }}</p>
                <small>{{ item.source }} · {{ Math.round(item.score * 100) }}%</small>
              </article>
            </section>
          </template>
        </article>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.resource-redesign {
  min-height: calc(100vh - 72px);
  overflow-x: hidden;
  padding: 32px 42px 56px;
  color: var(--color-ink);
}

.resource-stage {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 330px;
  gap: 28px;
  align-items: stretch;
}

.resource-brief-card,
.generation-evidence-card,
.generated-resource-card {
  min-width: 0;
  border: 1px solid rgba(16, 94, 83, 0.12);
  background: rgba(255, 255, 255, 0.78);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px);
}

.resource-brief-card {
  position: relative;
  min-height: 360px;
  border-radius: 18px;
  padding: 40px 44px;
  overflow: hidden;
}

.resource-brief-card::after {
  position: absolute;
  inset: auto 40px -120px auto;
  width: 360px;
  height: 260px;
  border-radius: 999px;
  background: radial-gradient(circle, rgba(248, 174, 107, 0.28), transparent 66%);
  content: '';
  pointer-events: none;
}

.resource-card-icon {
  width: 44px;
  height: 44px;
  margin-bottom: 18px;
  color: var(--color-primary);
}

.resource-card-icon svg,
.rail-icon svg {
  width: 100%;
  height: 100%;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.3;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.resource-brief-card h1 {
  margin: 0 0 30px;
  font-size: clamp(28px, 3vw, 42px);
  letter-spacing: 0;
}

.brief-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 26px 44px;
  max-width: 760px;
}

.brief-columns div {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.brief-columns span,
.resource-card-top span,
.generation-evidence-card span,
.progress-chip span {
  color: rgba(31, 54, 49, 0.62);
  font-size: 13px;
}

.brief-columns strong {
  font-size: 18px;
  overflow-wrap: anywhere;
}

.brief-columns em,
.brief-tags span,
.resource-card-top em {
  max-width: 100%;
  width: fit-content;
  border-radius: 999px;
  background: rgba(255, 104, 77, 0.12);
  color: #ee604c;
  font-style: normal;
  font-weight: 700;
  padding: 7px 12px;
}

.brief-columns .preference {
  background: rgba(247, 153, 57, 0.14);
  color: #d77b10;
}

.brief-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 28px 0;
}

.dfs-support-section {
  display: grid;
  gap: 14px;
  margin-top: 8px;
  border-top: 1px solid rgba(31, 54, 49, 0.1);
  padding-top: 22px;
}

.dfs-support-section > header {
  display: grid;
  gap: 5px;
}

.dfs-support-section > header span {
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.12em;
}

.dfs-support-section > header h3 {
  margin: 0;
  font-size: 20px;
}

.dfs-support-section > header p {
  margin: 0;
  color: rgba(31, 54, 49, 0.64);
  font-size: 13px;
  line-height: 1.65;
}

.resource-request-controls {
  display: grid;
  gap: 12px;
  margin: 28px 0;
}

.resource-type-chip {
  border: 1px solid rgba(31, 54, 49, 0.12);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--color-ink);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  font-weight: 800;
  padding: 7px 12px;
  transition: border-color 160ms ease, background 160ms ease, color 160ms ease;
}

.resource-type-chip:hover,
.resource-type-chip.selected {
  border-color: rgba(0, 121, 102, 0.38);
  background: rgba(0, 121, 102, 0.1);
  color: var(--color-primary);
}

.difficulty-control {
  align-items: center;
  display: flex;
  gap: 10px;
  width: fit-content;
}

.difficulty-control span {
  color: rgba(31, 54, 49, 0.62);
  font-size: 13px;
  font-weight: 800;
}

.difficulty-control select {
  border: 1px solid rgba(31, 54, 49, 0.14);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.86);
  color: var(--color-ink);
  min-height: 34px;
  padding: 0 10px;
}

.brief-tags span {
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid rgba(31, 54, 49, 0.1);
  color: var(--color-ink);
}

.primary-action {
  border: 0;
  border-radius: 12px;
  background: linear-gradient(135deg, #007966, #0a8e7e);
  color: #fff;
  cursor: pointer;
  font-weight: 800;
  min-height: 52px;
  padding: 0 28px;
  box-shadow: 0 16px 28px rgba(0, 116, 100, 0.22);
}

.primary-action:disabled {
  cursor: wait;
  opacity: 0.78;
}

.technical-details,
.task-error-line {
  font-size: 13px;
  font-weight: 700;
  margin: 14px 0 0;
}

.technical-details {
  color: rgba(31, 54, 49, 0.62);
}

.technical-details summary { cursor: pointer; font-size: 11px; font-weight: 700; }
.technical-details code { display: block; margin-top: 8px; font-size: 10px; color: rgba(31,54,49,.55); word-break: break-all; }

.task-error-line {
  color: #df5b45;
}

.generation-evidence-card {
  border-radius: 18px;
  display: flex;
  flex-direction: column;
  padding: 24px;
}

.side-title {
  align-items: center;
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.side-title h2 {
  flex: 1;
  font-size: 22px;
  margin: 0;
}

.side-title span,
.side-title i {
  border-radius: 999px;
  background: var(--color-primary);
  display: block;
}

.side-title span {
  width: 22px;
  height: 22px;
}

.side-title i {
  width: 9px;
  height: 9px;
}

.evidence-line {
  border-bottom: 1px solid rgba(31, 54, 49, 0.08);
  padding: 18px 0;
}

.evidence-line strong {
  display: block;
  margin-bottom: 8px;
}

.evidence-line p,
.active-mini p,
.generated-resource-card p,
.section-title-row p,
.rail-node p {
  color: rgba(31, 54, 49, 0.66);
  line-height: 1.7;
  margin: 0;
}

.active-mini {
  border-radius: 16px;
  margin-top: auto;
  padding: 16px;
  background: rgba(246, 248, 246, 0.9);
}

.model-runtime-card {
  border: 1px solid rgba(216, 124, 38, 0.18);
  border-radius: 14px;
  background: rgba(255, 244, 229, 0.82);
  display: grid;
  gap: 7px;
  margin: 16px 0;
  padding: 14px;
}

.model-runtime-card.is-real {
  border-color: rgba(0, 121, 102, 0.2);
  background: rgba(0, 121, 102, 0.08);
}

.model-runtime-card strong {
  overflow-wrap: anywhere;
}

.model-runtime-card p {
  color: rgba(31, 54, 49, 0.66);
  line-height: 1.6;
  margin: 0;
}

.active-mini strong {
  display: block;
  margin: 8px 0;
}

.orchestration-rail {
  position: relative;
  display: grid;
  grid-template-columns: repeat(6, minmax(100px, 1fr));
  gap: 18px;
  margin: 42px 0 34px;
  padding: 10px 20px 0;
  overflow: hidden;
}

.rail-line {
  position: absolute;
  left: 54px;
  right: 54px;
  top: 42px;
  height: 2px;
  border-top: 2px dashed rgba(0, 121, 102, 0.48);
  z-index: 0;
}

.rail-node {
  position: relative;
  z-index: 1;
  align-items: center;
  display: flex;
  flex-direction: column;
  text-align: center;
  gap: 8px;
  min-width: 0;
}

.rail-icon {
  align-items: center;
  background: rgba(255, 255, 255, 0.82);
  border-radius: 999px;
  color: rgba(31, 54, 49, 0.66);
  display: flex;
  height: 54px;
  justify-content: center;
  width: 54px;
  box-shadow: 0 10px 24px rgba(31, 54, 49, 0.1);
}

.rail-node.is-success .rail-icon {
  color: var(--color-primary);
}

.rail-node.is-running .rail-icon {
  background: #078270;
  color: #fff;
}

.rail-node strong {
  font-size: 14px;
}

.rail-node span {
  color: rgba(31, 54, 49, 0.58);
  font-size: 12px;
}

.rail-node p {
  font-size: 12px;
  max-width: 150px;
  overflow-wrap: anywhere;
}

.resource-pack {
  position: relative;
}

.section-title-row {
  align-items: end;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
}

.section-title-row h2 {
  font-size: 28px;
  margin: 0 0 8px;
}

.progress-chip {
  border: 1px solid rgba(16, 94, 83, 0.12);
  background: rgba(255, 255, 255, 0.72);
  border-radius: 16px;
  display: grid;
  gap: 4px;
  min-width: 118px;
  padding: 12px 18px;
}

.progress-chip strong {
  color: var(--color-primary);
  font-size: 22px;
}

.resource-card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

.generated-resource-card {
  border-radius: 16px;
  display: grid;
  gap: 14px;
  min-height: 220px;
  padding: 22px;
  cursor: pointer;
  transition:
    border-color 180ms ease,
    box-shadow 180ms ease,
    transform 180ms ease;
}

.generated-resource-card:hover,
.generated-resource-card:focus-visible {
  border-color: rgba(0, 121, 102, 0.34);
  box-shadow: 0 22px 42px rgba(31, 54, 49, 0.12);
  outline: none;
  transform: translateY(-2px);
}

.resource-card-top,
.resource-card-meta {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: space-between;
}

.resource-card-top span,
.resource-card-top em {
  font-weight: 800;
}

.resource-card-top em {
  background: rgba(0, 121, 102, 0.14);
  color: var(--color-primary);
}

.generated-resource-card h3 {
  font-size: 20px;
  margin: 0;
  overflow-wrap: anywhere;
}

.resource-card-meta {
  margin-top: auto;
  color: rgba(31, 54, 49, 0.7);
  font-size: 13px;
  font-weight: 700;
}

.resource-open-button {
  justify-self: start;
  border: 1px solid rgba(0, 121, 102, 0.2);
  border-radius: 999px;
  background: rgba(0, 121, 102, 0.09);
  color: var(--color-primary);
  cursor: pointer;
  font-weight: 900;
  min-height: 36px;
  padding: 0 14px;
}

.resource-detail-backdrop {
  position: fixed;
  z-index: 1000;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgba(15, 29, 26, 0.34);
  padding: 24px;
}

.resource-detail-panel {
  position: relative;
  display: grid;
  gap: 18px;
  width: min(920px, 100%);
  min-width: 0;
  max-height: min(86vh, 820px);
  overflow: auto;
  border: 1px solid rgba(16, 94, 83, 0.16);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 28px 80px rgba(14, 35, 31, 0.24);
  padding: 30px;
}

.resource-detail-close {
  position: absolute;
  top: 18px;
  right: 18px;
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 999px;
  background: rgba(31, 54, 49, 0.08);
  color: var(--color-ink);
  cursor: pointer;
  font-size: 24px;
  line-height: 1;
}

.resource-detail-head {
  display: grid;
  gap: 10px;
  padding-right: 46px;
}

.resource-detail-head span {
  width: fit-content;
  border-radius: 999px;
  background: rgba(0, 121, 102, 0.12);
  color: var(--color-primary);
  font-weight: 900;
  padding: 8px 12px;
}

.resource-detail-head h2 {
  margin: 0;
  font-size: clamp(24px, 3vw, 34px);
  overflow-wrap: anywhere;
}

.resource-detail-head p,
.resource-detail-evidence p,
.resource-detail-state,
.resource-detail-error {
  margin: 0;
  color: rgba(31, 54, 49, 0.7);
  line-height: 1.75;
}

.resource-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.resource-detail-meta span {
  max-width: 100%;
  border-radius: 999px;
  background: rgba(31, 54, 49, 0.07);
  color: rgba(31, 54, 49, 0.76);
  font-weight: 800;
  padding: 8px 11px;
}

.resource-detail-content {
  border: 1px solid rgba(31, 54, 49, 0.1);
  border-radius: 14px;
  background: rgba(247, 250, 248, 0.9);
  padding: 20px;
}

.resource-quality-panel {
  display: grid;
  gap: 16px;
  border: 1px solid rgba(0, 121, 102, 0.15);
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(0, 121, 102, 0.08), rgba(255, 255, 255, 0.84));
  padding: 18px;
}

.resource-quality-head,
.resource-quality-head div {
  display: flex;
  align-items: center;
  gap: 10px;
}

.resource-quality-head {
  justify-content: space-between;
}

.resource-quality-head div {
  align-items: flex-start;
  flex-direction: column;
  gap: 3px;
}

.resource-quality-head span,
.resource-quality-grid span {
  color: rgba(31, 54, 49, 0.64);
  font-size: 12px;
  font-weight: 800;
}

.resource-quality-head strong {
  color: var(--color-primary);
  font-size: 20px;
}

.resource-quality-head em {
  border-radius: 999px;
  background: rgba(223, 91, 69, 0.12);
  color: #c94f3b;
  font-style: normal;
  font-weight: 900;
  padding: 7px 11px;
}

.resource-quality-head em.passed {
  background: rgba(0, 121, 102, 0.12);
  color: var(--color-primary);
}

.resource-quality-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.resource-quality-grid div {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.resource-quality-grid strong {
  font-size: 17px;
}

.resource-quality-grid i {
  height: 5px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(31, 54, 49, 0.1);
}

.resource-quality-grid b {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--color-primary);
}

.resource-detail-evidence {
  display: grid;
  gap: 12px;
}

.resource-detail-evidence h3 {
  margin: 0;
}

.resource-detail-evidence article {
  border-left: 3px solid var(--color-primary);
  background: rgba(0, 121, 102, 0.06);
  border-radius: 12px;
  padding: 14px 16px;
}

.resource-detail-evidence strong,
.resource-detail-evidence small {
  display: block;
}

.resource-detail-evidence small {
  margin-top: 8px;
  color: rgba(31, 54, 49, 0.58);
  font-weight: 800;
  overflow-wrap: anywhere;
}

.resource-detail-error {
  color: #df5b45;
  font-weight: 800;
}

.tone-amber {
  border-top: 4px solid #f2a13d;
}

.tone-coral {
  border-top: 4px solid #ff725e;
}

.tone-blue {
  border-top: 4px solid #477bbd;
}

.tone-teal {
  border-top: 4px solid var(--color-primary);
}

@media (max-width: 1080px) {
  .resource-stage {
    grid-template-columns: 1fr;
  }

  .orchestration-rail,
  .resource-card-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .rail-line {
    display: none;
  }
}

@media (max-width: 720px) {
  .resource-redesign {
    padding: 24px 18px 40px;
  }

  .resource-brief-card,
  .resource-detail-panel {
    padding: 24px;
  }

  .brief-columns,
  .orchestration-rail,
  .resource-card-grid {
    grid-template-columns: 1fr;
  }

  .resource-quality-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
