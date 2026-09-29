<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { analyzeProfile, getCurrentProfile } from '../api/profile';
import { waitForAgentTask } from '../api/task';
import { track } from '../research';
import type { PageKey, Profile } from '../types';
import type { ModelRuntime, ResourceSafety } from '../types/api';

const props = defineProps<{
  initialIntent?: { text: string; nonce: number } | null;
}>();

const emit = defineEmits<{
  initialIntentConsumed: [];
  navigate: [page: PageKey];
}>();

type MessageRole = 'agent' | 'student';
type InsightTone = 'normal' | 'weak' | 'resource' | 'data';
type AnalysisTone = 'version' | 'weak' | 'cognitive' | 'pace' | 'model';

interface ProfileMessage {
  id: string;
  role: MessageRole;
  name: string;
  content: string;
}

interface AnalysisChip {
  title: string;
  value: string;
  tone: AnalysisTone;
  active: boolean;
}

interface ProfileDimension {
  label: string;
  value: string;
  tone: InsightTone;
  source: string;
  progress?: number;
}

interface ProfileTaskResult {
  generation_mode?: ModelRuntime['mode'];
  model_runtime?: ModelRuntime;
  safety?: ResourceSafety;
}

const messages = ref<ProfileMessage[]>([]);

const draft = ref('');
const isThinking = ref(false);
const streamedReply = ref('');
const activeChipIndex = ref(3);
const conversationRef = ref<HTMLElement | null>(null);
const currentProfile = ref<Profile | null>(null);
const activeTaskId = ref('');
const taskProgress = ref(0);
const errorMessage = ref('');
const profileVersion = ref(0);
const modelRuntime = ref<ModelRuntime | null>(null);
const safetyReview = ref<ResourceSafety | null>(null);

let chipTimer: number | undefined;

const modelRuntimeSummary = computed(() => {
  const runtime = modelRuntime.value;
  if (!runtime) return '等待本轮画像任务';
  if (runtime.mode === 'real_model') {
    return `${runtime.model || runtime.provider || '真实模型'} · ${runtime.call_count ?? 0} 次调用`;
  }
  return '确定性开发模式';
});

const analysisChips = computed<AnalysisChip[]>(() => {
  const profile = currentProfile.value;
  const chips: Array<Omit<AnalysisChip, 'active'>> = [
    { title: '画像版本', value: profileVersion.value ? `v${profileVersion.value}` : '待同步', tone: 'version' },
    { title: '薄弱点', value: profile?.weakPoints[0] ?? '暂无薄弱点', tone: 'weak' },
    { title: '认知风格', value: profile?.cognitiveStyle.join('、') || '待补充', tone: 'cognitive' },
    { title: '学习节奏', value: profile?.learningPace ?? '待补充', tone: 'pace' },
    { title: '模型运行', value: modelRuntimeSummary.value, tone: 'model' },
  ];

  return chips.map((chip, index) => ({
    ...chip,
    active: index <= activeChipIndex.value,
  }));
});

const profileDimensions = computed<ProfileDimension[]>(() => {
  const profile = currentProfile.value;
  const confidence = Math.round((profile?.confidenceScore ?? 0) * 100);
  return [
    { label: '学习目标', value: profile?.learningGoal ?? '等待画像加载', tone: 'normal', source: '学生自述' },
    { label: '知识基础', value: profile?.cognitiveStyle.join('、') || '等待认知风格分析', tone: 'data', source: '画像分析' },
    { label: '薄弱点', value: profile?.weakPoints.join('、') || '等待薄弱点聚类', tone: 'weak', source: '测验与行为' },
    { label: '学习偏好', value: profile?.resourcePreference.join('、') || '等待偏好识别', tone: 'resource', source: '学生自述' },
    { label: '学习节奏', value: profile?.learningPace ?? 'medium', tone: 'data', source: '学习行为' },
    { label: '资源偏好', value: profile?.resourcePreference.join('、') || '等待资源偏好', tone: 'resource', source: '学生自述' },
    { label: '掌握程度', value: `${confidence}%`, tone: 'normal', source: '测验结果', progress: confidence },
    { label: '模型运行证据', value: modelRuntimeSummary.value, tone: 'resource', source: '任务运行记录' },
    { label: '最近意图', value: activeTaskId.value || '尚未提交新画像任务', tone: 'data', source: '当前会话' },
  ];
});

const weakTags = computed(() => currentProfile.value?.weakPoints.length ? currentProfile.value.weakPoints : ['等待画像分析']);
const preferenceTags = computed(() =>
  currentProfile.value?.resourcePreference.length ? currentProfile.value.resourcePreference : ['等待资源偏好'],
);

const scrollToBottom = async () => {
  await nextTick();
  if (conversationRef.value) {
    conversationRef.value.scrollTop = conversationRef.value.scrollHeight;
  }
};

const clearTimers = () => {
  if (chipTimer) {
    window.clearInterval(chipTimer);
    chipTimer = undefined;
  }
};

const animateAnalysis = () => {
  activeChipIndex.value = -1;
  chipTimer = window.setInterval(() => {
    activeChipIndex.value += 1;
    if (activeChipIndex.value >= analysisChips.value.length - 1) {
      window.clearInterval(chipTimer);
      chipTimer = undefined;
    }
  }, 220);
};

const ensureIntroMessage = () => {
  if (messages.value.length || !currentProfile.value) return;
  const profile = currentProfile.value;
  const weak = profile.weakPoints.join('、') || '暂无明确薄弱点';
  const preference = profile.resourcePreference.join('、') || '暂无资源偏好';
  messages.value = [
    {
      id: 'agent-current-profile',
      role: 'agent',
      name: 'ProfileAgent',
      content: `已读取你的最新学习画像：当前目标是“${profile.learningGoal}”，薄弱点为“${weak}”，资源偏好为“${preference}”。你可以继续补充学习目标、困惑或材料偏好。`,
    },
  ];
};

const completeReply = (reply: string) => {
  messages.value.push({
    id: `agent-${Date.now()}`,
    role: 'agent',
    name: 'ProfileAgent',
    content: reply,
  });
  streamedReply.value = '';
  isThinking.value = true;
  isThinking.value = false;
  activeChipIndex.value = analysisChips.value.length - 1;
  void scrollToBottom();
};

const appendStudentMessage = (text: string) => {
  messages.value.push({
    id: `student-${Date.now()}`,
    role: 'student',
    name: '你',
    content: text,
  });
};

const buildReply = (text: string) => {
  const profile = currentProfile.value;
  const weak = profile?.weakPoints.join('、') || '暂无薄弱点';
  const preference = profile?.resourcePreference.join('、') || '暂无资源偏好';
  const review = safetyReview.value?.passed ? 'SafetyAgent 已独立复核通过' : '等待 SafetyAgent 复核记录';
  return `收到。ProfileAgent 已基于任务 ${activeTaskId.value} 处理“${text}”，${modelRuntimeSummary.value}，${review}。当前画像薄弱点：${weak}；资源偏好：${preference}。`;
};

const runProfileTask = async (text: string) => {
  const hadProfile = Boolean(currentProfile.value);
  clearTimers();
  appendStudentMessage(text);
  draft.value = '';
  isThinking.value = true;
  streamedReply.value = '正在提交 ProfileAgent 画像任务...';
  errorMessage.value = '';
  taskProgress.value = 3;
  animateAnalysis();
  void scrollToBottom();

  try {
    const created = await analyzeProfile({ message: text, courseIds: [1, 2] });
    activeTaskId.value = created.taskId;
    const completedTask = await waitForAgentTask(created.taskId, (task) => {
      taskProgress.value = task.progress;
      streamedReply.value = `${task.current_agent || 'ProfileAgent'} 正在更新画像... ${task.progress}%`;
      void scrollToBottom();
    });
    const taskResult = (completedTask.result || {}) as ProfileTaskResult;
    modelRuntime.value = taskResult.model_runtime ?? null;
    safetyReview.value = taskResult.safety ?? null;
    taskProgress.value = 100;
    const result = await getCurrentProfile();
    currentProfile.value = result.profile;
    profileVersion.value = result.version;
    track(hadProfile ? 'profile_corrected' : 'profile_confirmed', {
      page: 'profile-chat',
      taskRef: activeTaskId.value || null,
      metadata: {
        profile_version: result.version,
        statement_length: text.length,
        changed_axis: hadProfile ? 'user_statement' : 'initial_profile',
      },
    });
    completeReply(buildReply(text));
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '画像分析任务失败';
    streamedReply.value = errorMessage.value;
    isThinking.value = false;
  }
};

const sendMessage = () => {
  const text = draft.value.trim();
  if (!text || isThinking.value) return;

  void runProfileTask(text);
};

const receiveInitialIntent = (text: string) => {
  const cleanText = text.trim();
  if (!cleanText) return;

  void runProfileTask(cleanText);
};

const openLearningHistory = () => {
  emit('navigate', 'userCenter');
};

watch(
  () => props.initialIntent,
  (intent) => {
    if (!intent?.text) return;
    receiveInitialIntent(intent.text);
    emit('initialIntentConsumed');
  },
  { immediate: true },
);

onBeforeUnmount(clearTimers);

onMounted(async () => {
  try {
    const result = await getCurrentProfile();
    currentProfile.value = result.profile;
    profileVersion.value = result.version;
    ensureIntroMessage();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '画像加载失败';
  }
});
</script>

<template>
  <div class="profile-chat-page ai-page-bg page-enter">
    <section class="profile-chat-layout">
      <div class="profile-chat-primary">
        <aside class="profile-chat-panel profile-chat-panel--dialogue">
        <header class="profile-chat-heading">
          <span class="profile-heading-icon profile-heading-icon--robot" aria-hidden="true">
            <svg viewBox="0 0 48 48">
              <rect x="10" y="15" width="28" height="23" rx="8" />
              <path d="M24 15v-6" />
              <circle cx="24" cy="7" r="2.5" />
              <circle cx="19" cy="26" r="2.5" />
              <circle cx="29" cy="26" r="2.5" />
              <path d="M18 33h12" />
            </svg>
          </span>
          <h1>AI 对话</h1>
        </header>

        <div ref="conversationRef" class="profile-chat-stream" aria-label="画像对话记录">
          <article
            v-for="message in messages"
            :key="message.id"
            class="profile-chat-message"
            :class="`is-${message.role}`"
          >
            <div class="profile-message-avatar" :class="message.role === 'agent' ? 'is-robot' : 'is-person'">
              <svg v-if="message.role === 'agent'" viewBox="0 0 48 48" aria-hidden="true">
                <rect x="10" y="15" width="28" height="23" rx="8" />
                <path d="M24 15v-6" />
                <circle cx="24" cy="7" r="2.5" />
                <circle cx="19" cy="26" r="2.5" />
                <circle cx="29" cy="26" r="2.5" />
                <path d="M18 33h12" />
              </svg>
              <svg v-else viewBox="0 0 48 48" aria-hidden="true">
                <circle cx="24" cy="12" r="6" />
                <path d="M24 19v14" />
                <path d="M14 25h20" />
                <path d="M24 33l-9 9" />
                <path d="M24 33l9 9" />
              </svg>
            </div>
            <div class="profile-chat-bubble">
              <span>{{ message.name }}</span>
              <p>{{ message.content }}</p>
            </div>
          </article>

          <article v-if="isThinking" class="profile-chat-message is-agent">
            <div class="profile-message-avatar is-robot">
              <svg viewBox="0 0 48 48" aria-hidden="true">
                <rect x="10" y="15" width="28" height="23" rx="8" />
                <path d="M24 15v-6" />
                <circle cx="24" cy="7" r="2.5" />
                <circle cx="19" cy="26" r="2.5" />
                <circle cx="29" cy="26" r="2.5" />
                <path d="M18 33h12" />
              </svg>
            </div>
            <div class="profile-chat-bubble profile-chat-bubble--stream">
              <span>ProfileAgent</span>
              <p>{{ streamedReply || '正在分析学习意图与画像维度...' }}</p>
              <details v-if="activeTaskId" class="profile-task-details">
                <summary>查看任务技术信息 · {{ taskProgress }}%</summary>
                <code>{{ activeTaskId }}</code>
              </details>
            </div>
          </article>
        </div>

        <form class="profile-chat-composer" @submit.prevent="sendMessage">
          <button class="profile-chat-attach hover-scale" type="button" aria-label="添加学习材料">⌁</button>
          <input v-model="draft" type="text" placeholder="输入学习反馈..." />
          <button class="profile-chat-send hover-scale" type="submit" :disabled="isThinking" aria-label="发送">
            ↑
          </button>
        </form>
        </aside>

        <aside class="profile-archive-panel">
        <header class="profile-archive-title">
          <div class="profile-archive-main-title">
            <span class="profile-heading-icon profile-heading-icon--postcard" aria-hidden="true">
              <svg viewBox="0 0 48 48">
                <rect x="8" y="12" width="32" height="24" rx="4" />
                <path d="M27 12v24" />
                <path d="M13 19h9" />
                <path d="M13 26h7" />
                <circle cx="33" cy="21" r="3.5" />
              </svg>
            </span>
            <h2>学习画像档案</h2>
          </div>
          <div class="profile-archive-actions" aria-label="画像辅助入口">
            <span>画像随学随新</span>
            <button class="hover-scale" type="button" @click="openLearningHistory">历史日志</button>
          </div>
        </header>

        <section class="profile-dimension-grid">
          <article
            v-for="dimension in profileDimensions"
            :key="dimension.label"
            class="profile-dimension-card interactive-card"
            :class="`is-${dimension.tone}`"
          >
            <span>{{ dimension.label }}</span>
            <div class="profile-dimension-meta">
              <strong>{{ dimension.value }}</strong>
              <small>{{ dimension.source }}</small>
            </div>
            <div v-if="dimension.progress" class="profile-progress">
              <i :style="{ width: `${dimension.progress}%` }"></i>
            </div>
          </article>
        </section>

        <section class="profile-cluster-card interactive-card">
          <h3>薄弱点集群</h3>
          <div class="profile-tag-row">
            <span v-for="tag in weakTags" :key="tag" class="is-weak">{{ tag }}</span>
          </div>
        </section>

        <section class="profile-cluster-card interactive-card">
          <h3>资源偏好</h3>
          <div class="profile-tag-row">
            <span v-for="tag in preferenceTags" :key="tag" class="is-resource">{{ tag }}</span>
          </div>
        </section>

        <section v-if="errorMessage" class="profile-cluster-card interactive-card">
          <h3>任务状态</h3>
          <p>{{ errorMessage }}</p>
        </section>

        <section class="profile-confidence-card interactive-card">
          <div>
            <span>学习置信度</span>
            <strong>{{ Math.round((currentProfile?.confidenceScore ?? 0) * 100) }}%</strong>
          </div>
          <div class="profile-progress">
            <i :style="{ width: `${Math.round((currentProfile?.confidenceScore ?? 0) * 100)}%` }"></i>
          </div>
          <p>{{ activeTaskId ? '已基于最新任务刷新' : '等待新的画像任务' }}</p>
        </section>
        </aside>
      </div>

      <section class="profile-analysis-area">
        <header class="profile-chat-heading">
          <span class="profile-heading-icon profile-heading-icon--function" aria-hidden="true">
            <svg viewBox="0 0 48 48">
              <path d="M14 34V13" />
              <path d="M14 34h23" />
              <path d="M14 34c5-9 9-11 14-10 5 1 6-8 12-12" />
              <circle cx="14" cy="34" r="3" />
              <circle cx="28" cy="24" r="3" />
              <circle cx="40" cy="12" r="3" />
            </svg>
          </span>
          <h2>动态分析</h2>
        </header>

        <div class="profile-analysis-flow" aria-label="动态画像分析过程">
          <article
            v-for="(chip, index) in analysisChips"
            :key="chip.title"
            class="profile-analysis-chip profile-chip-float"
            :class="[`is-${chip.tone}`, { active: chip.active }]"
            :style="{ animationDelay: `${index * 180}ms` }"
          >
            <span>{{ chip.title }}</span>
            <strong>{{ chip.value }}</strong>
          </article>
        </div>
      </section>
    </section>
  </div>
</template>

<style scoped>
.profile-chat-page {
  padding: clamp(26px, 4vw, 48px) clamp(20px, 3.6vw, 46px) 54px;
}

.profile-chat-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: clamp(28px, 3.2vw, 48px);
  max-width: 1500px;
  margin: 0 auto;
}

.profile-chat-primary {
  display: grid;
  grid-template-columns: minmax(340px, 2fr) minmax(520px, 3fr);
  gap: clamp(24px, 3.2vw, 46px);
  height: clamp(700px, 80vh, 860px);
  align-items: stretch;
}

.profile-chat-panel,
.profile-analysis-area,
.profile-archive-panel {
  min-width: 0;
}

.profile-chat-panel--dialogue {
  position: relative;
  display: grid;
  grid-template-rows: auto 1fr auto;
  gap: 24px;
  height: 100%;
  min-height: 0;
  border: 1px solid rgba(18, 128, 111, 0.42);
  border-radius: 34px;
  padding: clamp(20px, 2.6vw, 30px);
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.84), rgba(246, 252, 248, 0.74)),
    radial-gradient(circle at 12% 12%, rgba(18, 128, 111, 0.09), transparent 30%);
  box-shadow: 0 30px 70px rgba(42, 77, 69, 0.1);
  overflow: hidden;
}

.profile-chat-panel--dialogue::before,
.profile-chat-panel--dialogue::after {
  content: "";
  position: absolute;
  width: 126px;
  height: 126px;
  border-color: rgba(18, 128, 111, 0.58);
  pointer-events: none;
}

.profile-chat-panel--dialogue::before {
  top: -1px;
  left: -1px;
  border-top: 3px solid;
  border-left: 3px solid;
  border-top-left-radius: 34px;
}

.profile-chat-panel--dialogue::after {
  right: -1px;
  bottom: -1px;
  border-right: 3px solid;
  border-bottom: 3px solid;
  border-bottom-right-radius: 34px;
}

.profile-chat-heading,
.profile-archive-main-title {
  display: flex;
  align-items: center;
  gap: 14px;
}

.profile-chat-heading h1,
.profile-chat-heading h2,
.profile-archive-main-title h2 {
  margin: 0;
  color: var(--ink);
  font-size: clamp(1.75rem, 2.4vw, 2.35rem);
  font-weight: 900;
}

.profile-heading-icon {
  display: grid;
  width: 50px;
  height: 50px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: rgba(18, 128, 111, 0.12);
  color: var(--teal);
}

.profile-heading-icon svg,
.profile-message-avatar svg {
  width: 34px;
  height: 34px;
  overflow: visible;
}

.profile-heading-icon svg *,
.profile-message-avatar svg * {
  fill: none;
  stroke: currentColor;
  stroke-width: 3;
  stroke-linecap: round;
  stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
}

.profile-heading-icon circle,
.profile-message-avatar circle {
  fill: currentColor;
}

.profile-heading-icon--robot,
.profile-message-avatar.is-robot {
  background: rgba(18, 128, 111, 0.12);
  color: var(--teal);
}

.profile-heading-icon--function {
  background: rgba(18, 128, 111, 0.1);
  color: var(--teal-dark);
}

.profile-heading-icon--postcard {
  background: rgba(18, 128, 111, 0.08);
  color: var(--ink);
}

.profile-chat-stream {
  display: grid;
  align-content: start;
  gap: 24px;
  overflow: auto;
  padding: 6px 2px 4px;
}

.profile-chat-message {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.profile-chat-message.is-student {
  flex-direction: row-reverse;
}

.profile-message-avatar {
  display: grid;
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  place-items: center;
  border-radius: 50%;
  box-shadow: 0 10px 22px rgba(42, 77, 69, 0.08);
}

.profile-message-avatar.is-person {
  background: rgba(226, 132, 0, 0.12);
  color: #d67508;
}

.profile-message-avatar.is-person svg {
  width: 30px;
  height: 30px;
}

.profile-chat-bubble {
  display: grid;
  gap: 10px;
  max-width: min(86%, 420px);
  min-width: 0;
  border: 1px solid rgba(25, 58, 50, 0.1);
  border-radius: 22px;
  padding: 18px 20px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 16px 34px rgba(46, 77, 68, 0.06);
}

.profile-chat-message.is-student .profile-chat-bubble {
  background:
    linear-gradient(145deg, rgba(246, 176, 90, 0.12), rgba(255, 255, 255, 0.78)),
    rgba(255, 255, 255, 0.78);
}

.profile-chat-bubble span {
  color: var(--teal);
  font-size: 0.82rem;
  font-weight: 900;
}

.profile-chat-message.is-student .profile-chat-bubble span {
  color: var(--ink);
  text-align: right;
}

.profile-chat-bubble p {
  margin: 0;
  color: #203a34;
  font-size: 1rem;
  line-height: 1.9;
  overflow-wrap: anywhere;
}

.profile-chat-bubble small {
  color: rgba(32, 58, 52, 0.58);
  font-size: 0.76rem;
  font-weight: 800;
  overflow-wrap: anywhere;
}

.profile-chat-bubble--stream {
  border-color: rgba(18, 128, 111, 0.24);
}

.profile-chat-composer {
  display: grid;
  grid-template-columns: 46px minmax(0, 1fr) 48px;
  align-items: center;
  gap: 12px;
  border: 1px solid rgba(26, 67, 58, 0.12);
  border-radius: 999px;
  padding: 10px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: 0 18px 42px rgba(40, 77, 68, 0.1);
}

.profile-chat-composer input {
  width: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
}

.profile-chat-composer input::placeholder {
  color: rgba(27, 52, 47, 0.45);
}

.profile-chat-attach,
.profile-chat-send {
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
  font: inherit;
}

.profile-chat-attach {
  width: 42px;
  height: 42px;
  background: rgba(18, 128, 111, 0.08);
  color: var(--teal);
  font-size: 1.35rem;
  font-weight: 900;
}

.profile-chat-send {
  width: 48px;
  height: 48px;
  background: linear-gradient(135deg, #0f8a79, #006f62);
  color: #fff;
  font-size: 1.5rem;
  box-shadow: 0 14px 24px rgba(8, 112, 98, 0.24);
}

.profile-chat-send:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.profile-analysis-area {
  position: relative;
  display: grid;
  align-content: start;
  gap: 24px;
  border-top: 1px solid rgba(35, 58, 53, 0.08);
  padding: 30px 0 4px;
}
.profile-task-details { margin-top: 6px; color: rgba(32, 58, 52, 0.58); font-size: .76rem; }
.profile-task-details summary { cursor: pointer; font-weight: 800; }
.profile-task-details code { display:block; margin-top:6px; overflow-wrap:anywhere; }

.profile-analysis-flow {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: clamp(14px, 1.8vw, 24px);
  align-items: stretch;
}

.profile-analysis-chip {
  display: grid;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
  border: 1px solid rgba(35, 58, 53, 0.12);
  border-radius: 999px;
  padding: 18px clamp(14px, 1.8vw, 24px);
  background: rgba(255, 255, 255, 0.74);
  box-shadow: 0 20px 46px rgba(48, 78, 70, 0.08);
  text-align: center;
  opacity: 0.42;
  filter: saturate(0.8);
}

.profile-analysis-chip.active {
  opacity: 1;
  filter: saturate(1);
}

.profile-analysis-chip span {
  color: rgba(31, 55, 50, 0.56);
  font-size: 0.9rem;
}

.profile-analysis-chip strong {
  color: var(--ink);
  font-size: 1.18rem;
  overflow-wrap: anywhere;
}

.profile-analysis-chip.is-weak {
  border-color: rgba(235, 104, 76, 0.26);
  background: rgba(255, 245, 240, 0.86);
}

.profile-analysis-chip.is-weak strong {
  color: #e4573e;
}

.profile-analysis-chip.is-version {
  border-color: rgba(211, 164, 48, 0.28);
  background: rgba(255, 246, 215, 0.92);
}

.profile-analysis-chip.is-version strong {
  color: #826017;
}

.profile-analysis-chip.is-cognitive {
  border-color: rgba(65, 174, 164, 0.25);
  background: rgba(228, 248, 245, 0.92);
}

.profile-analysis-chip.is-cognitive strong {
  color: #247d74;
}

.profile-analysis-chip.is-pace {
  border-color: rgba(104, 174, 98, 0.25);
  background: rgba(234, 248, 231, 0.94);
}

.profile-analysis-chip.is-pace strong {
  color: #4f874b;
}

.profile-analysis-chip.is-model {
  background: rgba(247, 249, 250, 0.82);
}

.profile-analysis-chip.is-model.active {
  border-color: rgba(72, 135, 207, 0.26);
  background: rgba(231, 242, 255, 0.94);
  box-shadow: 0 20px 46px rgba(72, 135, 207, 0.13);
}

.profile-analysis-chip.is-model.active strong {
  color: #3b73b3;
}

.profile-archive-panel {
  display: grid;
  gap: 24px;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  border-left: 2px dashed rgba(35, 58, 53, 0.2);
  padding-left: clamp(18px, 2.5vw, 36px);
  padding-right: 10px;
  scrollbar-color: rgba(18, 128, 111, 0.18) transparent;
  scrollbar-width: thin;
}

.profile-archive-title {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
}

.profile-archive-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  color: var(--teal);
  font-weight: 900;
}

.profile-archive-actions span,
.profile-archive-actions button {
  border-radius: 999px;
  padding: 10px 14px;
  background: rgba(18, 128, 111, 0.08);
  color: var(--teal);
  font: inherit;
  font-size: 0.92rem;
  font-weight: 900;
  white-space: nowrap;
}

.profile-archive-actions button {
  border: 1px solid rgba(18, 128, 111, 0.16);
  cursor: pointer;
}

.profile-dimension-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

.profile-dimension-card,
.profile-cluster-card,
.profile-confidence-card {
  border-radius: 24px;
  padding: 22px;
  background: rgba(255, 255, 255, 0.74);
}

.profile-dimension-card {
  display: grid;
  gap: 12px;
  min-height: 138px;
}

.profile-dimension-card span,
.profile-confidence-card span {
  color: rgba(31, 55, 50, 0.58);
  font-size: 0.88rem;
  font-weight: 800;
}

.profile-dimension-card strong {
  color: var(--ink);
  font-size: 1.06rem;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.profile-dimension-card.is-weak {
  border-color: rgba(235, 104, 76, 0.22);
  background: rgba(255, 247, 243, 0.86);
}

.profile-dimension-card.is-resource {
  border-color: rgba(226, 132, 0, 0.18);
}

.profile-dimension-card.is-data {
  border-color: rgba(67, 115, 190, 0.18);
}

.profile-progress {
  overflow: hidden;
  height: 9px;
  border-radius: 999px;
  background: rgba(28, 65, 57, 0.09);
}

.profile-progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #0f8a79, #6bc4b5);
}

.profile-cluster-card,
.profile-confidence-card {
  display: grid;
  gap: 16px;
}

.profile-cluster-card h3 {
  margin: 0;
  color: var(--ink);
  font-size: 1.12rem;
}

.profile-tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.profile-tag-row span {
  max-width: 100%;
  border-radius: 999px;
  padding: 9px 14px;
  font-size: 0.9rem;
  font-weight: 900;
  overflow-wrap: anywhere;
}

.profile-tag-row .is-weak {
  background: rgba(235, 104, 76, 0.12);
  color: #e4573e;
}

.profile-tag-row .is-resource {
  background: rgba(226, 132, 0, 0.11);
  color: #d67508;
}

.profile-confidence-card > div:first-child {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.profile-confidence-card strong {
  color: var(--ink);
  font-size: 2rem;
}

.profile-confidence-card p {
  margin: 0;
  color: rgba(31, 55, 50, 0.58);
  text-align: right;
}

@media (max-width: 1100px) {
  .profile-chat-primary {
    grid-template-columns: 1fr;
    height: auto;
  }

  .profile-chat-panel--dialogue {
    min-height: 620px;
  }

  .profile-analysis-flow {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 20px;
  }

  .profile-archive-panel {
    height: auto;
    overflow: visible;
    border-top: 2px dashed rgba(35, 58, 53, 0.2);
    border-left: 0;
    padding-top: clamp(24px, 4vw, 40px);
    padding-left: 0;
    padding-right: 0;
  }
}
.profile-dimension-meta { display:grid; gap:6px; min-width:0; }
.profile-dimension-meta small { color:rgba(31,55,50,.48); font-size:.72rem; font-weight:800; }

@media (max-width: 820px) {
  .profile-dimension-grid,
  .profile-analysis-flow {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 680px) {
  .profile-chat-page {
    padding-inline: 16px;
  }

  .profile-dimension-grid,
  .profile-analysis-flow {
    grid-template-columns: 1fr;
  }

  .profile-archive-title {
    grid-template-columns: 1fr;
  }

  .profile-archive-actions {
    justify-content: flex-start;
    flex-wrap: wrap;
  }
}
</style>
