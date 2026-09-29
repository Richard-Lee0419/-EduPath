<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getCourses } from '../api/course';
import { getEvaluationReport } from '../api/evaluation';
import { generateLearningPath, getCurrentLearningPath } from '../api/learningPath';
import { getCurrentProfile } from '../api/profile';
import { listResources } from '../api/resource';
import { waitForAgentTask } from '../api/task';
import type { EvaluationReport, LearningPath, PageKey, Profile } from '../types';

const emit = defineEmits<{
  navigate: [page: PageKey];
}>();

const plannerText = ref('');
const plannerNote = ref('');
const plannerLoading = ref(false);
const plannerProgress = ref(0);
const profile = ref<Profile | null>(null);
const evaluation = ref<EvaluationReport | null>(null);
const learningPath = ref<LearningPath | null>(null);
const resourceCount = ref(0);
const courseNames = ref<string[]>([]);
const courseIds = ref<number[]>([]);
const loadError = ref('');
const todayTaskLabel = '今日学习任务';
const todayTaskLabelCharacters = Array.from(todayTaskLabel);

const openPage = (page: PageKey) => {
  emit('navigate', page);
};

const primaryWeakPoint = computed(() =>
  profile.value?.weakPoints[0]
    || evaluation.value?.weakPoints[0]
    || learningPath.value?.dailyPlan[0]?.theme
    || '',
);

const heroTitle = computed(() => {
  const firstTask = learningPath.value?.dailyPlan[0]?.theme;
  if (firstTask) return `继续推进：${firstTask}`;
  if (primaryWeakPoint.value) return `先理解${primaryWeakPoint.value}，再完成针对性练习`;
  return '等待真实学习数据同步';
});

const heroTitleCharacters = computed(() => Array.from(heroTitle.value));

const heroReason = computed(() => {
  const score = evaluation.value?.overallScore;
  const weak = primaryWeakPoint.value;
  if (!weak) {
    return '完成画像对话、测验或学习路径生成后，这里会展示真实推荐原因。';
  }
  return score
    ? `评估掌握度 ${score}%，系统建议优先巩固「${weak}」。`
    : `根据最新学习画像，系统建议优先巩固「${weak}」。`;
});

const submitPlanner = async () => {
  const text = plannerText.value.trim();
  if (plannerLoading.value) return;
  plannerLoading.value = true;
  plannerProgress.value = 3;
  plannerNote.value = 'PlannerAgent 正在创建真实学习路径任务...';
  try {
    const result = await generateLearningPath({
      courseIds: courseIds.value,
      target: text || (primaryWeakPoint.value ? `巩固${primaryWeakPoint.value}` : '生成学习路径'),
      days: 3,
      dailyMinutes: learningPath.value?.dailyMinutes || 40,
    });
    await waitForAgentTask(result.taskId, (task) => {
      plannerProgress.value = task.progress;
      plannerNote.value = `task_id: ${task.task_id} · ${task.progress}%`;
    });
    const latest = await getCurrentLearningPath();
    learningPath.value = latest.learningPath;
    plannerProgress.value = 100;
    plannerNote.value = `已生成新的学习路径，task_id: ${result.taskId}`;
  } catch (error) {
    plannerNote.value = error instanceof Error ? error.message : '学习路径任务创建失败';
  } finally {
    plannerLoading.value = false;
  }
};

const backgroundItems = computed(() => [
  { label: '当前课程', value: courseNames.value.join('、') || profile.value?.targetCourses.join('、') || '暂无课程数据' },
  { label: '当前薄弱点', value: primaryWeakPoint.value || '暂无薄弱点记录', tone: 'weak' },
  { label: '学习偏好', value: profile.value?.cognitiveStyle.join(' / ') || '画像待补充' },
  { label: '预计时间', value: learningPath.value?.dailyMinutes ? `${learningPath.value.dailyMinutes} 分钟` : '暂无路径时间' },
  { label: '结果置信度', value: profile.value?.confidenceScore ? `${Math.round(profile.value.confidenceScore * 100)}%` : '暂无置信度', tone: 'data' },
]);

const supportCards = computed(() => [
  {
    title: '资源包',
    icon: '▰',
    description: resourceCount.value ? `资源库已同步 ${resourceCount.value} 个个性化资源` : '还没有生成资源，可立即创建资源包',
    action: '查看资源',
    target: 'resources' as PageKey,
    tone: 'resource',
  },
  {
    title: '薄弱点修复',
    icon: '⌁',
    description: primaryWeakPoint.value ? `围绕「${primaryWeakPoint.value}」安排补强路径` : '完成画像或测验后生成补强路径',
    action: '开始练习',
    target: 'learningPath' as PageKey,
    tone: 'weak',
  },
  {
    title: 'AI 辅导',
    icon: '◫',
    description: '互动对话，澄清概念障碍',
    action: '开始对话',
    target: 'tutor' as PageKey,
    tone: 'tutor',
  },
  {
    title: '掌握度检测',
    icon: '▣',
    description: evaluation.value ? `当前综合掌握度 ${evaluation.value.overallScore}%` : '完成测验后自动刷新评估',
    action: '开始测验',
    target: 'evaluation' as PageKey,
    tone: 'data',
  },
]);

const agents = computed(() => [
  { name: 'ProfileAgent', role: '上下文画像', state: profile.value ? '已同步' : '待同步' },
  { name: 'KnowledgeAgent', role: 'RAG 知识检索', state: '可检索' },
  { name: 'PlannerAgent', role: '学习路径规划', state: plannerLoading.value ? `${plannerProgress.value}%` : '可触发', active: plannerLoading.value },
  { name: 'ResourceAgent', role: '资源学习创作', state: resourceCount.value ? '有资源' : '待生成' },
  { name: 'EvaluationAgent', role: '综合评估', state: evaluation.value ? `${evaluation.value.overallScore}%` : '待评估' },
]);

const evidenceCards = computed(() => [
  {
    title: '课程匹配',
    description: learningPath.value?.target || '路径会根据当前课程和画像目标生成。',
    tone: 'data',
  },
  {
    title: '知识点',
    description: primaryWeakPoint.value || '暂无薄弱知识点记录',
    tone: 'teal',
  },
  {
    title: '近期表现',
    description: evaluation.value?.weakPoints.length
      ? `评估识别到 ${evaluation.value.weakPoints.length} 个需巩固知识点。`
      : '暂无明显薄弱点，建议继续完成测验刷新评估。',
    tone: 'weak',
  },
  {
    title: '资源质量',
    description: resourceCount.value ? `已关联 ${resourceCount.value} 个资源记录。` : '生成资源后会关联 RAG 证据和 SafetyAgent 审查。',
    tone: 'resource',
  },
]);

onMounted(async () => {
  try {
    const [profileResult, report, pathResult, resources, courses] = await Promise.all([
      getCurrentProfile(),
      getEvaluationReport(),
      getCurrentLearningPath(),
      listResources({ page: 1, size: 1 }),
      getCourses(),
    ]);
    profile.value = profileResult.profile;
    evaluation.value = report;
    learningPath.value = pathResult.learningPath;
    resourceCount.value = resources.total;
    courseNames.value = courses.map((course) => course.name);
    courseIds.value = courses.map((course) => course.id);
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '学习首页数据加载失败';
  }
});
</script>

<template>
  <div class="learning-home ai-page-bg page-enter">
    <section class="learning-home__hero">
      <div class="learning-home__intro">
        <p class="learning-home__eyebrow" :aria-label="todayTaskLabel">
          <span
            v-for="(character, index) in todayTaskLabelCharacters"
            :key="`${character}-${index}`"
            class="learning-home__split-character learning-home__split-character--eyebrow"
            :style="{ '--character-index': index }"
            aria-hidden="true"
          >{{ character }}</span>
        </p>
        <h1 :aria-label="heroTitle">
          <span
            v-for="(character, index) in heroTitleCharacters"
            :key="`${heroTitle}-${character}-${index}`"
            class="learning-home__split-character learning-home__split-character--task"
            :style="{ '--character-index': index }"
            aria-hidden="true"
          >{{ character === ' ' ? '\u00a0' : character }}</span>
        </h1>
        <p class="learning-home__reason">推荐原因：{{ heroReason }}</p>
        <p v-if="loadError" class="learning-home__planner-note">{{ loadError }}</p>

        <div class="learning-home__actions">
          <button class="learning-home__primary hover-scale" type="button" @click="openPage('tutor')">
            开始今日学习
          </button>
          <button class="learning-home__secondary hover-scale" type="button" @click="openPage('resources')">
            生成资源包
          </button>
        </div>

        <form class="learning-home__planner" @submit.prevent="submitPlanner">
          <span aria-hidden="true">✦</span>
          <input v-model="plannerText" type="text" placeholder="让 PlannerAgent 帮我本次学习..." />
          <button type="submit" :disabled="plannerLoading" aria-label="提交给 PlannerAgent">→</button>
        </form>
        <p v-if="plannerNote" class="learning-home__planner-note">{{ plannerNote }}</p>
      </div>

      <aside class="learning-home__context-card interactive-card" aria-label="学习背景">
        <h2>学习背景</h2>
        <dl>
          <div v-for="item in backgroundItems" :key="item.label">
            <dt>{{ item.label }}</dt>
            <dd :class="item.tone ? `is-${item.tone}` : ''">{{ item.value }}</dd>
          </div>
        </dl>
      </aside>
    </section>

    <section class="learning-home__featured interactive-card" @click="openPage('tutor')">
      <div>
        <span class="learning-home__pill">深度学习任务</span>
        <h2>{{ primaryWeakPoint }}</h2>
        <p>{{ learningPath?.adjustmentStrategy || '系统会结合画像、评估和 RAG 证据安排下一步学习任务。' }}</p>
        <div class="learning-home__meta">
          <span>ResourceAgent</span>
          <span>KnowledgeAgent</span>
          <span>45m</span>
        </div>
      </div>
      <button class="learning-home__work-button" type="button" @click.stop="openPage('tutor')">
        进入工作区
      </button>
    </section>

    <section class="learning-home__support-grid" aria-label="辅助学习入口">
      <article
        v-for="card in supportCards"
        :key="card.title"
        class="learning-home__support-card interactive-card"
        :class="`is-${card.tone}`"
        @click="openPage(card.target)"
      >
        <span class="learning-home__support-icon">{{ card.icon }}</span>
        <h3>{{ card.title }}</h3>
        <p>{{ card.description }}</p>
        <button type="button" @click.stop="openPage(card.target)">{{ card.action }} →</button>
      </article>
    </section>

    <section class="learning-home__agent-section">
      <h2>Studio 智能体协作链路 <span aria-hidden="true">⚡</span></h2>
      <div class="learning-home__agent-rail" aria-label="多智能体运行流程">
        <template v-for="(agent, index) in agents" :key="agent.name">
          <article class="learning-home__agent-node" :class="{ 'is-active agent-active-pulse': agent.active }">
            <span class="learning-home__agent-dot"></span>
            <strong>{{ agent.name }}</strong>
            <small>{{ agent.role }}</small>
            <em>{{ agent.state }}</em>
          </article>
          <span v-if="index < agents.length - 1" class="learning-home__agent-link"></span>
        </template>
      </div>
    </section>

    <section class="learning-home__evidence">
      <h2>为什么 EduPath 推荐此内容</h2>
      <div class="learning-home__evidence-grid">
        <article
          v-for="item in evidenceCards"
          :key="item.title"
          class="learning-home__evidence-card interactive-card"
          :class="`is-${item.tone}`"
        >
          <span></span>
          <h3>{{ item.title }}</h3>
          <p>{{ item.description }}</p>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.learning-home {
  display: grid;
  gap: clamp(24px, 3vw, 44px);
  padding: clamp(18px, 3vw, 42px) clamp(24px, 6vw, 88px) 64px;
  color: var(--ink);
}

.learning-home__hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 360px);
  align-items: start;
  gap: clamp(24px, 4.5vw, 72px);
}

.learning-home__intro {
  max-width: 760px;
  padding-top: clamp(4px, 1vw, 14px);
}

.learning-home__eyebrow {
  display: flex;
  flex-wrap: wrap;
  margin: 0 0 20px;
  color: var(--teal);
  font-size: clamp(1.7rem, 4vw, 3.5rem);
  font-weight: 900;
  letter-spacing: 0;
}

.learning-home__intro h1 {
  display: flex;
  flex-wrap: wrap;
  max-width: 820px;
  margin: 0;
  color: var(--teal-dark);
  font-size: clamp(1.85rem, 3.4vw, 3.5rem);
  font-weight: 900;
  line-height: 1.22;
}

.learning-home__split-character {
  display: inline-block;
  flex: 0 0 auto;
  opacity: 0;
  transform: translateY(0.38em);
  will-change: opacity, transform;
  animation: learningHomeCharacterReveal 480ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.learning-home__split-character--eyebrow {
  animation-delay: calc(var(--character-index) * 55ms);
}

.learning-home__split-character--task {
  animation-delay: calc(280ms + var(--character-index) * 30ms);
}

@keyframes learningHomeCharacterReveal {
  from {
    opacity: 0;
    transform: translateY(0.38em);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.learning-home__reason {
  margin: 18px 0 0;
  color: rgba(24, 48, 43, 0.72);
  font-size: clamp(1rem, 1.4vw, 1.18rem);
  line-height: 1.8;
}

.learning-home__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-top: 34px;
}

.learning-home__primary,
.learning-home__secondary,
.learning-home__work-button,
.learning-home__planner button {
  cursor: pointer;
  border: 0;
  font: inherit;
  font-weight: 800;
}

.learning-home__primary {
  border-radius: 999px;
  padding: 15px 30px;
  background: linear-gradient(135deg, #0d8a79, #006f62);
  color: #fff;
  box-shadow: 0 20px 34px rgba(11, 119, 104, 0.18);
}

.learning-home__secondary {
  border: 1px solid rgba(8, 112, 98, 0.42);
  border-radius: 999px;
  padding: 14px 30px;
  background: rgba(255, 255, 255, 0.74);
  color: var(--teal-dark);
}

.learning-home__planner {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 14px;
  max-width: 640px;
  margin-top: 34px;
  border: 1px solid rgba(18, 128, 111, 0.13);
  border-radius: 999px;
  padding: 10px 10px 10px 24px;
  background: rgba(255, 255, 255, 0.75);
  box-shadow: 0 22px 52px rgba(34, 65, 57, 0.1);
  backdrop-filter: blur(18px);
}

.learning-home__planner span {
  color: var(--teal);
  font-size: 1.2rem;
}

.learning-home__planner input {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
}

.learning-home__planner input::placeholder {
  color: rgba(33, 55, 50, 0.45);
}

.learning-home__planner button {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  border-radius: 50%;
  background: rgba(14, 132, 116, 0.12);
  color: var(--teal-dark);
  font-size: 1.35rem;
}

.learning-home__planner-note {
  margin: 12px 0 0 24px;
  color: rgba(24, 48, 43, 0.62);
  font-size: 0.95rem;
}

.learning-home__context-card {
  min-width: 0;
  border-radius: 28px;
  padding: 30px;
  background:
    radial-gradient(circle at 92% 8%, rgba(22, 151, 133, 0.1), transparent 34%),
    rgba(255, 255, 255, 0.8);
}

.learning-home__context-card h2 {
  margin: 0 0 24px;
  color: var(--ink);
  font-size: 1.55rem;
}

.learning-home__context-card dl {
  display: grid;
  gap: 18px;
  margin: 0;
}

.learning-home__context-card div {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid rgba(28, 65, 57, 0.09);
  padding-bottom: 14px;
}

.learning-home__context-card div:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.learning-home__context-card dt {
  color: rgba(24, 48, 43, 0.55);
}

.learning-home__context-card dd {
  margin: 0;
  color: var(--ink);
  font-weight: 800;
  overflow-wrap: anywhere;
}

.learning-home__context-card .is-weak {
  border-radius: 999px;
  padding: 4px 10px;
  background: rgba(255, 108, 84, 0.12);
  color: #e4573e;
}

.learning-home__context-card .is-data {
  color: #376fbb;
}

.learning-home__featured {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 28px;
  border-radius: 30px;
  padding: clamp(28px, 4vw, 50px);
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.9), rgba(255, 252, 246, 0.74)),
    radial-gradient(circle at 82% 28%, rgba(226, 132, 0, 0.13), transparent 24%);
}

.learning-home__pill {
  display: inline-flex;
  border-radius: 999px;
  padding: 7px 13px;
  background: rgba(18, 128, 111, 0.12);
  color: var(--teal);
  font-size: 0.82rem;
  font-weight: 900;
}

.learning-home__featured h2 {
  margin: 18px 0 12px;
  color: var(--ink);
  font-size: clamp(1.7rem, 3vw, 2.55rem);
  overflow-wrap: anywhere;
}

.learning-home__featured p {
  max-width: 720px;
  margin: 0;
  color: rgba(24, 48, 43, 0.68);
  font-size: 1.05rem;
  line-height: 1.8;
}

.learning-home__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 26px;
  color: rgba(24, 48, 43, 0.58);
  font-size: 0.9rem;
  font-weight: 800;
}

.learning-home__meta span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.learning-home__meta span::before {
  content: "";
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--teal);
}

.learning-home__meta span:first-child::before {
  background: #e58a00;
}

.learning-home__work-button {
  border-radius: 999px;
  padding: 14px 24px;
  background: linear-gradient(135deg, #0d8a79, #006f62);
  color: #fff;
  white-space: nowrap;
}

.learning-home__support-grid,
.learning-home__evidence-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 24px;
}

.learning-home__support-card,
.learning-home__evidence-card {
  min-width: 0;
  cursor: pointer;
  border-radius: 24px;
  padding: 26px;
}

.learning-home__support-icon {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 50%;
  margin-bottom: 22px;
  font-size: 1.3rem;
  font-weight: 900;
}

.learning-home__support-card h3,
.learning-home__evidence-card h3 {
  margin: 0;
  color: var(--ink);
  font-size: 1.25rem;
}

.learning-home__support-card p,
.learning-home__evidence-card p {
  margin: 14px 0 0;
  color: rgba(24, 48, 43, 0.62);
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.learning-home__support-card button {
  margin-top: 24px;
  border: 0;
  background: transparent;
  color: var(--teal);
  cursor: pointer;
  font: inherit;
  font-weight: 900;
}

.learning-home__support-card.is-resource .learning-home__support-icon {
  background: rgba(226, 132, 0, 0.13);
  color: #df7b12;
}

.learning-home__support-card.is-weak .learning-home__support-icon {
  background: rgba(255, 108, 84, 0.13);
  color: #e4573e;
}

.learning-home__support-card.is-tutor .learning-home__support-icon {
  background: rgba(18, 128, 111, 0.13);
  color: var(--teal);
}

.learning-home__support-card.is-data .learning-home__support-icon {
  background: rgba(67, 115, 190, 0.13);
  color: #376fbb;
}

.learning-home__agent-section,
.learning-home__evidence {
  display: grid;
  gap: 24px;
}

.learning-home__agent-section h2,
.learning-home__evidence h2 {
  margin: 0;
  color: var(--ink);
  font-size: clamp(1.35rem, 2.2vw, 2rem);
  text-align: center;
}

.learning-home__agent-rail {
  display: grid;
  grid-template-columns: minmax(130px, 1fr) 0.6fr minmax(130px, 1fr) 0.6fr minmax(130px, 1fr) 0.6fr minmax(130px, 1fr) 0.6fr minmax(130px, 1fr);
  align-items: center;
  gap: 12px;
}

.learning-home__agent-node {
  display: grid;
  min-width: 0;
  min-height: 78px;
  place-items: center;
  border: 1px solid rgba(24, 48, 43, 0.1);
  border-radius: 999px;
  padding: 14px 16px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 16px 34px rgba(36, 71, 62, 0.07);
  text-align: center;
}

.learning-home__agent-node.is-active {
  border-color: rgba(18, 128, 111, 0.28);
  background: rgba(210, 239, 232, 0.95);
  color: var(--teal-dark);
}

.learning-home__agent-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: rgba(24, 48, 43, 0.18);
}

.learning-home__agent-node.is-active .learning-home__agent-dot {
  background: var(--teal);
}

.learning-home__agent-node strong {
  color: inherit;
  font-size: 0.86rem;
  overflow-wrap: anywhere;
}

.learning-home__agent-node small {
  color: rgba(24, 48, 43, 0.55);
  font-size: 0.78rem;
}

.learning-home__agent-node em {
  color: var(--teal);
  font-size: 0.76rem;
  font-style: normal;
  font-weight: 900;
  overflow-wrap: anywhere;
}

.learning-home__agent-link {
  display: block;
  width: 62%;
  min-width: 38px;
  justify-self: center;
  border-top: 2px dashed rgba(24, 48, 43, 0.22);
}

.learning-home__evidence-card {
  position: relative;
  overflow: hidden;
}

.learning-home__evidence-card > span {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 4px;
  background: var(--teal);
}

.learning-home__evidence-card.is-data > span {
  background: #376fbb;
}

.learning-home__evidence-card.is-weak > span {
  background: #e4573e;
}

.learning-home__evidence-card.is-resource > span {
  background: #df7b12;
}

@media (max-width: 1100px) {
  .learning-home__hero,
  .learning-home__featured {
    grid-template-columns: 1fr;
  }

  .learning-home__support-grid,
  .learning-home__evidence-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .learning-home__agent-rail {
    grid-template-columns: 1fr;
  }

  .learning-home__agent-link {
    width: 0;
    height: 24px;
    border-top: 0;
    border-left: 2px dashed rgba(24, 48, 43, 0.22);
  }
}

@media (max-width: 680px) {
  .learning-home {
    padding-inline: 18px;
  }

  .learning-home__support-grid,
  .learning-home__evidence-grid {
    grid-template-columns: 1fr;
  }

  .learning-home__context-card div {
    display: grid;
  }
}

@media (prefers-reduced-motion: reduce) {
  .learning-home__split-character {
    opacity: 1;
    transform: none;
    animation: none;
  }
}
</style>
