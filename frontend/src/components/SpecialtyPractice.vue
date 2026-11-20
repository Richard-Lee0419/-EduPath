<script setup lang="ts" generic="Exercise extends { id: string }, Answer">
import { computed, nextTick, onMounted, ref, shallowRef } from 'vue';
import type { SpecialtyPracticeState } from '../api/specialtyPractice';

const props = defineProps<{
  topic: string;
  course: string;
  title: string;
  sourceTitle: string;
  validationMessage: string;
  loadState: () => Promise<SpecialtyPracticeState<Exercise, Answer>>;
  submitAttempt: (exerciseId: string, answer: Answer) => Promise<SpecialtyPracticeState<Exercise, Answer>>;
  readAnswer: () => Answer | null;
  describeExercise: (exercise: Exercise) => string;
  describeAnswer: (answer: Answer) => string;
}>();
const emit = defineEmits<{ resetAnswer: [] }>();

const practice = shallowRef<SpecialtyPracticeState<Exercise, Answer> | null>(null);
const loading = ref(true);
const submitting = ref(false);
const errorMessage = ref('');
const retestOpen = ref(false);
const resultHeading = ref<HTMLElement | null>(null);
const retestHeading = ref<HTMLElement | null>(null);

const exercise = computed(() => practice.value?.next_exercise);
const attempts = computed(() => [
  { label: '首次诊断结果', attempt: practice.value?.initial },
  { label: '新题复测结果', attempt: practice.value?.retest },
]);
const showForm = computed(() => exercise.value && (practice.value?.stage === 'initial' || retestOpen.value));

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const saved = await props.loadState();
    if (practice.value?.next_exercise?.id !== saved.next_exercise?.id) {
      emit('resetAnswer');
      retestOpen.value = false;
    }
    practice.value = saved;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '练习记录加载失败';
  } finally {
    loading.value = false;
  }
}

async function openRetest() {
  retestOpen.value = true;
  await nextTick();
  retestHeading.value?.focus();
}

async function submit() {
  const current = exercise.value;
  if (!current || submitting.value) return;
  const answer = props.readAnswer();
  if (answer === null) {
    errorMessage.value = props.validationMessage;
    return;
  }
  submitting.value = true;
  errorMessage.value = '';
  try {
    practice.value = await props.submitAttempt(current.id, answer);
    emit('resetAnswer');
    retestOpen.value = false;
    await nextTick();
    resultHeading.value?.focus();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '作答提交失败，请重试';
  } finally {
    submitting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="specialty-practice interactive-card" :aria-labelledby="`${topic}-practice-title`">
    <header class="practice-header">
      <div>
        <p class="course-name">{{ course }}</p>
        <h2 :id="`${topic}-practice-title`">{{ title }}</h2>
      </div>
      <span v-if="practice?.stage === 'complete'" class="practice-complete">复测已记录</span>
    </header>

    <p v-if="loading" role="status">正在读取练习记录…</p>
    <template v-else>
      <div v-if="practice?.initial" class="practice-results">
        <h3 ref="resultHeading" class="practice-result-heading" tabindex="-1">
          {{ practice.retest ? '练习记录' : '诊断与补救' }}
        </h3>
        <template v-for="{ label, attempt } in attempts" :key="label">
          <article v-if="attempt" class="practice-result">
            <header>
              <h4>{{ label }}</h4>
              <span :class="attempt.feedback.correct ? 'correct' : 'review'">
                {{ attempt.feedback.correct ? '全部正确' : '需要复核' }}
              </span>
            </header>
            <p class="practice-meta">
              {{ describeExercise(attempt.exercise) }} ·
              <time :datetime="attempt.submitted_at">{{ formatDate(attempt.submitted_at) }}</time>
            </p>
            <p><strong>你的作答</strong> {{ describeAnswer(attempt.answer) }}</p>
            <p class="practice-explanation">{{ attempt.feedback.explanation }}</p>
            <p v-if="attempt.feedback.provenance?.source === 'agentarts'" class="practice-meta">
              AgentArts 执行编号 <code>{{ attempt.feedback.provenance.execution_id }}</code>
            </p>
            <details>
              <summary>查看课程依据</summary>
              <p>《{{ sourceTitle }}》 · {{ attempt.feedback.citation.section }} · {{ attempt.feedback.citation.source_version }}</p>
              <blockquote>{{ attempt.feedback.citation.excerpt }}</blockquote>
            </details>
          </article>
        </template>
      </div>
      <div v-if="practice?.stage === 'retest'" class="practice-focus">
        <p>{{ practice.focus }}</p>
        <template v-if="!retestOpen">
          <p class="practice-meta">首次诊断已保存。打开新题后仍可查看首答与课程依据。</p>
          <button type="button" class="practice-action" @click="openRetest">开始新题复测</button>
        </template>
      </div>

      <form v-if="exercise && showForm" class="practice-form" @submit.prevent="submit">
        <h3 ref="retestHeading" tabindex="-1">{{ practice?.stage === 'initial' ? '首次诊断' : '新题复测' }}</h3>
        <slot name="exercise" :exercise="exercise" :submitting="submitting" />
        <button class="practice-action" type="submit" :disabled="submitting">
          {{ submitting ? '正在批阅…' : practice?.stage === 'initial' ? '提交诊断' : '提交复测' }}
        </button>
      </form>

      <div v-if="errorMessage" class="practice-error">
        <p role="alert">{{ errorMessage }}</p>
        <button type="button" class="practice-reload" @click="load">重新加载练习</button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.specialty-practice {
  margin: 10px 0 28px;
  scroll-margin-top: 88px;
  padding: clamp(18px, 3vw, 34px);
  border: 1px solid #d9e7e2;
  border-radius: 18px;
  background: #fff;
  line-height: 1.6;
}

.practice-header, .practice-result header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px 16px;
  flex-wrap: wrap;
}

h2, h3, h4 { margin: 0; }
h3 { scroll-margin-top: 88px; }
p { margin: 6px 0; }

.course-name, .practice-meta {
  color: #516963;
  font-size: 0.9rem;
}

.practice-form, .practice-focus, .practice-results, .practice-error {
  margin-top: 18px;
}

.practice-focus {
  border-inline-start: 3px solid #168171;
  padding-inline-start: 14px;
}

.practice-action, .practice-reload {
  padding: 7px 14px;
  border: 1px solid #116458;
  border-radius: 8px;
  background: #116458;
  color: #fff;
  font: inherit;
  cursor: pointer;
}

.practice-action:disabled { opacity: 0.72; cursor: wait; }
.practice-reload { background: #fff; color: #116458; }
.practice-result {
  margin-top: 8px;
  padding: 14px;
  border: 1px solid #dfebe7;
  border-radius: 10px;
  background: #f6faf8;
}
.practice-explanation { white-space: pre-line; }
.practice-complete, .correct { color: #126653; font-weight: 700; }
.review, .practice-error { color: #9a3f24; }
summary { width: fit-content; color: #116458; font-weight: 600; cursor: pointer; }
blockquote { margin: 8px 0 0; padding-inline-start: 12px; border-inline-start: 2px solid #8caaa0; }
</style>
