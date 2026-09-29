<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  completeLearningPathDay,
  generateLearningPath,
  getCurrentLearningPath,
  startBehaviorPathReplan,
} from '../api/learningPath';
import { waitForAgentTask } from '../api/task';
import SplitText from '../components/SplitText.vue';
import { shouldEmitExposure, track } from '../research';
import type { LearningPath as LearningPathData, PageKey } from '../types';
import type { ModelRuntime, ResourceSafety } from '../types/api';

defineEmits<{
  navigate: [page: PageKey];
}>();

interface PathNode {
  day: number;
  label: string;
  title: string;
  detail: string;
  status: 'done' | 'active' | 'repair' | 'recommend' | 'locked';
  meta: string;
  reason: string;
  difficulty: string;
  evidenceCount: number;
}

interface PathTaskResult {
  generation_mode?: ModelRuntime['mode'];
  model_runtime?: ModelRuntime;
  safety?: ResourceSafety;
}

const learningPath = ref<LearningPathData | null>(null);
const loading = ref(true);
const generating = ref(false);
const generationProgress = ref(0);
const activeTaskId = ref('');
const errorMessage = ref('');
const modelRuntime = ref<ModelRuntime | null>(null);
const safetyReview = ref<ResourceSafety | null>(null);
const completingDay = ref<number | null>(null);
const behaviorReplanning = ref(false);
const learningFeedback = ref('');
const deferredDay = ref<number | null>(null);

const pathNodes = computed<PathNode[]>(() => {
  const plan = learningPath.value?.dailyPlan ?? [];
  const completed = new Set(learningPath.value?.completedDays ?? []);
  const firstIncomplete = plan.findIndex((day) => !completed.has(day.day));
  return plan.slice(0, 6).map((day, index) => ({
    day: day.day,
    label: completed.has(day.day) ? '已完成' : index === firstIncomplete ? '当前' : index === firstIncomplete + 1 ? 'AI 补救' : index === firstIncomplete + 2 ? '推荐' : '即将开始',
    title: day.theme,
    detail: day.expectedOutcome,
    status: completed.has(day.day) ? 'done' : index === firstIncomplete ? 'active' : index === firstIncomplete + 1 ? 'repair' : index === firstIncomplete + 2 ? 'recommend' : 'locked',
    meta: `${day.tasks.reduce((total, task) => total + task.estimatedMinutes, 0)} 分钟`,
    reason: day.reason,
    difficulty: day.difficulty,
    evidenceCount: day.evidenceChunkIds.length,
}));
});

const focusPoint = computed(() => learningPath.value?.dailyPlan[0]?.theme ?? '待生成学习任务');
const nextTestName = computed(() => `${focusPoint.value} 阶段评估`);
const recentAdjustment = computed(() => learningPath.value?.adjustmentStrategy || '路径会在完成测验或画像更新后自动调整。');
const adjustmentSignal = computed(() => learningPath.value?.adjustmentSignal);
const adjustmentStatusLabel = computed(() => {
  const status = adjustmentSignal.value?.status;
  if (status === 'replan_recommended') return '建议调整路径';
  if (status === 'watch') return '持续观察';
  return '路径稳定';
});
const pacingSummary = computed(() => {
  const pacing = adjustmentSignal.value?.pacing;
  if (!pacing?.currentDailyMinutes) return '等待路径生成后评估';
  if (pacing.currentDailyMinutes === pacing.recommendedDailyMinutes) {
    return `维持每日 ${pacing.currentDailyMinutes} 分钟`;
  }
  return `每日 ${pacing.currentDailyMinutes} → ${pacing.recommendedDailyMinutes} 分钟`;
});
const pathVersionSummary = computed(() => {
  const path = learningPath.value;
  if (!path?.version) return '尚未生成路径版本';
  if (path.replanTrigger === 'quiz_evaluation') {
    return `测评驱动 · v${path.previousVersion} → v${path.version}`;
  }
  if (path.replanTrigger === 'behavior_signal') {
    return `行为驱动 · v${path.previousVersion} → v${path.version}`;
  }
  return `当前版本 v${path.version}`;
});
const modelRuntimeSummary = computed(() => {
  const runtime = modelRuntime.value;
  if (!runtime) return '等待本轮路径生成记录';
  if (runtime.mode === 'real_model') {
    return `${runtime.model || runtime.provider || '真实模型'} · ${runtime.call_count ?? 0} 次调用 · ${runtime.total_tokens ?? 0} Token`;
  }
  return '确定性开发模式';
});

const loadPath = async () => {
  const result = await getCurrentLearningPath();
  learningPath.value = result.learningPath;
  loading.value = false;
  const pathId = `path:${result.learningPath?.target ?? 'unknown'}`;
  if (result.learningPath?.adjustmentStrategy && shouldEmitExposure(`path_change_shown:${pathId}`)) {
    track('path_change_shown', {
      page: 'learning-path',
      metadata: {
        path_id: pathId,
        path_length: result.learningPath.dailyPlan?.length ?? 0,
        adjustment_strategy_present: true,
      },
    });
  }
};

const regeneratePath = async () => {
  generating.value = true;
  errorMessage.value = '';
  activeTaskId.value = '';
  generationProgress.value = 3;
  try {
    const result = await generateLearningPath({
      courseIds: [1, 2],
      target: learningPath.value?.target || '生成学习路径',
      days: 7,
      dailyMinutes: learningPath.value?.dailyMinutes || 40,
    });
    activeTaskId.value = result.taskId;
    const completedTask = await waitForAgentTask(result.taskId, (task) => {
      generationProgress.value = task.progress;
    });
    const taskResult = (completedTask.result || {}) as PathTaskResult;
    modelRuntime.value = taskResult.model_runtime ?? null;
    safetyReview.value = taskResult.safety ?? null;
    generationProgress.value = 100;
    await loadPath();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '路径生成失败';
  } finally {
    generating.value = false;
  }
};

const completeNode = async (day: number) => {
  if (completingDay.value !== null) return;
  track('path_change_accepted', {
    page: 'learning-path',
    metadata: {
      node_day: day,
      node_label: pathNodes.value.find((node) => node.day === day)?.label ?? 'active',
      adjustment_strategy_present: Boolean(learningPath.value?.adjustmentStrategy),
    },
  });
  completingDay.value = day;
  learningFeedback.value = '';
  errorMessage.value = '';
  try {
    const result = await completeLearningPathDay(day);
    const mastery = result.learning_update.mastery_updates[0];
    learningFeedback.value = mastery
      ? `第 ${day} 天已完成，${mastery.knowledge_point} 掌握度 ${mastery.previous_score}% → ${mastery.mastery_score}%`
      : `第 ${day} 天已完成；该事件已记录或此前已经提交。`;
    await loadPath();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '路径节点完成记录失败';
  } finally {
    completingDay.value = null;
  }
};

const runBehaviorReplan = async () => {
  if (behaviorReplanning.value) return;
  behaviorReplanning.value = true;
  errorMessage.value = '';
  learningFeedback.value = '';
  try {
    const created = await startBehaviorPathReplan();
    activeTaskId.value = created.taskId;
    const completedTask = await waitForAgentTask(created.taskId, (task) => {
      generationProgress.value = task.progress;
    });
    const taskResult = (completedTask.result || {}) as PathTaskResult;
    modelRuntime.value = taskResult.model_runtime ?? null;
    safetyReview.value = taskResult.safety ?? null;
    learningFeedback.value = created.reused
      ? '已复用同一行为信号对应的重规划任务。'
      : '行为驱动重规划已完成，新的路径版本已生效。';
    await loadPath();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '行为驱动重规划失败，原路径保持不变';
  } finally {
    behaviorReplanning.value = false;
  }
};

const deferNode = (day: number) => {
  deferredDay.value = day;
  learningFeedback.value = `已将第 ${day} 天节点标记为稍后处理，当前路径不会被删除。`;
};

const skipNode = (day: number) => {
  deferredDay.value = day;
  learningFeedback.value = `已跳过第 ${day} 天节点，建议先通过智能辅导补充理解。`;
};

onMounted(async () => {
  await loadPath();
});
</script>

<template>
  <div class="path-redesign ai-page-bg page-enter">
    <section class="path-hero">
      <div>
        <SplitText tag="h1" text="个性化学习路径" />
        <p>该路径根据画像、练习结果和薄弱点动态生成。</p>
        <small class="path-version">{{ pathVersionSummary }}</small>
      </div>
      <button class="secondary-pill hover-scale" type="button" :disabled="generating" @click="regeneratePath">
        {{ generating ? `生成中 ${generationProgress}%` : '重新生成路径' }}
      </button>
    </section>

    <p v-if="activeTaskId" class="path-task-line">task_id: {{ activeTaskId }}</p>
    <p v-if="modelRuntime" class="path-runtime-line">
      {{ modelRuntimeSummary }} · SafetyAgent {{ safetyReview?.passed ? '复核通过' : '等待复核' }}
    </p>
    <p v-if="errorMessage" class="path-error-line">{{ errorMessage }}</p>
    <p v-if="learningFeedback" class="path-runtime-line">{{ learningFeedback }}</p>

    <section v-if="loading" class="path-loading interactive-card">
      正在读取学习路径...
    </section>

    <section v-else class="path-layout">
      <div class="path-canvas interactive-card">
        <div class="path-flow-line learning-path-flow" aria-hidden="true"></div>
        <article
          v-for="node in pathNodes"
          :key="node.day"
          class="path-node-card"
          :class="`node-${node.status}`"
        >
          <div class="node-marker" aria-hidden="true">
            <span v-if="node.status === 'done'">✓</span>
            <span v-else-if="node.status === 'active'">▶</span>
            <span v-else-if="node.status === 'repair'">!</span>
            <span v-else-if="node.status === 'recommend'">书</span>
            <span v-else>锁</span>
          </div>
          <div class="node-content">
            <div class="node-topline">
              <span>{{ node.label }}</span>
              <small>{{ node.meta }}</small>
            </div>
            <h2>{{ node.title }}</h2>
            <p>{{ node.detail }}</p>
            <p v-if="node.reason" class="node-reason">{{ node.reason }}</p>
            <small v-if="node.difficulty" class="node-difficulty">{{ node.difficulty }}</small>
            <small class="node-evidence">RAG 证据 {{ node.evidenceCount }} 条</small>
            <button
              v-if="node.status === 'active'"
              class="primary-mini hover-scale"
              type="button"
              :disabled="completingDay !== null"
              @click="completeNode(node.day)"
            >
              {{ completingDay === node.day ? '记录中...' : '完成本节点' }}
            </button>
            <div v-if="node.status === 'active'" class="node-secondary-actions">
              <button type="button" @click="deferNode(node.day)">稍后</button>
              <button type="button" @click="skipNode(node.day)">跳过</button>
            </div>
          </div>
        </article>
      </div>

      <aside class="path-side">
        <article class="path-analysis interactive-card">
          <div class="path-side-icon" aria-hidden="true"></div>
          <h2>路径分析</h2>
          <span>当前目标</span>
          <strong>{{ learningPath?.target || '暂无路径目标' }}</strong>
          <span>已识别薄弱点</span>
          <em>{{ focusPoint }}</em>
          <div class="divider"></div>
          <span>AI 路径说明</span>
          <p>“{{ learningPath?.personalizationSummary || '等待 ProfileAgent 汇总个性化依据。' }}”</p>
          <div class="divider"></div>
          <span>动态调整策略</span>
          <p>{{ learningPath?.adjustmentStrategy || '完成测验后将根据新画像调整路径。' }}</p>
        </article>

        <article
          class="adjustment-signal-card interactive-card"
          :data-status="adjustmentSignal?.status || 'stable'"
        >
          <div class="signal-heading">
            <div>
              <span>行为驱动调整信号</span>
              <h3>{{ adjustmentStatusLabel }}</h3>
            </div>
            <strong>{{ adjustmentSignal?.riskScore ?? 0 }}</strong>
          </div>
          <p class="signal-pacing">{{ pacingSummary }}</p>
          <ul class="signal-reasons">
            <li v-for="reason in adjustmentSignal?.reasons.slice(0, 3)" :key="reason">{{ reason }}</li>
          </ul>
          <small v-if="adjustmentSignal?.replanCandidate && !adjustmentSignal.cooldown.eligible">
            冷却期剩余 {{ adjustmentSignal.cooldown.remainingMinutes }} 分钟，期间不会重复触发。
          </small>
          <small v-else>判定仅基于已记录的学习证据，不会在本阶段自动调用模型。</small>
          <button
            v-if="adjustmentSignal?.shouldReplan"
            class="signal-replan-button hover-scale"
            type="button"
            :disabled="behaviorReplanning"
            @click="runBehaviorReplan"
          >
            {{ behaviorReplanning ? `受控重规划中 ${generationProgress}%` : '执行受控路径调整' }}
          </button>
        </article>

        <article class="next-test-card interactive-card">
          <span>下一阶段测试</span>
          <strong>{{ nextTestName }}</strong>
          <p>预计 3 个学习阶段后到达。</p>
        </article>

        <article class="recent-adjust-card interactive-card">
          <h3>近期调整</h3>
          <p>{{ recentAdjustment }}</p>
          <ul v-if="learningPath?.replanChanges.length" class="recent-change-list">
            <li v-for="change in learningPath.replanChanges" :key="`${change.action}-${change.knowledgePoint}`">
              <strong>{{ change.knowledgePoint }}</strong>
              <span>{{ change.reason }}</span>
            </li>
          </ul>
          <div class="adjust-row">
            <span>当前重点</span>
            <strong>{{ focusPoint }}</strong>
            <button type="button" @click="$emit('navigate', 'evaluation')">+</button>
          </div>
        </article>
      </aside>
    </section>
  </div>
</template>

<style scoped>
.path-redesign {
  min-height: calc(100vh - 72px);
  overflow-x: hidden;
  padding: 48px 72px 60px;
  color: var(--color-ink);
}

.path-hero {
  align-items: center;
  display: flex;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 44px;
}

.path-hero h1 {
  font-size: clamp(32px, 4vw, 48px);
  margin: 0 0 10px;
}

.path-hero p,
.path-analysis p,
.next-test-card p,
.recent-adjust-card p,
.node-content p {
  color: rgba(31, 54, 49, 0.66);
  line-height: 1.7;
  margin: 0;
}

.secondary-pill {
  border: 1px solid rgba(0, 121, 102, 0.18);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--color-primary);
  cursor: pointer;
  font-weight: 800;
  min-height: 48px;
  padding: 0 26px;
}

.secondary-pill:disabled {
  cursor: wait;
  opacity: 0.72;
}

.path-task-line,
.path-runtime-line,
.path-error-line {
  font-size: 13px;
  font-weight: 700;
  margin: -30px 0 24px;
}

.path-task-line {
  color: rgba(31, 54, 49, 0.62);
}

.path-version {
  color: var(--color-primary);
  display: inline-block;
  font-weight: 800;
  margin-top: 8px;
}

.recent-change-list {
  display: grid;
  gap: 8px;
  list-style: none;
  margin: 16px 0 0;
  padding: 0;
}

.recent-change-list li {
  display: grid;
  gap: 4px;
  border-radius: 10px;
  background: rgba(255, 104, 77, 0.08);
  padding: 10px 12px;
}

.recent-change-list span {
  color: rgba(31, 54, 49, 0.66);
  font-size: 12px;
  line-height: 1.5;
}

.path-runtime-line {
  color: var(--color-primary);
  margin-top: -18px;
}

.path-error-line {
  color: #df5b45;
}

.path-loading,
.path-canvas,
.path-analysis,
.adjustment-signal-card,
.next-test-card,
.recent-adjust-card {
  border: 1px solid rgba(16, 94, 83, 0.1);
  background: rgba(255, 255, 255, 0.78);
  border-radius: 18px;
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px);
}

.adjustment-signal-card {
  border: 1px solid rgba(0, 121, 102, 0.16);
  background: rgba(255, 255, 255, 0.82);
  padding: 24px;
}

.adjustment-signal-card[data-status='watch'] {
  border-color: rgba(223, 132, 32, 0.42);
  background: rgba(255, 249, 237, 0.86);
}

.adjustment-signal-card[data-status='replan_recommended'] {
  border-color: rgba(255, 96, 72, 0.48);
  background: rgba(255, 246, 243, 0.9);
}

.signal-heading {
  align-items: center;
  display: flex;
  justify-content: space-between;
  gap: 18px;
}

.signal-heading span {
  color: rgba(31, 54, 49, 0.58);
  font-size: 12px;
  font-weight: 800;
}

.signal-heading h3 {
  font-size: 20px;
  margin: 5px 0 0;
}

.signal-heading > strong {
  align-items: center;
  border-radius: 999px;
  background: rgba(0, 121, 102, 0.1);
  color: var(--color-primary);
  display: flex;
  font-size: 18px;
  height: 44px;
  justify-content: center;
  min-width: 44px;
}

.signal-pacing {
  color: var(--color-primary) !important;
  font-weight: 800;
  margin-top: 16px !important;
}

.signal-reasons {
  color: rgba(31, 54, 49, 0.7);
  display: grid;
  font-size: 13px;
  gap: 7px;
  line-height: 1.55;
  margin: 14px 0;
  padding-left: 18px;
}

.adjustment-signal-card small {
  color: rgba(31, 54, 49, 0.52);
  line-height: 1.5;
}

.signal-replan-button {
  border: 0;
  border-radius: 10px;
  background: var(--color-primary);
  color: #fff;
  cursor: pointer;
  display: block;
  font-weight: 800;
  margin-top: 16px;
  min-height: 42px;
  padding: 0 16px;
  width: 100%;
}

.signal-replan-button:disabled {
  cursor: wait;
  opacity: 0.68;
}

.path-loading {
  padding: 28px;
}

.path-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 28px;
}

.path-canvas {
  min-height: 860px;
  overflow: hidden;
  padding: 58px 52px;
  position: relative;
}

.path-flow-line {
  position: absolute;
  left: 50%;
  top: 96px;
  bottom: 70px;
  width: 3px;
  border-left: 3px dashed rgba(0, 121, 102, 0.26);
  transform: translateX(-50%);
}

.path-node-card {
  align-items: center;
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 24px;
  margin: 0 auto 54px;
  max-width: min(100%, 560px);
  position: relative;
  z-index: 1;
}

.path-node-card:nth-child(even) {
  transform: translateX(-38px);
}

.path-node-card:nth-child(odd) {
  transform: translateX(38px);
}

.node-marker {
  align-items: center;
  border-radius: 999px;
  display: flex;
  font-weight: 900;
  height: 58px;
  justify-content: center;
  width: 58px;
  justify-self: center;
}

.node-content {
  border: 1px solid rgba(31, 54, 49, 0.08);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 16px 34px rgba(31, 54, 49, 0.08);
  padding: 22px 26px;
}

.node-active .node-content {
  background: linear-gradient(135deg, rgba(224, 246, 242, 0.9), rgba(255, 255, 255, 0.86));
}

.node-repair .node-content {
  border-color: rgba(255, 104, 77, 0.62);
  background: rgba(255, 247, 244, 0.82);
}

.node-recommend .node-content,
.node-locked .node-content {
  opacity: 0.74;
}

.node-done .node-marker,
.node-active .node-marker {
  background: #087c6a;
  color: #fff;
}

.node-repair .node-marker {
  border: 3px solid #ff6d54;
  color: #ff6d54;
  background: #fff;
}

.node-recommend .node-marker {
  border: 2px solid #f0a33c;
  color: #df8420;
  background: #fff8eb;
}

.node-locked .node-marker {
  background: #efefef;
  color: #a0aaa7;
}

.node-topline {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 16px;
}

.node-topline span {
  border-radius: 999px;
  background: rgba(0, 121, 102, 0.12);
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 800;
  padding: 5px 10px;
}

.node-topline small {
  color: rgba(31, 54, 49, 0.7);
  font-weight: 800;
}

.node-content h2 {
  font-size: 24px;
  margin: 14px 0 8px;
  overflow-wrap: anywhere;
}

.primary-mini {
  border: 0;
  border-radius: 10px;
  background: var(--color-primary);
  color: #fff;
  cursor: pointer;
  font-weight: 800;
  margin-top: 18px;
  min-height: 40px;
  padding: 0 18px;
}
.node-secondary-actions { display:flex; gap:10px; margin-top:10px; }
.node-secondary-actions button { border:0; background:transparent; color:rgba(31,54,49,.58); cursor:pointer; font-size:11px; padding:4px 0; }
.node-secondary-actions button:hover { color:var(--color-primary); }

.path-side {
  display: grid;
  gap: 26px;
  align-content: start;
}

.path-analysis {
  background: linear-gradient(135deg, rgba(222, 249, 244, 0.92), rgba(255, 255, 255, 0.82));
  padding: 28px;
}

.path-side-icon {
  width: 46px;
  height: 46px;
  border-radius: 999px;
  background: radial-gradient(circle, rgba(0, 121, 102, 0.22), rgba(0, 121, 102, 0.08));
  margin-bottom: 12px;
}

.path-analysis h2 {
  font-size: 26px;
  margin: 0 0 24px;
}

.path-analysis span,
.next-test-card span {
  color: rgba(31, 54, 49, 0.58);
  display: block;
  font-size: 13px;
  margin-top: 18px;
}

.path-analysis strong {
  display: block;
  font-size: 18px;
  margin-top: 8px;
  overflow-wrap: anywhere;
}

.path-analysis em {
  color: #ff6048;
  display: block;
  font-style: normal;
  font-weight: 900;
  margin-top: 8px;
}

.divider {
  height: 1px;
  margin: 26px 0 8px;
  background: rgba(31, 54, 49, 0.1);
}

.next-test-card {
  border: 2px solid rgba(54, 106, 185, 0.72);
  padding: 24px;
}

.next-test-card strong {
  display: block;
  font-size: 18px;
  margin: 8px 0;
}

.recent-adjust-card {
  margin-top: 360px;
  padding: 24px;
}

.recent-adjust-card h3 {
  margin: 0 0 20px;
}

.adjust-row {
  align-items: center;
  border-radius: 14px;
  background: rgba(31, 54, 49, 0.06);
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, auto) 34px;
  gap: 12px;
  margin-top: 14px;
  padding: 12px;
}

.adjust-row strong {
  color: #ff6048;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.adjust-row button {
  border: 0;
  border-radius: 999px;
  background: rgba(255, 104, 77, 0.16);
  color: #ff6048;
  cursor: pointer;
  font-weight: 900;
  height: 34px;
}

@media (max-width: 980px) {
  .path-redesign {
    padding: 30px 22px;
  }

  .path-layout {
    grid-template-columns: 1fr;
  }

  .recent-adjust-card {
    margin-top: 0;
  }
}

@media (max-width: 640px) {
  .path-node-card,
  .path-node-card:nth-child(even),
  .path-node-card:nth-child(odd) {
    grid-template-columns: 1fr;
    transform: none;
  }

  .path-canvas {
    min-height: 0;
    padding: 32px 22px;
  }

  .path-flow-line {
    display: none;
  }
}
</style>
