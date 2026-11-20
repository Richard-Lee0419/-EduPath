<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getCourses, learningCourses, requestedCourseId } from '../api/course';
import { getEvaluationReport } from '../api/evaluation';
import SplitText from '../components/SplitText.vue';
import type { EvaluationReport, PageKey } from '../types';

defineEmits<{
  navigate: [page: PageKey];
}>();

const report = ref<EvaluationReport | null>(null);
const loading = ref(true);
const errorMessage = ref('');
const courseId = requestedCourseId();
const selectedCourseName = ref('');

const diagnosticFlow = [
  { title: '测试结果', tone: 'neutral' },
  { title: '根因诊断', tone: 'weak' },
  { title: courseId ? '本课掌握度' : '画像更新', tone: 'data' },
  { title: '路径调整', tone: 'resource' },
  { title: '资源推荐', tone: 'active' },
];

const hasReport = computed(() => Boolean(report.value?.reportId));
const hasWeakPoints = computed(() => Boolean(report.value?.weakPoints.length));
const primaryWeakPoint = computed(() => report.value?.weakPoints[0] || '暂无明显薄弱点');
const primaryFocusPoint = computed(() =>
  report.value?.weakPoints[0]
  || report.value?.mastery[0]?.knowledgePoint
  || '完成一次诊断测验',
);
const heroDescription = computed(() =>
  report.value?.reportId
    ? `最近一次测验得分 ${report.value.overallScore} 分。${report.value.recommendation}`
    : '尚未形成评估报告。完成一次练习测试后，这里会展示掌握度、错因与下一步建议。',
);

const summaryCards = computed(() => [
  { label: '最近测验得分', value: report.value?.reportId ? `${report.value.overallScore} 分` : '尚未评估', tone: 'good' },
  { label: '主要弱点', value: primaryWeakPoint.value, tone: 'weak' },
  { label: '错误模式', value: report.value?.mistakePatterns.join('、') || '暂无错误模式', tone: 'data' },
  { label: '下一步建议', value: report.value?.recommendation || '完成测验后生成建议', tone: 'next' },
]);

const weakCards = computed(() => {
  const mastery = report.value?.mastery ?? [];
  const weakPoints = report.value?.weakPoints ?? [];
  if (!weakPoints.length) return [];

  const masteryByPoint = new Map(mastery.map((item) => [item.knowledgePoint, item]));
  return weakPoints
    .slice()
    .slice(0, 2)
    .map((knowledgePoint) => {
      const item = masteryByPoint.get(knowledgePoint);
      return {
        title: knowledgePoint,
        detail: item ? `${item.level} · ${item.masteryScore}%` : '待继续诊断',
      };
    });
});

onMounted(async () => {
  try {
    if (courseId) {
      const course = learningCourses(await getCourses()).find((item) => item.id === courseId);
      if (!course) throw new Error('课程不存在或当前账号没有访问权限。');
      selectedCourseName.value = course.name;
    }
    report.value = await getEvaluationReport(courseId ?? undefined);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '评估报告加载失败';
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="evaluation-page ai-page-bg page-enter">
    <section class="evaluation-hero">
      <SplitText tag="h1" text="学习效果评估报告" />
      <p v-if="selectedCourseName">{{ selectedCourseName }}</p>
      <p v-if="loading">正在读取评估报告…</p>
      <p v-else-if="errorMessage" role="alert">{{ errorMessage }}</p>
      <p v-else-if="!hasReport">{{ heroDescription }}</p>
      <p v-else-if="hasWeakPoints">你目前的主要薄弱环节是 <strong>{{ primaryWeakPoint }}</strong>。{{ heroDescription }}</p>
      <p v-else class="hero-success">本轮评估<strong>未发现新的明显薄弱点</strong>。{{ heroDescription }}</p>
    </section>

    <section class="summary-strip">
      <article v-for="card in summaryCards" :key="card.label" class="summary-card interactive-card" :class="`tone-${card.tone}`">
        <span>{{ card.label }}</span>
        <strong>{{ card.value }}</strong>
      </article>
    </section>

    <section class="diagnostic-panel interactive-card">
      <span>学习反馈闭环</span>
      <div class="diagnostic-flow">
        <article v-for="step in diagnosticFlow" :key="step.title" class="flow-step" :class="`flow-${step.tone}`">
          <i></i>
          <strong>{{ step.title }}</strong>
        </article>
      </div>
    </section>

    <template v-if="report">
      <section class="evaluation-grid">
        <article class="mastery-panel interactive-card">
          <div class="panel-heading">
            <span class="chart-mark"></span>
            <h2>知识点历史掌握度</h2>
          </div>
          <p v-if="report.submittedAt">最近测验交卷 {{ new Date(report.submittedAt).toLocaleString('zh-CN', { hour12: false }) }}</p>
          <p v-if="!report.mastery.length">暂无知识点掌握度记录，请先完成测验。</p>
          <div class="mastery-bars">
            <div v-for="item in report.mastery" :key="item.knowledgePoint" class="mastery-bar-row">
              <div>
                <span>{{ item.knowledgePoint }}<small v-if="item.attempts !== undefined"> · {{ item.attempts }} 次作答</small></span>
                <strong>{{ item.masteryScore }}%</strong>
              </div>
              <div class="mastery-track">
                <span :style="{ width: `${item.masteryScore}%` }"></span>
              </div>
            </div>
          </div>
        </article>

        <aside class="weak-panels">
          <article v-for="item in weakCards" :key="item.title" class="weak-card interactive-card">
            <span>优先关注</span>
            <h2>{{ item.title }}</h2>
            <p>{{ item.detail }}</p>
          </article>
          <article v-if="!weakCards.length" class="weak-card interactive-card weak-card-good">
            <span>当前状态</span>
            <h2>{{ hasReport ? '暂无新增薄弱点' : '暂无测验记录' }}</h2>
            <p>{{ hasReport ? '可按建议进入迁移巩固。' : '完成一次测验后可查看学情。' }}</p>
          </article>
        </aside>
      </section>

      <section class="bottom-evaluation-grid">
        <article class="profile-evolution interactive-card">
          <h2>{{ courseId ? '课程诊断' : '画像动态演进' }}</h2>
          <div>
            <span>薄弱环节已更新</span>
            <small>{{ report.weakPoints.join('、') || '暂无新薄弱点' }}</small>
          </div>
          <div>
            <span>错误模式</span>
            <small>{{ report.mistakePatterns.join('、') || '暂无错误模式' }}</small>
          </div>
        </article>

        <article class="next-task-card">
          <div>
            <span>推荐下一步任务</span>
            <h2>{{ primaryFocusPoint }}</h2>
            <p>{{ report.recommendation }}</p>
          </div>
          <a v-if="courseId" class="next-task-button hover-scale" :href="`${hasReport ? '/learning-path' : '/quiz'}?course=${courseId}`">
            {{ hasReport ? '更新学习路径并继续' : '去完成一次练习' }}
          </a>
          <button v-else class="next-task-button hover-scale" type="button" @click="$emit('navigate', hasReport ? 'learningPath' : 'quiz')">
            {{ hasReport ? '更新学习路径并继续' : '去完成一次练习' }}
          </button>
        </article>
      </section>
    </template>
  </div>
</template>

<style scoped>
.evaluation-page {
  min-height: calc(100vh - 72px);
  overflow-x: hidden;
  padding: 48px 72px 60px;
  color: var(--color-ink);
}

.evaluation-hero h1 {
  font-size: clamp(42px, 5vw, 66px);
  margin: 0 0 18px;
}

.evaluation-hero p {
  color: rgba(31, 54, 49, 0.76);
  font-size: 22px;
  line-height: 1.65;
  margin: 0;
  max-width: 940px;
}

.evaluation-hero strong {
  color: #ff6048;
}

.evaluation-hero .hero-success strong {
  color: var(--color-primary);
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 22px;
  margin: 54px 0;
}

.summary-card,
.diagnostic-panel,
.mastery-panel,
.weak-card,
.profile-evolution {
  border: 1px solid rgba(16, 94, 83, 0.1);
  background: rgba(255, 255, 255, 0.78);
  border-radius: 16px;
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px);
}

.summary-card {
  display: grid;
  gap: 10px;
  min-height: 110px;
  min-width: 0;
  padding: 22px;
}

.summary-card span {
  color: rgba(31, 54, 49, 0.58);
  font-size: 13px;
  font-weight: 800;
}

.summary-card strong {
  font-size: 21px;
  overflow-wrap: anywhere;
}

.tone-weak {
  background: rgba(255, 244, 240, 0.84);
  border-color: rgba(255, 104, 77, 0.22);
}

.tone-next {
  background: rgba(230, 248, 244, 0.86);
  border-color: rgba(0, 121, 102, 0.22);
}

.diagnostic-panel {
  padding: 42px 52px;
}

.diagnostic-panel > span {
  color: rgba(31, 54, 49, 0.62);
  display: block;
  font-weight: 800;
  margin-bottom: 28px;
}

.diagnostic-flow {
  align-items: center;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 28px;
  position: relative;
}

.diagnostic-flow::before {
  background: linear-gradient(90deg, transparent, rgba(0, 121, 102, 0.55), transparent);
  content: '';
  height: 2px;
  left: 7%;
  position: absolute;
  right: 7%;
  top: 28px;
}

.flow-step {
  position: relative;
  z-index: 1;
  display: grid;
  justify-items: center;
  gap: 10px;
  text-align: center;
}

.flow-step i {
  border-radius: 999px;
  display: block;
  height: 54px;
  width: 54px;
  background: rgba(31, 54, 49, 0.08);
  border: 1px solid rgba(31, 54, 49, 0.12);
}

.flow-weak i {
  background: rgba(255, 104, 77, 0.12);
  border-color: rgba(255, 104, 77, 0.32);
}

.flow-data i {
  background: rgba(62, 111, 184, 0.12);
  border-color: rgba(62, 111, 184, 0.28);
}

.flow-resource i {
  background: rgba(246, 156, 48, 0.14);
  border-color: rgba(246, 156, 48, 0.3);
}

.flow-active i {
  background: var(--color-primary);
  box-shadow: 0 14px 28px rgba(0, 121, 102, 0.24);
}

.evaluation-loading {
  padding: 28px;
}

.evaluation-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) 0.85fr;
  gap: 34px;
  margin-top: 54px;
}

.mastery-panel {
  min-width: 0;
  padding: 34px;
}

.panel-heading {
  align-items: center;
  display: flex;
  gap: 12px;
}

.panel-heading h2,
.weak-card h2,
.profile-evolution h2,
.next-task-card h2 {
  margin: 0;
  font-size: 26px;
}

.chart-mark {
  display: block;
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: linear-gradient(135deg, #477bbd, #80b5f2);
}

.mastery-panel p,
.weak-card p,
.profile-evolution small,
.next-task-card p {
  color: rgba(31, 54, 49, 0.66);
  line-height: 1.7;
  margin: 18px 0 0;
}

.mastery-bars {
  display: grid;
  gap: 24px;
  margin-top: 34px;
}

.mastery-bar-row > div:first-child {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  margin-bottom: 10px;
}

.mastery-track {
  border-radius: 999px;
  height: 9px;
  background: rgba(31, 54, 49, 0.1);
  overflow: hidden;
}

.mastery-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #477bbd, #0a8e7e);
}

.mastery-bar-row:nth-child(3) .mastery-track span {
  background: linear-gradient(90deg, #ff6048, #ff9a83);
}

.weak-panels {
  display: grid;
  gap: 24px;
}

.weak-card {
  background: rgba(255, 244, 240, 0.82);
  border-color: rgba(255, 104, 77, 0.18);
  padding: 34px;
}

.weak-card span {
  border-radius: 999px;
  background: rgba(255, 104, 77, 0.12);
  color: #ff6048;
  display: inline-block;
  font-weight: 800;
  margin-bottom: 16px;
  padding: 7px 12px;
}

.weak-card-good {
  background: rgba(230, 248, 244, 0.86);
  border-color: rgba(0, 121, 102, 0.22);
}

.weak-card-good span {
  background: rgba(0, 121, 102, 0.12);
  color: var(--color-primary);
}

.bottom-evaluation-grid {
  display: grid;
  grid-template-columns: 0.7fr 1.3fr;
  gap: 34px;
  margin-top: 44px;
}

.profile-evolution {
  padding: 34px;
}

.profile-evolution div {
  border-top: 1px solid rgba(31, 54, 49, 0.08);
  display: grid;
  gap: 6px;
  margin-top: 18px;
  padding-top: 18px;
}

.profile-evolution span {
  font-weight: 800;
}

.next-task-card {
  align-items: center;
  border-radius: 18px;
  background: linear-gradient(135deg, #007966, #069783);
  color: #fff;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 28px;
  padding: 42px;
  box-shadow: 0 24px 44px rgba(0, 121, 102, 0.24);
}

.next-task-card span {
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  display: inline-block;
  font-weight: 800;
  margin-bottom: 18px;
  padding: 7px 12px;
}

.next-task-card p {
  color: rgba(255, 255, 255, 0.78);
}

.next-task-button {
  border: 0;
  border-radius: 14px;
  background: #fff;
  color: var(--color-primary);
  cursor: pointer;
  font-weight: 900;
  min-height: 58px;
  padding: 0 30px;
  white-space: nowrap;
}

@media (max-width: 980px) {
  .evaluation-page {
    padding: 30px 22px;
  }

  .summary-strip,
  .evaluation-grid,
  .bottom-evaluation-grid {
    grid-template-columns: 1fr;
  }

  .diagnostic-flow {
    grid-template-columns: 1fr;
  }

  .diagnostic-flow::before {
    display: none;
  }

  .next-task-card {
    grid-template-columns: 1fr;
  }
}
</style>
