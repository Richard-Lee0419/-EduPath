<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getCurrentProfile } from '../api/profile';
import { getCourses } from '../api/course';
import { generateQuiz, submitQuiz, type QuizQuestion, type QuizSubmitResult } from '../api/quiz';
import type { Profile } from '../types';
import type { ModelRuntime, ResourceEvidence, ResourceSafety } from '../types/api';

const quizId = ref<number | null>(null);
const quizTitle = ref('');
const questions = ref<QuizQuestion[]>([]);
const answers = ref<Record<number, string>>({});
const profile = ref<Profile | null>(null);
const loading = ref(true);
const submitting = ref(false);
const errorMessage = ref('');
const courses = ref<Array<{ id: number; name: string }>>([]);
const selectedCourseId = ref<number | null>(null);
const activeTaskId = ref('');
const taskProgress = ref(0);
const modelRuntime = ref<ModelRuntime | null>(null);
const safetyReview = ref<ResourceSafety | null>(null);
const evidence = ref<ResourceEvidence[]>([]);
const submitResult = ref<QuizSubmitResult | null>(null);

const targetKnowledgePoints = computed(() => {
  const points = profile.value?.weakPoints.filter(Boolean) ?? [];
  return points.slice(0, 3);
});

const generationSource = computed(() =>
  profile.value && targetKnowledgePoints.value.length
    ? `基于当前学习画像薄弱点：${targetKnowledgePoints.value.join('、')}`
    : '未发现画像薄弱点，按当前课程知识库生成基础诊断题',
);

const runtimeSummary = computed(() => {
  const runtime = modelRuntime.value;
  if (!runtime) return '等待本轮模型运行记录';
  if (runtime.mode === 'real_model') {
    return `${runtime.model || runtime.provider || '真实模型'} · ${runtime.call_count ?? 0} 次调用 · ${runtime.total_tokens ?? 0} Token`;
  }
  return '确定性回退模式';
});

const completedAnswers = computed(() => questions.value.filter((question) => answers.value[question.question_id]).length);

const loadQuiz = async () => {
  loading.value = true;
  errorMessage.value = '';
  submitResult.value = null;
  taskProgress.value = 2;
  modelRuntime.value = null;
  safetyReview.value = null;
  evidence.value = [];
  try {
    const [profileResult, courseRows] = await Promise.all([getCurrentProfile(), getCourses()]);
    profile.value = profileResult.profile;
    courses.value = courseRows.map((item) => ({ id: item.id, name: item.name }));
    if (!selectedCourseId.value) {
      const preferred = courseRows.find((item) => profile.value?.targetCourses.includes(item.name));
      selectedCourseId.value = preferred?.id ?? courseRows[0]?.id ?? null;
    }
    if (!selectedCourseId.value) throw new Error('没有可用于生成小测的课程');
    const quiz = await generateQuiz(
      {
        courseId: selectedCourseId.value,
        knowledgePoints: targetKnowledgePoints.value,
        difficulty: 'basic',
        questionCount: 3,
      },
    );
    activeTaskId.value = quiz.taskId;
    taskProgress.value = 100;
    quizId.value = quiz.quiz_id;
    quizTitle.value = quiz.title || '3 题专项诊断小测';
    questions.value = quiz.questions;
    answers.value = {};
    evidence.value = quiz.evidence;
    modelRuntime.value = quiz.modelRuntime;
    safetyReview.value = quiz.safety;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '小测生成失败';
  } finally {
    loading.value = false;
  }
};

const submitCurrentQuiz = async () => {
  if (!quizId.value || submitting.value) return;
  if (completedAnswers.value !== questions.value.length) {
    errorMessage.value = `请先完成全部 ${questions.value.length} 道题`;
    return;
  }
  submitting.value = true;
  errorMessage.value = '';
  submitResult.value = null;
  taskProgress.value = 3;
  try {
    const result = await submitQuiz(
      quizId.value,
      questions.value.map((question) => ({
        question_id: question.question_id,
        answer: answers.value[question.question_id] || '',
      })),
    );
    activeTaskId.value = result.taskId;
    taskProgress.value = 100;
    submitResult.value = result;
    evidence.value = result.evidence;
    modelRuntime.value = result.modelRuntime;
    safetyReview.value = result.safety;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '小测提交失败';
  } finally {
    submitting.value = false;
  }
};

onMounted(loadQuiz);
</script>

<template>
  <section class="quiz-page ai-page-bg page-enter">
    <header class="quiz-hero interactive-card">
      <div>
        <span>QuizAgent · 最多 3 题</span>
        <h1>课程知识诊断小测</h1>
        <p>{{ generationSource }}</p>
      </div>
      <div class="quiz-hero__actions">
        <select v-model="selectedCourseId" :disabled="loading || submitting" @change="loadQuiz">
          <option v-for="course in courses" :key="course.id" :value="course.id">{{ course.name }}</option>
        </select>
        <button class="secondary-action hover-scale" type="button" :disabled="loading || submitting" @click="loadQuiz">
          {{ loading ? `生成中 ${taskProgress}%` : '重新生成 3 题' }}
        </button>
      </div>
    </header>

    <div v-if="activeTaskId" class="task-strip">
      <span>task_id: {{ activeTaskId }}</span>
      <strong>{{ taskProgress }}%</strong>
    </div>
    <p v-if="errorMessage" class="quiz-error">{{ errorMessage }}</p>

    <p v-if="loading" class="quiz-state">KnowledgeAgent 正在检索课程证据，QuizAgent 将只生成 3 道以内的结构化单选题…</p>
    <template v-else-if="questions.length">
      <section class="quiz-meta interactive-card">
        <div>
          <span>小测 ID</span>
          <strong>{{ quizId }}</strong>
        </div>
        <div>
          <span>题量限制</span>
          <strong>{{ questions.length }} / 3 题</strong>
        </div>
        <div>
          <span>模型运行</span>
          <strong>{{ runtimeSummary }}</strong>
        </div>
        <div>
          <span>安全审查</span>
          <strong>{{ safetyReview?.passed ? 'SafetyAgent 已通过' : '等待审查记录' }}</strong>
        </div>
      </section>

      <article v-for="(question, index) in questions" :key="question.question_id" class="quiz-question interactive-card">
        <div class="quiz-question__head">
          <div>
            <small>第 {{ index + 1 }} 题 · {{ question.knowledge_point || '课程知识点' }}</small>
            <span v-if="question.evidence_chunk_ids.length">RAG {{ question.evidence_chunk_ids.length }} 条</span>
          </div>
          <em>{{ question.difficulty || 'basic' }}</em>
        </div>
        <strong>{{ question.stem }}</strong>
        <div class="quiz-options">
          <label v-for="(option, optionIndex) in question.options" :key="option" :class="{ selected: answers[question.question_id] === option }">
            <input v-model="answers[question.question_id]" type="radio" :name="`q-${question.question_id}`" :value="option" />
            <b>{{ String.fromCharCode(65 + optionIndex) }}</b>
            <span>{{ option }}</span>
          </label>
        </div>
      </article>

      <footer class="quiz-submit interactive-card">
        <div>
          <strong>已完成 {{ completedAnswers }} / {{ questions.length }}</strong>
          <p>提交后由后端确定性判分，再经 EvaluationAgent、ProfileAgent 和 PathAgent 自动重规划未来三天。</p>
        </div>
        <button class="primary-action hover-scale" type="button" :disabled="submitting" @click="submitCurrentQuiz">
          {{ submitting ? `评估中 ${taskProgress}%` : '提交并生成评估' }}
        </button>
      </footer>

      <section v-if="submitResult" class="evaluation-result interactive-card">
        <div class="score-orb">
          <strong>{{ submitResult.score }}</strong>
          <span>{{ submitResult.correctCount }} / {{ submitResult.totalCount }} 题正确</span>
        </div>
        <div class="evaluation-copy">
          <span>EvaluationAgent 结论</span>
          <h2>{{ submitResult.evaluation.summary || '评估与画像已更新' }}</h2>
          <p v-if="submitResult.evaluation.weakPoints.length">
            薄弱点：{{ submitResult.evaluation.weakPoints.join('、') }}
          </p>
          <p v-else>本轮没有新增薄弱点。</p>
          <ul>
            <li v-for="action in submitResult.evaluation.nextActions" :key="action">{{ action }}</li>
          </ul>
          <small>画像版本已回写 · 来源 {{ submitResult.sourceTaskId || submitResult.taskId }}</small>
        </div>
      </section>

      <section v-if="submitResult?.pathUpdate.updated" class="path-update-card interactive-card">
        <div>
          <span>PathAgent 动态重规划</span>
          <strong>路径 v{{ submitResult.pathUpdate.previousVersion }} → v{{ submitResult.pathUpdate.version }}</strong>
          <small>由评估任务 {{ submitResult.pathUpdate.sourceEvaluationTaskId }} 触发</small>
        </div>
        <ul>
          <li v-for="change in submitResult.pathUpdate.changes" :key="`${change.action}-${change.knowledgePoint}`">
            <strong>{{ change.knowledgePoint }}</strong>
            <span>{{ change.reason }}</span>
          </li>
        </ul>
      </section>

      <section v-if="evidence.length" class="evidence-panel interactive-card">
        <div>
          <span>本轮 RAG 证据</span>
          <strong>{{ evidence.length }} 条可追溯片段</strong>
        </div>
        <ul>
          <li v-for="item in evidence.slice(0, 3)" :key="item.chunk_id || item.title">
            <strong>{{ item.title || item.chunk_id }}</strong>
            <small>{{ item.source || item.chunk_id }}</small>
          </li>
        </ul>
      </section>
    </template>
  </section>
</template>

<style scoped>
.quiz-page {
  min-height: calc(100vh - 72px);
  padding: 44px clamp(22px, 6vw, 86px) 72px;
  color: var(--color-ink);
}

.quiz-hero,
.quiz-meta,
.quiz-question,
.quiz-submit,
.evaluation-result,
.path-update-card,
.evidence-panel {
  border: 1px solid rgba(16, 94, 83, 0.12);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px);
}

.quiz-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 32px 36px;
}

.quiz-hero span,
.quiz-meta span,
.quiz-question__head small,
.evaluation-copy > span,
.evidence-panel > div span {
  color: rgba(31, 54, 49, 0.58);
  font-size: 13px;
  font-weight: 800;
}

.quiz-hero h1 {
  margin: 8px 0 10px;
  font-size: clamp(32px, 4vw, 48px);
}

.quiz-hero p,
.quiz-submit p,
.quiz-state,
.evaluation-copy p {
  color: rgba(31, 54, 49, 0.7);
  line-height: 1.8;
  margin: 0;
}

.quiz-hero__actions {
  display: grid;
  gap: 10px;
  min-width: 220px;
}

.quiz-hero select {
  border: 1px solid rgba(0, 121, 102, 0.2);
  border-radius: 12px;
  background: #fff;
  min-height: 44px;
  padding: 0 12px;
}

.secondary-action,
.primary-action {
  border: 0;
  border-radius: 12px;
  cursor: pointer;
  font-weight: 900;
  min-height: 48px;
  padding: 0 24px;
}

.secondary-action {
  border: 1px solid rgba(0, 121, 102, 0.2);
  background: rgba(0, 121, 102, 0.08);
  color: var(--color-primary);
}

.primary-action {
  background: var(--color-primary);
  color: #fff;
}

.secondary-action:disabled,
.primary-action:disabled {
  cursor: wait;
  opacity: 0.68;
}

.task-strip {
  display: flex;
  justify-content: space-between;
  margin: 18px 4px;
  color: rgba(31, 54, 49, 0.7);
  font-size: 13px;
}

.quiz-state,
.quiz-error {
  margin-top: 24px;
  font-weight: 800;
}

.quiz-error {
  color: #c94f3d;
}

.quiz-meta {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
  margin: 24px 0;
  padding: 22px 24px;
}

.quiz-meta div {
  display: grid;
  gap: 8px;
}

.quiz-meta strong {
  overflow-wrap: anywhere;
}

.quiz-question {
  display: grid;
  gap: 18px;
  margin-top: 16px;
  padding: 24px 26px;
}

.quiz-question__head,
.quiz-question__head > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.quiz-question__head span,
.quiz-question__head em {
  border-radius: 999px;
  background: rgba(0, 121, 102, 0.1);
  color: var(--color-primary);
  font-size: 12px;
  font-style: normal;
  font-weight: 900;
  padding: 5px 9px;
}

.quiz-options {
  display: grid;
  gap: 10px;
}

.quiz-options label {
  border: 1px solid rgba(31, 54, 49, 0.1);
  border-radius: 12px;
  cursor: pointer;
  display: grid;
  grid-template-columns: auto 28px 1fr;
  gap: 10px;
  align-items: center;
  padding: 12px 14px;
}

.quiz-options label.selected {
  border-color: rgba(0, 121, 102, 0.35);
  background: rgba(0, 121, 102, 0.08);
}

.quiz-options b {
  color: var(--color-primary);
}

.quiz-submit {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-top: 22px;
  padding: 24px 26px;
}

.evaluation-result {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 30px;
  margin-top: 24px;
  padding: 28px;
}

.score-orb {
  aspect-ratio: 1;
  border-radius: 50%;
  display: grid;
  place-content: center;
  text-align: center;
  background: radial-gradient(circle at 30% 20%, #effff8, #caeee2);
  color: var(--color-primary);
}

.score-orb strong {
  font-size: 52px;
}

.score-orb span {
  font-size: 12px;
}

.evaluation-copy h2 {
  margin: 8px 0 10px;
}

.evaluation-copy ul {
  padding-left: 20px;
  color: rgba(31, 54, 49, 0.75);
}

.evaluation-copy small {
  color: rgba(31, 54, 49, 0.52);
}

.evidence-panel {
  display: grid;
  grid-template-columns: 210px 1fr;
  gap: 24px;
  margin-top: 20px;
  padding: 24px 26px;
}

.path-update-card {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 24px;
  margin-top: 20px;
  padding: 24px 26px;
  border-color: rgba(255, 104, 77, 0.24);
  background: linear-gradient(135deg, rgba(255, 247, 244, 0.9), rgba(255, 255, 255, 0.84));
}

.path-update-card > div {
  display: grid;
  align-content: start;
  gap: 8px;
}

.path-update-card > div span,
.path-update-card small {
  color: rgba(31, 54, 49, 0.58);
  font-size: 12px;
}

.path-update-card ul {
  display: grid;
  gap: 10px;
  list-style: none;
  margin: 0;
  padding: 0;
}

.path-update-card li {
  display: grid;
  gap: 5px;
  border-radius: 12px;
  background: rgba(255, 104, 77, 0.08);
  padding: 12px 14px;
}

.path-update-card li span {
  color: rgba(31, 54, 49, 0.7);
  line-height: 1.6;
}

.evidence-panel > div {
  display: grid;
  align-content: start;
  gap: 8px;
}

.evidence-panel ul {
  display: grid;
  gap: 8px;
  list-style: none;
  margin: 0;
  padding: 0;
}

.evidence-panel li {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(0, 121, 102, 0.05);
}

.evidence-panel small {
  color: rgba(31, 54, 49, 0.52);
  text-align: right;
}

@media (max-width: 900px) {
  .quiz-meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .quiz-hero,
  .quiz-submit {
    align-items: stretch;
    flex-direction: column;
  }

  .quiz-hero__actions {
    width: 100%;
  }

  .quiz-meta,
  .evaluation-result,
  .path-update-card,
  .evidence-panel {
    grid-template-columns: 1fr;
  }

  .score-orb {
    max-width: 180px;
  }
}
</style>
