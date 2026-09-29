<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { cancelAgentTask, getAgentTask, listAgentTasks, normalizeTaskId, retryAgentTask, toAgentSteps } from '@/api/task';
import AgentWorkflowTimeline from '@/components/AgentWorkflowTimeline.vue';
import type { AgentTask, AgentTaskListItem, TaskStatus } from '@/types/api';

const props = withDefaults(defineProps<{ embedded?: boolean }>(), {
  embedded: false,
});

const filters: Array<{ value: '' | TaskStatus; label: string }> = [
  { value: '', label: '全部' },
  { value: 'running', label: '运行中' },
  { value: 'failed', label: '失败' },
  { value: 'success', label: '已完成' },
  { value: 'cancelled', label: '已取消' },
];

const statusLabels: Record<TaskStatus, string> = {
  pending: '等待执行',
  running: '运行中',
  success: '已完成',
  failed: '执行失败',
  cancelled: '已取消',
};

const domainLabels: Record<string, string> = {
  resource: '资源生成',
  profile: '学习画像',
  path: '学习路径',
  tutor: '智能辅导',
  quiz: '测验评估',
  kb_index: '知识库索引',
  resource_quality: '资源质量回归',
};

const selectedStatus = ref<'' | TaskStatus>('');
const tasks = ref<AgentTaskListItem[]>([]);
const total = ref(0);
const selectedTask = ref<AgentTask | null>(null);
const loading = ref(true);
const detailLoading = ref(false);
const operatingTaskId = ref('');
const errorMessage = ref('');
let refreshTimer: ReturnType<typeof window.setInterval> | undefined;

const learnerDomains = new Set(['resource', 'profile', 'path', 'tutor', 'quiz']);
const visibleTasks = computed(() => props.embedded
  ? tasks.value.filter((task) => learnerDomains.has(task.domain))
  : tasks.value);
const visibleTotal = computed(() => props.embedded ? visibleTasks.value.length : total.value);
const activeCount = computed(() => visibleTasks.value.filter((task) => task.status === 'pending' || task.status === 'running').length);
const failedCount = computed(() => visibleTasks.value.filter((task) => task.status === 'failed').length);
const selectedListItem = computed(() => visibleTasks.value.find((item) => item.task_id === selectedTask.value?.task_id));
const canRetrySelected = computed(() => Boolean(selectedListItem.value && canRetry(selectedListItem.value)));

const loadTasks = async (keepSelection = true) => {
  try {
    const result = await listAgentTasks({ status: selectedStatus.value || undefined, size: 30 });
    tasks.value = result.items;
    total.value = result.total;
    errorMessage.value = '';
    if (!keepSelection || !selectedTask.value) return;
    const selected = result.items.find((task) => task.task_id === selectedTask.value?.task_id);
    if (selected) await loadDetail(selected.task_id, false);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '任务列表加载失败';
  } finally {
    loading.value = false;
  }
};

const loadDetail = async (taskId: string, showLoading = true) => {
  if (showLoading) detailLoading.value = true;
  try {
    selectedTask.value = await getAgentTask(taskId);
    errorMessage.value = '';
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '任务详情加载失败';
  } finally {
    detailLoading.value = false;
  }
};

const applyFilter = async (status: '' | TaskStatus) => {
  selectedStatus.value = status;
  selectedTask.value = null;
  loading.value = true;
  await loadTasks(false);
};

const cancelTask = async (taskId: string) => {
  operatingTaskId.value = taskId;
  try {
    await cancelAgentTask(taskId);
    await Promise.all([loadTasks(false), loadDetail(taskId)]);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '取消任务失败';
  } finally {
    operatingTaskId.value = '';
  }
};

const retryTask = async (taskId: string) => {
  operatingTaskId.value = taskId;
  try {
    const created = await retryAgentTask(taskId);
    const newTaskId = normalizeTaskId(created);
    selectedStatus.value = '';
    await loadTasks(false);
    await loadDetail(newTaskId);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '重试任务失败';
  } finally {
    operatingTaskId.value = '';
  }
};

const canCancel = (status: TaskStatus) => status === 'pending' || status === 'running';
const canRetry = (task: AgentTaskListItem) => task.retryable && (task.status === 'failed' || task.status === 'cancelled');
const formatTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
const compactId = (taskId: string) => taskId.length > 28 ? `${taskId.slice(0, 18)}…${taskId.slice(-7)}` : taskId;

onMounted(async () => {
  await loadTasks(false);
  refreshTimer = window.setInterval(() => void loadTasks(true), 4000);
});

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer);
});
</script>

<template>
  <section class="task-center page-enter" :class="{ embedded: props.embedded }">
    <header class="task-center__hero">
      <div>
        <span class="task-center__eyebrow">AI GENERATION HISTORY</span>
        <h1>生成记录</h1>
        <p>查看画像、学习资源、路径与辅导内容的生成进度，随时回顾多智能体协作过程。</p>
      </div>
      <div class="task-center__summary">
        <article><strong>{{ visibleTotal }}</strong><span>生成记录</span></article>
        <article><strong>{{ activeCount }}</strong><span>生成中</span></article>
        <article><strong>{{ failedCount }}</strong><span>待重试</span></article>
      </div>
    </header>

    <div v-if="errorMessage" class="task-center__alert" role="alert">
      <span>{{ errorMessage }}</span>
      <button type="button" @click="loadTasks()">重新加载</button>
    </div>

    <div class="task-center__filters" aria-label="任务状态筛选">
      <button
        v-for="filter in filters"
        :key="filter.value || 'all'"
        type="button"
        :class="{ active: selectedStatus === filter.value }"
        :aria-pressed="selectedStatus === filter.value"
        @click="applyFilter(filter.value)"
      >{{ filter.label }}</button>
      <button class="task-center__refresh" type="button" :aria-busy="loading" @click="loadTasks()">刷新</button>
    </div>

    <div class="task-center__layout">
      <section class="task-center__list" aria-label="任务列表">
        <div v-if="loading" class="task-center__empty" role="status">正在读取任务记录…</div>
        <div v-else-if="!visibleTasks.length" class="task-center__empty">当前还没有生成记录，完成一次画像或资源生成后会显示在这里。</div>
        <template v-else>
          <button
            v-for="task in visibleTasks"
            :key="task.task_id"
            class="task-center__item"
            :class="[{ selected: selectedTask?.task_id === task.task_id }, task.status]"
            type="button"
            :aria-pressed="selectedTask?.task_id === task.task_id"
            @click="loadDetail(task.task_id)"
          >
            <span class="task-center__status-dot" aria-hidden="true"></span>
            <span class="task-center__item-copy">
              <span><strong>{{ domainLabels[task.domain] || task.domain }}</strong><em>{{ statusLabels[task.status] }}</em></span>
              <code :title="task.task_id">{{ compactId(task.task_id) }}</code>
              <small>{{ task.current_agent || task.operation_type }} · {{ formatTime(task.updated_at) }}</small>
            </span>
            <span
              class="task-center__progress"
              role="progressbar"
              :aria-label="`${domainLabels[task.domain] || task.domain}任务进度`"
              aria-valuemin="0"
              aria-valuemax="100"
              :aria-valuenow="task.progress"
            ><i :style="{ width: `${task.progress}%` }"></i></span>
          </button>
        </template>
      </section>

      <section class="task-center__detail">
        <div v-if="detailLoading" class="task-center__empty" role="status">正在读取执行轨迹…</div>
        <div v-else-if="!selectedTask" class="task-center__placeholder">
          <span>◎</span>
          <h2>选择一个任务查看执行轨迹</h2>
          <p>失败任务会保留原始错误与 Agent 步骤，重试将创建新的任务记录。</p>
        </div>
        <template v-else>
          <header class="task-center__detail-head">
            <div>
              <span :class="['task-center__badge', selectedTask.status]">{{ statusLabels[selectedTask.status] }}</span>
              <h2>{{ domainLabels[selectedListItem?.domain || ''] || '多智能体任务' }}</h2>
              <code>{{ selectedTask.task_id }}</code>
            </div>
            <div class="task-center__actions">
              <button
                v-if="canCancel(selectedTask.status)"
                type="button"
                :disabled="operatingTaskId === selectedTask.task_id"
                @click="cancelTask(selectedTask.task_id)"
              >取消任务</button>
              <button
                v-if="canRetrySelected"
                class="primary"
                type="button"
                :disabled="operatingTaskId === selectedTask.task_id"
                @click="retryTask(selectedTask.task_id)"
              >重新执行</button>
            </div>
          </header>

          <div v-if="selectedTask.error_message" class="task-center__failure">
            <strong>失败定位</strong>
            <p>{{ selectedTask.error_message }}</p>
            <small>原任务记录和既有业务结果不会被覆盖；重新执行会生成新的 task_id。</small>
          </div>

          <div class="task-center__meter">
            <span><strong>{{ selectedTask.progress }}%</strong> · {{ selectedTask.current_agent || statusLabels[selectedTask.status] }}</span>
            <i
              role="progressbar"
              aria-label="当前任务进度"
              aria-valuemin="0"
              aria-valuemax="100"
              :aria-valuenow="selectedTask.progress"
            ><b :style="{ width: `${selectedTask.progress}%` }"></b></i>
            <small>更新时间 {{ formatTime(selectedTask.updated_at) }}</small>
          </div>

          <AgentWorkflowTimeline :steps="toAgentSteps(selectedTask)" :task-id="selectedTask.task_id" />
        </template>
      </section>
    </div>
  </section>
</template>

<style scoped>
.task-center { min-height: calc(100vh - 74px); padding: 40px clamp(24px, 5vw, 76px) 64px; color: #133e3a; background: linear-gradient(150deg, #f7faf4 0%, #f4f8f1 48%, #fff8ed 100%); }
.task-center__hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 28px; max-width: 1480px; margin: 0 auto 28px; }
.task-center__eyebrow { color: #c97816; font-size: 12px; font-weight: 800; letter-spacing: .16em; text-transform: uppercase; }
.task-center h1 { margin: 8px 0; font-size: clamp(34px, 4vw, 52px); letter-spacing: -.04em; }
.task-center__hero p { max-width: 720px; margin: 0; color: #57716d; line-height: 1.75; }
.task-center__summary { display: flex; gap: 10px; }
.task-center__summary article { min-width: 92px; padding: 14px 18px; border: 1px solid rgba(18, 96, 86, .12); border-radius: 18px; background: rgba(255,255,255,.72); box-shadow: 0 12px 30px rgba(31,80,69,.07); }
.task-center__summary strong, .task-center__summary span { display: block; }
.task-center__summary strong { font-size: 24px; }
.task-center__summary span { margin-top: 3px; color: #718781; font-size: 12px; }
.task-center__alert { display: flex; justify-content: space-between; max-width: 1480px; margin: 0 auto 16px; padding: 13px 16px; border: 1px solid #f1b9a7; border-radius: 14px; color: #8b3f2d; background: #fff3ed; }
.task-center__alert button { border: 0; color: inherit; font-weight: 700; background: transparent; cursor: pointer; }
.task-center__filters { display: flex; gap: 8px; max-width: 1480px; margin: 0 auto 16px; }
.task-center__filters button, .task-center__actions button { min-height: 44px; padding: 9px 16px; border: 1px solid rgba(20,92,81,.16); border-radius: 999px; color: #42625d; background: rgba(255,255,255,.72); cursor: pointer; }
.task-center__filters button.active { color: white; border-color: #0f6b60; background: #0f6b60; }
.task-center__filters .task-center__refresh { margin-left: auto; }
.task-center__layout { display: grid; grid-template-columns: minmax(300px, 390px) minmax(0, 1fr); gap: 18px; max-width: 1480px; margin: 0 auto; }
.task-center__layout > * { min-width: 0; }
.task-center__list, .task-center__detail { min-height: 620px; border: 1px solid rgba(22,86,77,.12); border-radius: 24px; background: rgba(255,255,255,.78); box-shadow: 0 22px 54px rgba(31,80,69,.08); backdrop-filter: blur(16px); }
.task-center__list { max-height: 720px; padding: 12px; overflow: auto; }
.task-center__item { position: relative; display: grid; grid-template-columns: 12px 1fr; gap: 12px; width: 100%; margin-bottom: 8px; padding: 16px; overflow: hidden; text-align: left; border: 1px solid transparent; border-radius: 17px; color: inherit; background: transparent; cursor: pointer; }
.task-center__item:hover, .task-center__item.selected { border-color: rgba(15,107,96,.2); background: #f4faf7; }
.task-center__status-dot { width: 9px; height: 9px; margin-top: 6px; border-radius: 50%; background: #9aa9a5; box-shadow: 0 0 0 4px rgba(154,169,165,.13); }
.task-center__item.running .task-center__status-dot { background: #d88720; box-shadow: 0 0 0 4px rgba(216,135,32,.14); animation: task-pulse 1.5s infinite; }
.task-center__item.success .task-center__status-dot { background: #168375; }
.task-center__item.failed .task-center__status-dot { background: #c9583d; }
.task-center__item-copy > span { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.task-center__item-copy em { color: #718781; font-size: 12px; font-style: normal; }
.task-center__item-copy code, .task-center__detail-head code { display: block; margin: 7px 0 5px; color: #7b8e89; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px; word-break: break-all; }
.task-center__item-copy small { color: #84948f; }
.task-center__progress { position: absolute; right: 16px; bottom: 8px; left: 40px; height: 3px; overflow: hidden; border-radius: 5px; background: #e6eeea; }
.task-center__progress i { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #188c7c, #e49325); }
.task-center__detail { padding: clamp(20px, 3vw, 34px); overflow: auto; }
.task-center__detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.task-center__detail-head h2 { margin: 10px 0 0; font-size: 28px; }
.task-center__badge { display: inline-flex; padding: 6px 10px; border-radius: 99px; color: #536d67; background: #e9f0ed; font-size: 12px; font-weight: 800; }
.task-center__badge.running { color: #9a5a0d; background: #fff0d8; }
.task-center__badge.success { color: #0e6b5e; background: #dcf2ea; }
.task-center__badge.failed { color: #9b3d2d; background: #fde6df; }
.task-center__actions { display: flex; gap: 8px; }
.task-center__actions button.primary { color: white; border-color: #0f6b60; background: #0f6b60; }
.task-center__actions button:disabled { opacity: .5; cursor: wait; }
.task-center__failure { margin: 16px 0; padding: 16px 18px; border-left: 4px solid #cf5b40; border-radius: 12px; color: #713c31; background: #fff1eb; }
.task-center__failure p { margin: 7px 0; line-height: 1.6; word-break: break-word; }
.task-center__failure small { color: #986b60; }
.task-center__meter { display: grid; grid-template-columns: auto minmax(100px, 1fr) auto; align-items: center; gap: 14px; margin: 18px 0 24px; color: #58716c; font-size: 13px; }
.task-center__meter > i { height: 7px; overflow: hidden; border-radius: 8px; background: #e6efeb; }
.task-center__meter b { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #168374, #dda039); transition: width .35s ease; }
.task-center__empty, .task-center__placeholder { display: grid; min-height: 360px; place-content: center; text-align: center; color: #758984; }
.task-center__placeholder span { font-size: 46px; color: #d38a2c; }
.task-center__placeholder h2 { margin: 10px 0 6px; color: #315a54; }
.task-center__placeholder p { max-width: 420px; line-height: 1.7; }
@keyframes task-pulse { 50% { transform: scale(1.25); opacity: .6; } }
.task-center.embedded { min-height: 0; padding: 0; background: transparent; }
.task-center.embedded .task-center__hero,
.task-center.embedded .task-center__filters,
.task-center.embedded .task-center__layout { max-width: none; }
.task-center.embedded .task-center__hero { align-items: center; margin-bottom: 20px; }
.task-center.embedded .task-center__hero h1 { font-size: clamp(28px, 3vw, 38px); }
.task-center.embedded .task-center__hero p { max-width: 610px; }
.task-center.embedded .task-center__summary article { min-width: 84px; padding: 12px 14px; background: rgba(247, 252, 249, .9); }
.task-center.embedded .task-center__layout { grid-template-columns: minmax(270px, 340px) minmax(0, 1fr); }
.task-center.embedded .task-center__list,
.task-center.embedded .task-center__detail { min-height: 480px; border-radius: 20px; box-shadow: 0 14px 38px rgba(31,80,69,.06); }
.task-center.embedded .task-center__list { max-height: 580px; }
@media (max-width: 980px) { .task-center__hero { align-items: flex-start; flex-direction: column; } .task-center__layout { grid-template-columns: 1fr; } .task-center__list { min-height: 0; max-height: 390px; } .task-center__detail { min-height: 480px; } }
@media (max-width: 640px) { .task-center { padding: 26px 16px 48px; } .task-center__summary { width: 100%; overflow-x: auto; } .task-center__summary article { flex: 1 0 96px; } .task-center__filters { overflow-x: auto; padding-bottom: 6px; scrollbar-width: thin; } .task-center__filters button { flex: 0 0 auto; } .task-center__filters .task-center__refresh { margin-left: 8px; } .task-center__detail-head { flex-direction: column; } .task-center__actions { width: 100%; flex-wrap: wrap; } .task-center__meter { grid-template-columns: 1fr; } }
</style>
