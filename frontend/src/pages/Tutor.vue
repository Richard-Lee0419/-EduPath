<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getCourses } from '../api/course';
import { getCurrentProfile } from '../api/profile';
import { chatWithTutor } from '../api/tutor';
import RichContentRenderer from '../components/RichContentRenderer.vue';
import type { PageKey, Profile, TutorAnswer } from '../types';

const emit = defineEmits<{
  navigate: [page: PageKey];
}>();

const question = ref('');
const answer = ref<TutorAnswer | null>(null);
const loading = ref(false);
const mode = ref<'summary' | 'diagram' | 'code' | 'exam'>('diagram');
const activeTaskId = ref('');
const taskProgress = ref(0);
const errorMessage = ref('');
const profile = ref<Profile | null>(null);
const courses = ref<Array<{ id: number; name: string }>>([]);
const selectedCourseId = ref<number | null>(null);

const weakPoints = computed(() => profile.value?.weakPoints.filter(Boolean) ?? []);
const cognitiveStyle = computed(() => profile.value?.cognitiveStyle.filter(Boolean) ?? []);
const resourcePreference = computed(() => profile.value?.resourcePreference.filter(Boolean) ?? []);
const nextPractice = computed(() => weakPoints.value[0] ? `练习：${weakPoints.value[0]}` : '补充学习画像');
const modeTitle = computed(() => {
  if (mode.value === 'summary') return '简洁解释';
  if (mode.value === 'code') return '代码解释';
  if (mode.value === 'exam') return '考试重点';
  return '图解解释';
});
const modeAnswer = computed(() => answer.value?.answerMarkdown || '');
const diagramFrames = computed(() => answer.value?.steps ?? []);
const modelRuntimeSummary = computed(() => {
  const runtime = answer.value?.modelRuntime;
  if (!runtime) return '等待模型运行记录';
  if (runtime.mode === 'deterministic_fallback') {
    return runtime.call_count
      ? `已启用证据安全回退 · 模型审查链调用 ${runtime.call_count} 次`
      : '确定性证据回答';
  }
  return `${runtime.provider || 'model'} / ${runtime.model || 'unknown'} · ${runtime.call_count || 0} 次调用 · ${runtime.total_tokens || 0} tokens`;
});
const safetySummary = computed(() => {
  if (!answer.value?.safety) return '等待 SafetyAgent 结果';
  return answer.value.safety.passed
    ? `SafetyAgent 已独立复核通过 · 置信度 ${Math.round(answer.value.safety.confidence * 100)}%`
    : 'SafetyAgent 审查未通过';
});
const questionPlaceholder = computed(() =>
  weakPoints.value[0] ? `请帮我讲清楚${weakPoints.value[0]}` : '提问、请求图表或粘贴代码...',
);

const inferCourseId = (text: string) => {
  const normalized = text.toLowerCase();
  const computerOrganizationKeywords = [
    'cache', '直接映射', '全相联', '组相联', '冲突缺失', 'tag', 'index', 'offset',
    '地址拆分', '存储器', 'cpu', '指令系统', '流水线', '虚拟存储',
  ];
  const algorithmKeywords = [
    '递归', '调用栈', '二叉树', '遍历', '排序', '查找', '图', '队列', '链表',
    '时间复杂度', '空间复杂度', '动态规划', '算法',
  ];

  if (computerOrganizationKeywords.some((keyword) => normalized.includes(keyword))) {
    return courses.value.find((course) => /组成|organization/i.test(course.name))?.id ?? null;
  }
  if (algorithmKeywords.some((keyword) => normalized.includes(keyword))) {
    return courses.value.find((course) => /数据结构|算法|algorithm/i.test(course.name))?.id ?? null;
  }
  return null;
};

const askTutor = async () => {
  const text = question.value.trim();
  if (!text || loading.value) return;

  loading.value = true;
  errorMessage.value = '';
  taskProgress.value = 3;
  try {
    if (!selectedCourseId.value) {
      throw new Error('课程列表尚未加载，无法创建辅导任务');
    }
    const inferredCourseId = inferCourseId(text);
    const requestCourseId = inferredCourseId ?? selectedCourseId.value;
    if (inferredCourseId && inferredCourseId !== selectedCourseId.value) {
      selectedCourseId.value = inferredCourseId;
    }
    const result = await chatWithTutor(
      {
        courseId: requestCourseId,
        question: text,
        answerMode: mode.value === 'code' ? 'code_first' : mode.value === 'summary' ? 'summary' : 'step_by_step',
        sessionId: answer.value?.sessionId || undefined,
      },
      (task) => {
        activeTaskId.value = task.task_id;
        taskProgress.value = task.progress;
      },
    );
    activeTaskId.value = result.taskId;
    taskProgress.value = 100;
    answer.value = result.answer;
  } catch (error) {
    const message = error instanceof Error ? error.message : '智能辅导任务失败';
    errorMessage.value = message.includes('/tutor/chat 不可用')
      ? '回答未通过课程证据或结构校验。请确认课程选择后重试；系统不会返回无依据答案。'
      : message;
  } finally {
    loading.value = false;
  }
};

const switchMode = (nextMode: typeof mode.value) => {
  mode.value = nextMode;
};

const startPractice = () => {
  emit('navigate', 'quiz');
};

onMounted(async () => {
  try {
    const [courseRows, profileResult] = await Promise.all([getCourses(), getCurrentProfile().catch(() => null)]);
    courses.value = courseRows.map((item) => ({ id: item.id, name: item.name }));
    selectedCourseId.value = courseRows[0]?.id ?? null;
    profile.value = profileResult?.profile ?? null;
  } catch {
    // Keep the page usable; askTutor will surface missing course/profile state.
  }
});
</script>

<template>
  <div class="tutor-page ai-fluid-bg page-enter">
    <section class="tutor-context">
      <h2>学习上下文</h2>
      <article class="context-card interactive-card">
        <span>当前课程</span>
        <select v-model.number="selectedCourseId" class="course-select" aria-label="选择智能辅导课程">
          <option v-for="course in courses" :key="course.id" :value="course.id">
            {{ course.name }}
          </option>
        </select>
        <small class="course-routing-note">系统会按问题关键词自动匹配课程，也可以在这里手动切换。</small>
        <div class="context-divider"></div>
        <span>知识点</span>
        <em>{{ weakPoints[0] || '暂无画像薄弱点' }}</em>
      </article>

      <article class="context-card interactive-card">
        <span>学习者画像</span>
        <strong>学习风格</strong>
        <em class="blue-dot">{{ cognitiveStyle.join('、') || '暂无认知风格' }}</em>
        <span>薄弱环节</span>
        <div class="tag-line">
          <small v-for="item in weakPoints" :key="item">{{ item }}</small>
          <small v-if="!weakPoints.length">暂无薄弱点</small>
        </div>
        <div class="tag-line good">
          <small v-for="item in resourcePreference" :key="item">{{ item }}</small>
          <small v-if="!resourcePreference.length">暂无资源偏好</small>
        </div>
      </article>
    </section>

    <main class="tutor-answer-zone">
      <header class="tutor-answer-head">
        <div class="agent-avatar" aria-hidden="true">
          <svg viewBox="0 0 32 32">
            <circle cx="16" cy="16" r="10" />
            <path d="M11 15h10M12 20h8M16 6v-3" />
          </svg>
        </div>
        <h1>智能辅导响应</h1>
        <div class="mode-tabs">
          <button :class="{ active: mode === 'summary' }" type="button" @click="switchMode('summary')">简洁解释</button>
          <button :class="{ active: mode === 'diagram' }" type="button" @click="switchMode('diagram')">图解解释</button>
          <button :class="{ active: mode === 'code' }" type="button" @click="switchMode('code')">代码解释</button>
          <button :class="{ active: mode === 'exam' }" type="button" @click="switchMode('exam')">考试重点</button>
        </div>
      </header>

      <section class="answer-card interactive-card">
        <div class="answer-title">
          <span></span>
          <h2>{{ modeTitle }}</h2>
        </div>
        <details v-if="activeTaskId" class="tutor-task-details">
          <summary>查看任务技术信息 · {{ taskProgress }}%</summary>
          <code>{{ activeTaskId }}</code>
        </details>
        <p v-if="errorMessage" class="tutor-error-line">{{ errorMessage }}</p>
        <p v-if="loading">正在基于课程知识库组织回答...</p>
        <template v-else-if="answer">
          <RichContentRenderer :content="modeAnswer" content-format="markdown" />
          <div v-if="mode === 'diagram' && diagramFrames.length" class="stack-visual" aria-label="回答步骤图解">
            <div v-for="frame in diagramFrames" :key="frame">{{ frame }}</div>
          </div>
          <div class="warning-box">
            <strong>回答依据</strong>
            <p>{{ safetySummary }}</p>
            <small>{{ modelRuntimeSummary }}</small>
          </div>
          <p v-if="answer.learningUpdate?.mastery_updates?.length" class="mastery-feedback">
            本次追问已更新 {{ answer.learningUpdate.mastery_updates[0].knowledge_point }} 掌握度：
            {{ answer.learningUpdate.mastery_updates[0].previous_score }}% →
            {{ answer.learningUpdate.mastery_updates[0].mastery_score }}%
          </p>

          <div v-if="answer.citations.length" class="citation-map">
            <strong>回答片段与证据映射</strong>
            <article v-for="citation in answer.citations" :key="citation.answerFragment">
              <p>“{{ citation.answerFragment }}”</p>
              <small>{{ citation.evidenceChunkIds.join('、') }}</small>
            </article>
          </div>

          <div class="trace-card">
            <div class="trace-title">
              <strong>回答拆解</strong>
              <span>⌕</span>
            </div>
            <ol>
              <li v-for="(step, index) in answer.steps" :key="step">
                <span>{{ index + 1 }}</span>
                {{ step }}
              </li>
            </ol>
          </div>

          <div class="answer-footer">
            <div>
              <span>建议下一步</span>
              <strong>{{ nextPractice }}</strong>
            </div>
            <button class="primary-action hover-scale" type="button" @click="startPractice">开始练习</button>
          </div>
        </template>
        <p v-else>请输入问题后提交，TutorAgent 会返回真实任务结果。</p>
      </section>

      <form class="tutor-composer" @submit.prevent="askTutor">
        <button type="button" aria-label="添加资料">+</button>
        <input v-model="question" :placeholder="questionPlaceholder" />
        <button class="send-button hover-scale" type="submit" :disabled="loading">➤</button>
      </form>
    </main>

    <aside class="tutor-evidence">
      <h2>知识依据与推荐资源</h2>
      <article v-if="!answer?.evidence.length" class="evidence-card interactive-card">
        <span>RAG 证据</span>
        <p>等待后端返回证据片段</p>
      </article>
      <article v-for="item in answer?.evidence || []" :key="item.chunkId" class="evidence-card interactive-card">
        <span>RAG 证据 · {{ Math.round(item.score * 100) }}%</span>
        <strong>{{ item.title }}</strong>
        <p>{{ item.content || '已检索到课程知识库证据片段' }}</p>
        <small>{{ item.source || '课程知识库' }}</small>
      </article>
      <small class="kb-note">基于课程知识库生成</small>
    </aside>
  </div>
</template>

<style scoped>
.tutor-page {
  min-height: calc(100vh - 72px);
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr) 320px;
  gap: 30px;
  overflow-x: hidden;
  padding: 48px 68px 34px;
  color: var(--color-ink);
}

.tutor-context,
.tutor-evidence {
  display: grid;
  align-content: start;
  gap: 22px;
  min-width: 0;
}

.tutor-context h2,
.tutor-evidence h2 {
  font-size: 18px;
  margin: 0;
}

.context-card,
.answer-card,
.evidence-card {
  border: 1px solid rgba(16, 94, 83, 0.12);
  background: rgba(255, 255, 255, 0.78);
  border-radius: 18px;
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px);
}

.context-card {
  display: grid;
  gap: 14px;
  min-width: 0;
  padding: 26px;
}

.context-card span,
.evidence-card span,
.answer-footer span {
  color: rgba(31, 54, 49, 0.58);
  font-size: 13px;
}

.context-card strong {
  font-size: 22px;
}

.course-select {
  width: 100%;
  border: 1px solid rgba(0, 121, 102, 0.22);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--color-ink);
  cursor: pointer;
  font: inherit;
  font-weight: 800;
  padding: 11px 12px;
}

.course-routing-note {
  color: rgba(31, 54, 49, 0.58);
  line-height: 1.55;
}

.context-card em,
.tag-line small,
.concept-tags small {
  max-width: 100%;
  width: fit-content;
  border-radius: 999px;
  background: rgba(31, 54, 49, 0.06);
  color: var(--color-ink);
  font-style: normal;
  font-weight: 800;
  padding: 8px 12px;
  overflow-wrap: anywhere;
}

.context-card .blue-dot {
  background: rgba(68, 112, 186, 0.12);
  color: #386fb8;
}

.context-divider {
  height: 1px;
  background: rgba(31, 54, 49, 0.1);
}

.tag-line,
.concept-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.tag-line small {
  background: rgba(255, 104, 77, 0.13);
  color: #ff6048;
}

.tag-line.good small {
  background: rgba(0, 121, 102, 0.14);
  color: var(--color-primary);
}

.tutor-answer-zone {
  display: grid;
  gap: 18px;
  align-content: start;
  min-width: 0;
}

.tutor-answer-head {
  align-items: center;
  display: grid;
  grid-template-columns: 56px auto 1fr;
  gap: 18px;
  min-width: 0;
}

.agent-avatar {
  align-items: center;
  background: #078270;
  border-radius: 999px;
  color: #fff;
  display: flex;
  height: 56px;
  justify-content: center;
  width: 56px;
  box-shadow: 0 16px 26px rgba(0, 121, 102, 0.22);
}

.agent-avatar svg {
  fill: none;
  height: 34px;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2;
  width: 34px;
}

.tutor-answer-head h1 {
  font-size: 28px;
  line-height: 1.1;
  margin: 0;
}

.mode-tabs {
  justify-self: center;
  display: inline-flex;
  max-width: 100%;
  overflow-x: auto;
  border: 1px solid rgba(31, 54, 49, 0.1);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.58);
  padding: 5px;
}

.mode-tabs button {
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: rgba(31, 54, 49, 0.68);
  cursor: pointer;
  font-weight: 800;
  white-space: nowrap;
  padding: 12px 18px;
}

.mode-tabs .active {
  background: #fff;
  box-shadow: 0 10px 22px rgba(31, 54, 49, 0.1);
  color: var(--color-ink);
}

.answer-card {
  border: 2px solid rgba(0, 121, 102, 0.42);
  min-width: 0;
  padding: 34px;
}

.answer-title {
  align-items: center;
  display: flex;
  gap: 12px;
}

.answer-title span {
  width: 20px;
  height: 3px;
  background: var(--color-primary);
}

.answer-title h2 {
  color: var(--color-primary);
  font-size: 22px;
  margin: 0;
}

.tutor-task-details,
.tutor-error-line {
  font-size: 13px;
  font-weight: 800;
  margin: 10px 0 0;
}
.tutor-task-details {
  color: rgba(31, 54, 49, 0.62);
}
.tutor-task-details summary { cursor:pointer; font-weight:800; }
.tutor-task-details code { display:block; margin-top:6px; overflow-wrap:anywhere; }

.tutor-error-line {
  color: #df5b45;
}

.answer-card p,
.evidence-card p {
  color: rgba(31, 54, 49, 0.74);
  font-size: 18px;
  line-height: 1.85;
  overflow-wrap: anywhere;
}

.mode-answer-text {
  white-space: pre-line;
  overflow-wrap: anywhere;
}

.warning-box {
  border: 1px solid rgba(255, 130, 57, 0.35);
  border-radius: 14px;
  background: rgba(255, 247, 238, 0.82);
  color: #e56d1a;
  margin: 28px 0;
  padding: 20px;
}

.warning-box p {
  color: rgba(31, 54, 49, 0.72);
  font-size: 16px;
  margin-bottom: 0;
}

.warning-box small {
  color: rgba(31, 54, 49, 0.62);
  display: block;
  margin-top: 10px;
}

.citation-map {
  display: grid;
  gap: 12px;
  margin: 24px 0;
}

.citation-map article {
  border-left: 3px solid rgba(0, 121, 102, 0.55);
  background: rgba(0, 121, 102, 0.06);
  border-radius: 0 12px 12px 0;
  padding: 14px 16px;
}

.citation-map article p {
  font-size: 15px;
  line-height: 1.6;
  margin: 0 0 8px;
}

.citation-map article small {
  color: var(--color-primary);
  font-weight: 800;
  overflow-wrap: anywhere;
}

.trace-card {
  border: 1px solid rgba(31, 54, 49, 0.1);
  border-radius: 14px;
  overflow: hidden;
}

.trace-title {
  align-items: center;
  background: rgba(247, 249, 248, 0.92);
  display: flex;
  justify-content: space-between;
  padding: 16px 20px;
}

.stack-visual {
  display: grid;
  gap: 12px;
  justify-items: center;
  padding: 30px;
  position: relative;
}

.stack-visual div {
  max-width: 100%;
  border: 2px solid rgba(0, 121, 102, 0.48);
  border-radius: 8px;
  background: rgba(0, 121, 102, 0.08);
  font-family: Consolas, monospace;
  padding: 14px clamp(18px, 6vw, 54px);
  overflow-wrap: anywhere;
}

.stack-arrow {
  color: var(--color-primary);
  font-weight: 900;
  position: absolute;
  right: 76px;
  top: 64px;
  writing-mode: vertical-rl;
}

.trace-card ol {
  border-top: 1px solid rgba(31, 54, 49, 0.08);
  display: grid;
  gap: 14px;
  margin: 0;
  padding: 22px 28px 28px;
}

.trace-card li {
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.trace-card li span {
  align-items: center;
  background: rgba(31, 54, 49, 0.08);
  border-radius: 999px;
  display: inline-flex;
  font-weight: 900;
  height: 26px;
  justify-content: center;
  margin-right: 10px;
  width: 26px;
}

.answer-footer {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 20px;
  margin-top: 22px;
}

.answer-footer strong {
  display: block;
  margin-top: 6px;
}

.primary-action,
.send-button {
  border: 0;
  background: var(--color-primary);
  color: #fff;
  cursor: pointer;
  font-weight: 900;
}

.primary-action {
  border-radius: 12px;
  min-height: 48px;
  padding: 0 28px;
}

.tutor-composer {
  align-items: center;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(31, 54, 49, 0.1);
  border-radius: 999px;
  box-shadow: var(--shadow-card);
  display: grid;
  grid-template-columns: 46px 1fr 54px;
  gap: 12px;
  padding: 10px 12px;
}

.tutor-composer input {
  border: 0;
  background: transparent;
  font: inherit;
  min-width: 0;
  outline: 0;
}

.tutor-composer button {
  border-radius: 999px;
  height: 46px;
}

.tutor-composer button:first-child {
  border: 1px solid rgba(31, 54, 49, 0.16);
  background: #fff;
  color: rgba(31, 54, 49, 0.78);
  cursor: pointer;
  font-size: 26px;
}

.send-button:disabled {
  opacity: 0.55;
}

.evidence-card {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 22px;
}

.evidence-card strong,
.evidence-card small {
  overflow-wrap: anywhere;
}

.evidence-card p {
  font-size: 13px;
  line-height: 1.5;
  margin: 0;
}

.resource-hint {
  border-color: rgba(247, 153, 57, 0.28);
}

.resource-hint strong {
  display: block;
  margin-top: 10px;
}

.kb-note {
  color: rgba(31, 54, 49, 0.58);
  justify-self: center;
}

@media (max-width: 1180px) {
  .tutor-page {
    grid-template-columns: 1fr;
    padding: 30px 22px;
  }

  .tutor-answer-head {
    grid-template-columns: 56px 1fr;
  }

  .mode-tabs {
    grid-column: 1 / -1;
    justify-self: stretch;
    flex-wrap: wrap;
    overflow-x: visible;
    border-radius: 18px;
  }

  .mode-tabs button {
    flex: 1 1 132px;
  }
}
</style>
