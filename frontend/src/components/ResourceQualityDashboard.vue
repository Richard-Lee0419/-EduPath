<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  createResourceQualityRegressionTask,
  decideResourceQualityRepair,
  executeResourceQualityRepair,
  getResourceQualityRepairComparison,
  getResourceQualityMetrics,
  listResourceQualityRepairs,
  publishResourceQualityRepair,
  qualityRegressionResultFromTask,
  rollbackResourceQualityRepair,
} from '@/api/resource';
import { waitForAgentTask } from '@/api/task';
import type {
  ResourceQualityMetrics,
  ResourceQualityRepairComparison,
  ResourceQualityRegressionResult,
  ResourceQualityRepairItem,
} from '@/types/api';

const props = defineProps<{
  courseId: number | null;
  refreshToken?: number;
}>();

const metrics = ref<ResourceQualityMetrics | null>(null);
const loading = ref(false);
const errorMessage = ref('');
const windowDays = ref(30);
const resourceType = ref('');
const generationMode = ref('');
const regressionRunning = ref(false);
const regressionProgress = ref(0);
const regressionTaskId = ref('');
const regressionError = ref('');
const regressionResult = ref<ResourceQualityRegressionResult | null>(null);
const repairs = ref<ResourceQualityRepairItem[]>([]);
const repairTotal = ref(0);
const repairLoading = ref(false);
const repairError = ref('');
const reviewNotes = ref<Record<string, string>>({});
const decisionBusyId = ref('');
const executionBusyId = ref('');
const executionProgress = ref<Record<string, number>>({});
const comparisonByRepair = ref<Record<string, ResourceQualityRepairComparison>>({});
const comparisonBusyId = ref('');
const publicationBusyId = ref('');
const publicationNotes = ref<Record<string, string>>({});

const typeLabels: Record<string, string> = {
  lecture: '个性化讲义',
  mindmap: '思维导图',
  quiz: '专项小测',
  codelab: '代码实验',
  animation_script: '动画脚本',
  flowchart: '流程图',
  reading: '拓展阅读',
};

const modeLabels: Record<string, string> = {
  real_model: '真实模型',
  deterministic_fallback: '确定性回退',
  unknown: '历史未记录',
};

const repairStatusLabels: Record<string, string> = {
  pending_review: '待人工确认',
  approved: '已批准',
  rejected: '已驳回',
  in_progress: '修复中',
  completed: '已完成',
  failed: '修复失败',
  published: '已发布',
  rolled_back: '已回滚',
};

const trendPoints = computed(() => {
  const rows = metrics.value?.trend ?? [];
  if (!rows.length) return [];
  const width = 720;
  const height = 164;
  const horizontalPadding = 18;
  const verticalPadding = 18;
  const usableWidth = width - horizontalPadding * 2;
  const usableHeight = height - verticalPadding * 2;
  return rows.map((item, index) => ({
    ...item,
    x: rows.length === 1 ? width / 2 : horizontalPadding + (index / (rows.length - 1)) * usableWidth,
    y: verticalPadding + ((100 - Math.max(0, Math.min(100, item.average_score))) / 100) * usableHeight,
  }));
});

const trendPath = computed(() => trendPoints.value.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '));
const latestTrend = computed(() => trendPoints.value.at(-1));
const hasData = computed(() => Boolean(metrics.value?.summary.evaluated_resources));

const percent = (value: number) => `${Math.round(value * 100)}%`;
const displayScore = (value: number) => Math.round(value * 10) / 10;
const displayType = (value: string) => typeLabels[value] ?? value;
const displayMode = (value: string) => modeLabels[value] ?? value;
const contentPreview = (value?: string) => {
  const normalized = (value || '').replace(/\s+/g, ' ').trim();
  return normalized.length > 180 ? `${normalized.slice(0, 180)}…` : normalized || '暂无正文';
};
const formatFreshness = (value?: string) => value
  ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
  : '暂无有效快照';

const loadMetrics = async () => {
  if (!props.courseId) {
    metrics.value = null;
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  try {
    metrics.value = await getResourceQualityMetrics({
      courseId: props.courseId,
      resourceType: resourceType.value || undefined,
      generationMode: generationMode.value || undefined,
      windowDays: windowDays.value,
    });
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '质量指标加载失败';
  } finally {
    loading.value = false;
  }
};

const loadRepairQueue = async () => {
  if (!props.courseId) {
    repairs.value = [];
    repairTotal.value = 0;
    return;
  }
  repairLoading.value = true;
  repairError.value = '';
  try {
    const response = await listResourceQualityRepairs({ courseId: props.courseId, page: 1, size: 6 });
    repairs.value = response.items;
    repairTotal.value = response.total;
  } catch (error) {
    repairError.value = error instanceof Error ? error.message : '受控修复队列加载失败';
  } finally {
    repairLoading.value = false;
  }
};

const decideRepair = async (item: ResourceQualityRepairItem, decision: 'approve' | 'reject') => {
  if (!item.can_review || decisionBusyId.value) return;
  const note = (reviewNotes.value[item.repair_id] || '').trim();
  if (decision === 'reject' && !note) {
    repairError.value = '驳回时请填写原因，便于保留审计依据。';
    return;
  }
  decisionBusyId.value = item.repair_id;
  repairError.value = '';
  try {
    const result = await decideResourceQualityRepair(item.repair_id, { decision, note });
    repairs.value = repairs.value.map((row) => row.repair_id === item.repair_id ? result.item : row);
    delete reviewNotes.value[item.repair_id];
  } catch (error) {
    repairError.value = error instanceof Error ? error.message : '审核操作失败';
  } finally {
    decisionBusyId.value = '';
  }
};

const executeRepair = async (item: ResourceQualityRepairItem) => {
  if (!item.can_execute || executionBusyId.value) return;
  executionBusyId.value = item.repair_id;
  repairError.value = '';
  executionProgress.value[item.repair_id] = 2;
  try {
    const created = await executeResourceQualityRepair(item.repair_id);
    await waitForAgentTask(created.task_id, (snapshot) => {
      executionProgress.value[item.repair_id] = snapshot.progress;
    });
    executionProgress.value[item.repair_id] = 100;
    await loadRepairQueue();
  } catch (error) {
    repairError.value = error instanceof Error ? error.message : '受控修复任务执行失败';
    await loadRepairQueue();
  } finally {
    executionBusyId.value = '';
  }
};

const toggleComparison = async (item: ResourceQualityRepairItem) => {
  if (!item.can_compare || comparisonBusyId.value) return;
  if (comparisonByRepair.value[item.repair_id]) {
    delete comparisonByRepair.value[item.repair_id];
    return;
  }
  comparisonBusyId.value = item.repair_id;
  repairError.value = '';
  try {
    comparisonByRepair.value[item.repair_id] = await getResourceQualityRepairComparison(item.repair_id);
  } catch (error) {
    repairError.value = error instanceof Error ? error.message : '候选版本对比加载失败';
  } finally {
    comparisonBusyId.value = '';
  }
};

const publishRepair = async (item: ResourceQualityRepairItem) => {
  const comparison = comparisonByRepair.value[item.repair_id];
  if (!item.can_publish || !comparison?.candidate_base_version || publicationBusyId.value) return;
  publicationBusyId.value = item.repair_id;
  repairError.value = '';
  try {
    await publishResourceQualityRepair(item.repair_id, {
      expectedResourceVersion: comparison.candidate_base_version,
      note: publicationNotes.value[item.repair_id],
    });
    delete comparisonByRepair.value[item.repair_id];
    delete publicationNotes.value[item.repair_id];
    await Promise.all([loadRepairQueue(), loadMetrics()]);
  } catch (error) {
    repairError.value = error instanceof Error ? error.message : '候选版本发布失败';
  } finally {
    publicationBusyId.value = '';
  }
};

const rollbackRepair = async (item: ResourceQualityRepairItem) => {
  if (!item.can_rollback || publicationBusyId.value) return;
  const confirmed = window.confirm('确认回滚这次修复发布吗？系统会恢复发布前快照，并生成一个新的审计版本。');
  if (!confirmed) return;
  publicationBusyId.value = item.repair_id;
  repairError.value = '';
  try {
    await rollbackResourceQualityRepair(item.repair_id, publicationNotes.value[item.repair_id]);
    delete publicationNotes.value[item.repair_id];
    await Promise.all([loadRepairQueue(), loadMetrics()]);
  } catch (error) {
    repairError.value = error instanceof Error ? error.message : '修复版本回滚失败';
  } finally {
    publicationBusyId.value = '';
  }
};

const runRegression = async () => {
  if (!props.courseId || !metrics.value?.alerts.length || regressionRunning.value) return;
  regressionRunning.value = true;
  regressionProgress.value = 3;
  regressionError.value = '';
  regressionResult.value = null;
  try {
    const created = await createResourceQualityRegressionTask({
      courseId: props.courseId,
      resourceType: resourceType.value || undefined,
      generationMode: generationMode.value || undefined,
      windowDays: windowDays.value,
      maxResources: 5,
    });
    regressionTaskId.value = created.task_id;
    const task = await waitForAgentTask(created.task_id, (snapshot) => {
      regressionProgress.value = snapshot.progress;
    });
    regressionResult.value = qualityRegressionResultFromTask(task);
    regressionProgress.value = 100;
    await loadRepairQueue();
  } catch (error) {
    regressionError.value = error instanceof Error ? error.message : '质量回归任务执行失败';
  } finally {
    regressionRunning.value = false;
  }
};

watch(
  () => [props.courseId, props.refreshToken, windowDays.value, resourceType.value, generationMode.value],
  () => {
    void loadMetrics();
    void loadRepairQueue();
  },
  { immediate: true },
);
</script>

<template>
  <section class="quality-dashboard" aria-labelledby="quality-dashboard-title">
    <header class="quality-header">
      <div>
        <span class="quality-eyebrow">QUALITY OBSERVATORY</span>
        <h2 id="quality-dashboard-title">资源质量监测</h2>
        <p>基于已落库质量快照观察生成稳定性，并对确定性基线回归发出告警。</p>
      </div>
      <div class="quality-filters" aria-label="质量指标筛选">
        <label>
          <span>时间窗口</span>
          <select v-model.number="windowDays">
            <option :value="7">近 7 天</option>
            <option :value="30">近 30 天</option>
            <option :value="90">近 90 天</option>
          </select>
        </label>
        <label>
          <span>资源类型</span>
          <select v-model="resourceType">
            <option value="">全部类型</option>
            <option v-for="(label, key) in typeLabels" :key="key" :value="key">{{ label }}</option>
          </select>
        </label>
        <label>
          <span>模型模式</span>
          <select v-model="generationMode">
            <option value="">全部模式</option>
            <option value="real_model">真实模型</option>
            <option value="deterministic_fallback">确定性回退</option>
            <option value="unknown">历史未记录</option>
          </select>
        </label>
      </div>
    </header>

    <p v-if="loading && !metrics" class="quality-state">正在聚合真实质量快照…</p>
    <p v-else-if="errorMessage" class="quality-state is-error">{{ errorMessage }}</p>
    <template v-else-if="metrics">
      <div class="quality-kpis">
        <article>
          <span>平均质量分</span>
          <strong>{{ displayScore(metrics.summary.average_score) }}</strong>
          <small>满分 100 · 当前筛选窗口</small>
        </article>
        <article>
          <span>门禁通过率</span>
          <strong>{{ percent(metrics.summary.gate_pass_rate) }}</strong>
          <small>发布阈值：总分 ≥ 75 且单维 ≥ 60</small>
        </article>
        <article>
          <span>有效评测资源</span>
          <strong>{{ metrics.summary.evaluated_resources }}</strong>
          <small>覆盖率 {{ percent(metrics.summary.evaluation_coverage) }}</small>
        </article>
        <article :class="{ 'has-alert': metrics.summary.alert_count }">
          <span>回归告警</span>
          <strong>{{ metrics.summary.alert_count }}</strong>
          <small>{{ metrics.summary.alert_count ? '存在需要关注的质量信号' : '当前未触发确定性告警' }}</small>
        </article>
      </div>

      <div v-if="hasData" class="quality-grid">
        <article class="quality-chart-card trend-card">
          <div class="card-heading">
            <div>
              <span>质量趋势</span>
              <h3>每日平均质量分</h3>
            </div>
            <strong v-if="latestTrend">最新 {{ displayScore(latestTrend.average_score) }}</strong>
          </div>
          <div class="trend-chart">
            <svg viewBox="0 0 720 164" role="img" aria-label="每日资源平均质量分折线图">
              <line v-for="value in [25, 50, 75, 100]" :key="value" x1="18" x2="702" :y1="18 + ((100 - value) / 100) * 128" :y2="18 + ((100 - value) / 100) * 128" />
              <path v-if="trendPath" :d="trendPath" />
              <circle v-for="point in trendPoints" :key="point.date" :cx="point.x" :cy="point.y" r="5">
                <title>{{ point.date }}：{{ displayScore(point.average_score) }} 分，{{ point.resource_count }} 个资源</title>
              </circle>
            </svg>
            <div class="trend-axis">
              <span>{{ trendPoints[0]?.date }}</span>
              <span>{{ latestTrend?.date }}</span>
            </div>
          </div>
        </article>

        <article class="quality-chart-card">
          <div class="card-heading">
            <div>
              <span>五维基线</span>
              <h3>质量维度均分</h3>
            </div>
          </div>
          <div class="dimension-list">
            <div v-for="item in metrics.dimensions" :key="item.dimension">
              <header><span>{{ item.label }}</span><strong>{{ displayScore(item.average_score) }}</strong></header>
              <i><b :style="{ width: `${Math.min(100, item.average_score)}%` }" /></i>
              <small>{{ item.resource_count }} 个样本 · 通过率 {{ percent(item.pass_rate) }}</small>
            </div>
          </div>
        </article>

        <article class="quality-chart-card">
          <div class="card-heading">
            <div>
              <span>资源类型</span>
              <h3>类型质量分布</h3>
            </div>
          </div>
          <div class="breakdown-list">
            <div v-for="item in metrics.by_resource_type" :key="item.key">
              <span>{{ displayType(item.key) }}</span>
              <i><b :style="{ width: `${Math.min(100, item.average_score)}%` }" /></i>
              <strong>{{ displayScore(item.average_score) }}</strong>
              <small>{{ item.resource_count }} 个</small>
            </div>
          </div>
        </article>

        <article class="quality-chart-card alert-card">
          <div class="card-heading">
            <div>
              <span>回归判定</span>
              <h3>确定性质量告警</h3>
            </div>
            <button
              v-if="metrics.alerts.length"
              class="regression-action"
              type="button"
              :disabled="regressionRunning"
              @click="runRegression"
            >
              {{ regressionRunning ? `回归中 ${regressionProgress}%` : '执行小批量回归' }}
            </button>
          </div>
          <div v-if="metrics.alerts.length" class="alert-list">
            <article v-for="alert in metrics.alerts" :key="`${alert.code}-${alert.dimension || 'all'}`" :class="`is-${alert.severity}`">
              <span>{{ alert.severity === 'critical' ? '高优先级' : '需关注' }}</span>
              <strong>{{ alert.title }}</strong>
              <p>{{ alert.message }}</p>
            </article>
          </div>
          <div v-else class="all-clear">
            <strong>当前基线稳定</strong>
            <p>覆盖率、门禁通过率、五维均分与前后窗口变化均未触发告警。</p>
          </div>
          <p v-if="regressionTaskId" class="regression-task-id">task_id: {{ regressionTaskId }}</p>
          <p v-if="regressionError" class="regression-error">{{ regressionError }}</p>
          <section v-if="regressionResult" class="regression-result" :class="{ 'needs-action': regressionResult.action_required }">
            <div>
              <span>已复评资源</span>
              <strong>{{ regressionResult.inspected_resources }}</strong>
            </div>
            <div>
              <span>稳定</span>
              <strong>{{ regressionResult.stable_count }}</strong>
            </div>
            <div>
              <span>发生回归</span>
              <strong>{{ regressionResult.regression_count }}</strong>
            </div>
            <p>
              <b v-if="regressionResult.automation_policy === 'audit_only'">只读审计 · </b>
              {{ regressionResult.policy_description }}
            </p>
          </section>
        </article>
      </div>

      <div v-else class="quality-empty">
        <strong>当前筛选条件下暂无有效质量快照</strong>
        <p>生成新资源后，这里会直接读取 Java 后端持久化的量化评测结果。</p>
      </div>

      <section class="repair-queue" aria-labelledby="repair-queue-title">
        <header class="repair-queue-heading">
          <div>
            <span class="quality-eyebrow">CONTROLLED REPAIR QUEUE</span>
            <h3 id="repair-queue-title">受控修复队列</h3>
            <p>回归失败只进入待确认状态；批准仅授权后续修复执行，本阶段不会改写原资源。</p>
          </div>
          <strong>{{ repairTotal }} 项</strong>
        </header>
        <p v-if="repairLoading && !repairs.length" class="repair-queue-state">正在读取待确认回归项…</p>
        <p v-if="repairError" class="repair-queue-state is-error">{{ repairError }}</p>
        <div v-if="repairs.length" class="repair-list">
          <article v-for="item in repairs" :key="item.repair_id" class="repair-item">
            <header>
              <div>
                <span>{{ displayType(item.resource_type) }} · {{ item.resource_id }}</span>
                <h4>{{ item.resource_title }}</h4>
              </div>
              <b :class="`is-${item.status}`">{{ repairStatusLabels[item.status] ?? item.status }}</b>
            </header>
            <div class="repair-signal">
              <span>分数变化 <strong>{{ displayScore(item.score_delta) }}</strong></span>
              <span>回归维度 {{ item.regressed_dimensions.join('、') || '综合门禁' }}</span>
              <span>任务 {{ item.source_task_id }}</span>
            </div>
            <template v-if="item.can_review">
              <textarea
                v-model="reviewNotes[item.repair_id]"
                maxlength="500"
                rows="2"
                placeholder="可填写批准说明；驳回时必须填写原因"
              />
              <div class="repair-actions">
                <button
                  type="button"
                  :disabled="decisionBusyId === item.repair_id"
                  @click="decideRepair(item, 'approve')"
                >
                  {{ decisionBusyId === item.repair_id ? '处理中…' : '批准进入受控修复' }}
                </button>
                <button
                  class="is-reject"
                  type="button"
                  :disabled="decisionBusyId === item.repair_id || !(reviewNotes[item.repair_id] || '').trim()"
                  @click="decideRepair(item, 'reject')"
                >
                  驳回
                </button>
              </div>
            </template>
            <div v-else-if="item.can_execute" class="repair-execution">
              <p>该修复项已批准，可生成一个隔离候选版本；执行过程不会覆盖当前资源。</p>
              <button
                type="button"
                :disabled="executionBusyId === item.repair_id"
                @click="executeRepair(item)"
              >
                {{ executionBusyId === item.repair_id
                  ? `定向修复中 ${executionProgress[item.repair_id] || 0}%`
                  : '启动定向修复' }}
              </button>
            </div>
            <div v-else-if="item.status === 'in_progress'" class="repair-execution is-running">
              <p>候选版本正在生成并接受 SafetyAgent 与五维质量门禁检查。</p>
              <strong>{{ executionProgress[item.repair_id] || 0 }}%</strong>
            </div>
            <template v-else-if="item.status === 'completed'">
              <div class="repair-candidate" :class="{ 'is-ready': item.publish_ready }">
                <div>
                  <span>候选质量分</span>
                  <strong>{{ displayScore(item.candidate_quality_evaluation?.total_score || 0) }}</strong>
                </div>
                <p>{{ item.publish_ready ? '已通过恢复判定，请先完成人工对比再发布。' : '候选版本未达到发布恢复条件，原资源保持不变。' }}</p>
                <button
                  v-if="item.can_compare"
                  type="button"
                  :disabled="comparisonBusyId === item.repair_id"
                  @click="toggleComparison(item)"
                >
                  {{ comparisonBusyId === item.repair_id
                    ? '读取中…'
                    : comparisonByRepair[item.repair_id] ? '收起对比' : '查看候选对比' }}
                </button>
              </div>
              <section v-if="comparisonByRepair[item.repair_id]" class="candidate-comparison">
                <header>
                  <div>
                    <span>发布前人工确认</span>
                    <strong>v{{ comparisonByRepair[item.repair_id].current_resource_version }} 原资源</strong>
                  </div>
                  <b :class="{ 'is-stale': comparisonByRepair[item.repair_id].stale }">
                    {{ comparisonByRepair[item.repair_id].stale ? '源版本已变化' : '版本校验通过' }}
                  </b>
                </header>
                <div class="comparison-grid">
                  <article>
                    <span>当前线上版本 · {{ displayScore(comparisonByRepair[item.repair_id].current_quality_evaluation.total_score) }} 分</span>
                    <h5>{{ comparisonByRepair[item.repair_id].original_resource.title }}</h5>
                    <p>{{ comparisonByRepair[item.repair_id].original_resource.summary }}</p>
                    <small>{{ contentPreview(comparisonByRepair[item.repair_id].original_resource.content) }}</small>
                  </article>
                  <article class="is-candidate">
                    <span>修复候选 · {{ displayScore(comparisonByRepair[item.repair_id].candidate_quality_evaluation.total_score) }} 分</span>
                    <h5>{{ comparisonByRepair[item.repair_id].candidate_resource.title }}</h5>
                    <p>{{ comparisonByRepair[item.repair_id].candidate_resource.summary }}</p>
                    <small>{{ contentPreview(comparisonByRepair[item.repair_id].candidate_resource.content) }}</small>
                  </article>
                </div>
                <p class="changed-fields">
                  变化字段：{{ comparisonByRepair[item.repair_id].changed_fields.join('、') || '仅质量与安全元数据' }}
                </p>
                <textarea
                  v-if="item.can_publish"
                  v-model="publicationNotes[item.repair_id]"
                  maxlength="500"
                  rows="2"
                  placeholder="填写发布确认说明（可选）"
                />
                <div v-if="item.can_publish" class="repair-actions">
                  <button
                    type="button"
                    :disabled="publicationBusyId === item.repair_id || !comparisonByRepair[item.repair_id].can_publish"
                    @click="publishRepair(item)"
                  >
                    {{ publicationBusyId === item.repair_id ? '原子发布中…' : '确认发布候选版本' }}
                  </button>
                </div>
              </section>
            </template>
            <div v-else-if="item.status === 'published'" class="repair-publication is-published">
              <div>
                <strong>候选版本已发布</strong>
                <p>资源 v{{ item.previous_resource_version }} → v{{ item.published_resource_version }}，原始快照已保留。</p>
              </div>
              <button
                v-if="item.can_rollback"
                type="button"
                :disabled="publicationBusyId === item.repair_id"
                @click="rollbackRepair(item)"
              >
                {{ publicationBusyId === item.repair_id ? '回滚中…' : '回滚本次发布' }}
              </button>
            </div>
            <div v-else-if="item.status === 'rolled_back'" class="repair-publication">
              <div>
                <strong>已安全回滚</strong>
                <p>发布版本 v{{ item.published_resource_version }} 已恢复为新审计版本 v{{ item.rollback_resource_version }}。</p>
              </div>
            </div>
            <p v-else-if="item.status === 'failed' && item.execution_error" class="repair-note is-error">
              执行失败：{{ item.execution_error }}
            </p>
            <p v-else-if="item.review_note" class="repair-note">审核备注：{{ item.review_note }}</p>
          </article>
        </div>
        <p v-else-if="!repairLoading && !repairError" class="repair-queue-state">当前课程暂无质量回归修复项。</p>
      </section>

      <footer class="quality-source">
        <span>数据源：{{ metrics.source.source_table }}</span>
        <span>口径：{{ metrics.source.grain }}</span>
        <span>最新快照：{{ formatFreshness(metrics.source.freshness_at) }}</span>
        <span v-if="metrics.by_generation_mode.length">
          模式：{{ metrics.by_generation_mode.map((item) => `${displayMode(item.key)} ${item.resource_count}`).join(' / ') }}
        </span>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.quality-dashboard {
  margin: 34px 0;
  border: 1px solid rgba(0, 121, 102, 0.14);
  border-radius: 22px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.92), rgba(243, 250, 247, 0.84));
  box-shadow: 0 22px 56px rgba(24, 68, 59, 0.1);
  padding: 30px;
}

.quality-header,
.quality-filters,
.card-heading,
.dimension-list header,
.quality-source {
  display: flex;
  align-items: center;
}

.repair-queue {
  margin-top: 18px;
  border: 1px solid rgba(0, 121, 102, 0.12);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  padding: 20px;
}

.repair-queue-heading,
.repair-item > header,
.repair-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.repair-queue-heading h3,
.repair-item h4 {
  margin: 4px 0;
}

.repair-queue-heading p,
.repair-note {
  color: rgba(31, 54, 49, 0.62);
  font-size: 12px;
  line-height: 1.55;
  margin: 0;
}

.repair-queue-heading > strong {
  flex: 0 0 auto;
  border-radius: 99px;
  background: rgba(0, 121, 102, 0.09);
  color: #087766;
  padding: 7px 11px;
}

.repair-list {
  display: grid;
  gap: 12px;
  margin-top: 16px;
}

.repair-item {
  display: grid;
  gap: 12px;
  border: 1px solid rgba(0, 121, 102, 0.1);
  border-radius: 13px;
  background: #fff;
  padding: 15px;
}

.repair-item header span,
.repair-signal,
.repair-queue-state {
  color: rgba(31, 54, 49, 0.58);
  font-size: 11px;
}

.repair-item header b {
  border-radius: 99px;
  background: rgba(0, 121, 102, 0.09);
  color: #087766;
  font-size: 11px;
  padding: 6px 10px;
}

.repair-item header b.is-pending_review {
  background: rgba(243, 163, 75, 0.14);
  color: #a85c1f;
}

.repair-item header b.is-rejected,
.repair-item header b.is-failed {
  background: rgba(216, 97, 78, 0.12);
  color: #bd4e3d;
}

.repair-signal {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
}

.repair-signal strong { color: #c55341; }

.repair-item textarea {
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  border: 1px solid rgba(0, 121, 102, 0.16);
  border-radius: 10px;
  color: #173f37;
  font: inherit;
  padding: 10px 11px;
}

.repair-actions {
  justify-content: flex-end;
}

.repair-actions button {
  min-height: 34px;
  border: 0;
  border-radius: 9px;
  background: #087766;
  color: #fff;
  cursor: pointer;
  font-size: 11px;
  font-weight: 800;
  padding: 0 12px;
}

.repair-execution,
.repair-candidate {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  border-radius: 10px;
  background: rgba(0, 121, 102, 0.06);
  padding: 11px 12px;
}

.repair-execution p,
.repair-candidate p {
  color: rgba(31, 54, 49, 0.62);
  font-size: 11px;
  line-height: 1.5;
  margin: 0;
}

.repair-execution button,
.repair-candidate button,
.repair-publication button {
  flex: 0 0 auto;
  min-height: 34px;
  border: 0;
  border-radius: 9px;
  background: #087766;
  color: #fff;
  cursor: pointer;
  font-size: 11px;
  font-weight: 800;
  padding: 0 12px;
}

.repair-execution button:disabled { cursor: wait; opacity: 0.65; }

.repair-candidate > div {
  display: grid;
  gap: 2px;
  min-width: 72px;
}

.repair-candidate span { color: rgba(31, 54, 49, 0.55); font-size: 10px; }
.repair-candidate strong { color: #a75a20; font-size: 20px; }
.repair-candidate.is-ready { background: rgba(0, 121, 102, 0.09); }
.repair-candidate.is-ready strong { color: #087766; }
.repair-note.is-error { color: #c94f3e; }

.candidate-comparison {
  display: grid;
  gap: 12px;
  border: 1px solid rgba(0, 121, 102, 0.14);
  border-radius: 12px;
  background: rgba(247, 252, 250, 0.92);
  padding: 14px;
}

.candidate-comparison > header,
.repair-publication {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.candidate-comparison > header > div { display: grid; gap: 3px; }
.candidate-comparison > header b { color: #087766; font-size: 11px; }
.candidate-comparison > header b.is-stale { color: #bd4e3d; }

.comparison-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.comparison-grid article {
  border: 1px solid rgba(31, 54, 49, 0.09);
  border-radius: 10px;
  background: #fff;
  padding: 12px;
}

.comparison-grid article.is-candidate { border-color: rgba(0, 121, 102, 0.22); }
.comparison-grid h5 { margin: 6px 0; font-size: 14px; }
.comparison-grid p,
.comparison-grid small,
.changed-fields,
.repair-publication p {
  color: rgba(31, 54, 49, 0.62);
  font-size: 11px;
  line-height: 1.55;
  margin: 0;
}
.comparison-grid small { display: block; margin-top: 8px; }
.changed-fields { color: #087766; }

.repair-publication {
  border-radius: 10px;
  background: rgba(55, 101, 156, 0.07);
  padding: 12px;
}
.repair-publication.is-published { background: rgba(0, 121, 102, 0.08); }
.repair-publication strong { color: #173f37; font-size: 13px; }
.repair-publication p { margin-top: 4px; }

.repair-actions button.is-reject {
  background: rgba(216, 97, 78, 0.1);
  color: #bd4e3d;
}

.repair-actions button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

@media (max-width: 720px) {
  .comparison-grid { grid-template-columns: 1fr; }
  .repair-candidate,
  .repair-publication { align-items: flex-start; flex-direction: column; }
}

.repair-queue-state {
  border-radius: 10px;
  background: rgba(0, 121, 102, 0.05);
  margin: 14px 0 0;
  padding: 12px;
}

.repair-queue-state.is-error { color: #c94f3e; }

.quality-header {
  justify-content: space-between;
  gap: 28px;
}

.quality-eyebrow,
.card-heading span {
  color: #087766;
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.12em;
}

.quality-header h2 {
  margin: 5px 0 7px;
  font-size: 28px;
}

.quality-header p,
.all-clear p,
.quality-empty p {
  color: rgba(31, 54, 49, 0.64);
  margin: 0;
  line-height: 1.6;
}

.quality-filters {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.quality-filters label {
  display: grid;
  gap: 5px;
}

.quality-filters label > span {
  color: rgba(31, 54, 49, 0.58);
  font-size: 11px;
  font-weight: 700;
}

.quality-filters select {
  min-height: 38px;
  border: 1px solid rgba(0, 121, 102, 0.16);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.88);
  color: #173f37;
  padding: 0 28px 0 11px;
}

.quality-kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin: 26px 0 16px;
}

.quality-kpis article,
.quality-chart-card {
  border: 1px solid rgba(0, 121, 102, 0.1);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.78);
}

.quality-kpis article {
  display: grid;
  gap: 7px;
  padding: 18px;
}

.quality-kpis article.has-alert {
  border-color: rgba(218, 114, 54, 0.32);
  background: rgba(255, 246, 237, 0.9);
}

.quality-kpis span,
.quality-kpis small,
.dimension-list small,
.breakdown-list small {
  color: rgba(31, 54, 49, 0.58);
}

.quality-kpis strong {
  color: #075f52;
  font-size: 30px;
}

.quality-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(300px, 0.85fr);
  gap: 16px;
}

.quality-chart-card {
  padding: 20px;
  min-width: 0;
}

.card-heading {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.card-heading h3 {
  font-size: 18px;
  margin: 4px 0 0;
}

.card-heading > strong {
  color: #087766;
}

.regression-action {
  min-height: 36px;
  border: 0;
  border-radius: 10px;
  background: #087766;
  color: #fff;
  cursor: pointer;
  font-size: 12px;
  font-weight: 800;
  padding: 0 14px;
}

.regression-action:disabled {
  cursor: wait;
  opacity: 0.68;
}

.trend-chart svg {
  width: 100%;
  overflow: visible;
}

.trend-chart line {
  stroke: rgba(31, 54, 49, 0.08);
  stroke-dasharray: 5 7;
}

.trend-chart path {
  fill: none;
  stroke: #078270;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 4;
}

.trend-chart circle {
  fill: #f3a34b;
  stroke: #fff;
  stroke-width: 3;
}

.trend-axis {
  display: flex;
  justify-content: space-between;
  color: rgba(31, 54, 49, 0.48);
  font-size: 11px;
}

.dimension-list,
.breakdown-list,
.alert-list {
  display: grid;
  gap: 14px;
}

.dimension-list header {
  justify-content: space-between;
  margin-bottom: 7px;
}

.dimension-list i,
.breakdown-list i {
  display: block;
  height: 7px;
  border-radius: 99px;
  overflow: hidden;
  background: rgba(0, 121, 102, 0.09);
}

.dimension-list b,
.breakdown-list b {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #078270, #42b99f);
}

.dimension-list small {
  display: block;
  margin-top: 5px;
}

.breakdown-list > div {
  display: grid;
  grid-template-columns: 100px minmax(80px, 1fr) 38px 42px;
  align-items: center;
  gap: 9px;
  font-size: 12px;
}

.alert-list article {
  display: grid;
  gap: 5px;
  border-left: 3px solid #df9a42;
  border-radius: 0 10px 10px 0;
  background: rgba(255, 245, 230, 0.72);
  padding: 12px 14px;
}

.alert-list article.is-critical {
  border-color: #d8614e;
  background: rgba(255, 237, 233, 0.76);
}

.alert-list span {
  color: #b76727;
  font-size: 11px;
  font-weight: 800;
}

.alert-list p {
  color: rgba(31, 54, 49, 0.64);
  font-size: 12px;
  line-height: 1.55;
  margin: 0;
}

.all-clear,
.quality-empty,
.quality-state {
  border-radius: 14px;
  background: rgba(0, 121, 102, 0.07);
  padding: 22px;
}

.all-clear strong,
.quality-empty strong {
  display: block;
  color: #087766;
  margin-bottom: 7px;
}

.regression-task-id,
.regression-error {
  margin: 12px 0 0;
  font-size: 11px;
}

.regression-task-id { color: rgba(31, 54, 49, 0.5); }
.regression-error { color: #c94f3e; }

.regression-result {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 14px;
  border: 1px solid rgba(0, 121, 102, 0.16);
  border-radius: 12px;
  background: rgba(0, 121, 102, 0.06);
  padding: 12px;
}

.regression-result.needs-action {
  border-color: rgba(216, 97, 78, 0.28);
  background: rgba(255, 237, 233, 0.72);
}

.regression-result div {
  display: grid;
  gap: 3px;
}

.regression-result span {
  color: rgba(31, 54, 49, 0.56);
  font-size: 10px;
}

.regression-result strong { font-size: 18px; }

.regression-result p {
  grid-column: 1 / -1;
  color: rgba(31, 54, 49, 0.62);
  font-size: 11px;
  line-height: 1.5;
  margin: 4px 0 0;
}

.quality-state.is-error {
  color: #c94f3e;
  background: rgba(255, 237, 233, 0.8);
}

.quality-source {
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-top: 16px;
  color: rgba(31, 54, 49, 0.52);
  font-size: 11px;
}

@media (max-width: 1050px) {
  .quality-header { align-items: flex-start; flex-direction: column; }
  .quality-filters { justify-content: flex-start; }
  .quality-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .quality-grid { grid-template-columns: 1fr; }
}

@media (max-width: 620px) {
  .quality-dashboard { padding: 20px; }
  .quality-kpis { grid-template-columns: 1fr; }
  .quality-filters label, .quality-filters select { width: 100%; }
}
</style>
