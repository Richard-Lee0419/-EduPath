<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getEvaluationReport } from '../api/evaluation';
import { getCurrentProfile } from '../api/profile';
import { listResources } from '../api/resource';
import TaskCenter from './TaskCenter.vue';
import type { EvaluationReport, Profile, ResourceCard, User, PageKey } from '../types';

const props = defineProps<{
  user: User;
}>();

const emit = defineEmits<{
  navigate: [page: PageKey];
  logout: [];
  exit: [];
}>();

const profile = ref<Profile | null>(null);
const report = ref<EvaluationReport | null>(null);
const resourceItems = ref<ResourceCard[]>([]);
const resourceTotal = ref(0);
const errorMessage = ref('');
const accountAccessNotice = ref('');
const generationRecordsSection = ref<HTMLElement | null>(null);

const canViewTeacherInsights = computed(() => props.user.role === 'teacher' || props.user.role === 'admin');

const openTeacherInsights = () => {
  if (!canViewTeacherInsights.value) {
    accountAccessNotice.value = '当前账号暂无教师端权限，请使用教师或管理员账号登录。';
    return;
  }
  accountAccessNotice.value = '';
  emit('navigate', 'teacherInsights');
};

const scrollToGenerationRecords = () => {
  generationRecordsSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' });
};

const profileItems = computed(() => [
  { label: '当前目标', value: profile.value?.learningGoal ?? '等待画像加载', tone: 'green' },
  { label: '薄弱知识点', value: profile.value?.weakPoints.join(' / ') || '暂无薄弱点', tone: 'orange' },
  { label: '学习偏好', value: profile.value?.cognitiveStyle.join(' / ') || '等待分析', tone: 'blue' },
  { label: '学习节奏', value: profile.value?.learningPace ?? 'medium', tone: 'cyan' },
  { label: '推荐资源偏好', value: profile.value?.resourcePreference.join(' / ') || '等待推荐', tone: 'coral' },
]);

const records = computed(() => {
  const resourceRecords = resourceItems.value.slice(0, 3).map((resource, index) => ({
    title: resource.title,
    detail: [resource.subtitle, resource.minutes ? `${resource.minutes} 分钟` : ''].filter(Boolean).join(' · '),
    time: index === 0 ? '最新生成' : '资源库',
    tone: index === 0 ? 'note' : 'doc',
  }));
  const evaluationRecord = report.value?.recommendation
    ? [{
      title: '学习评估建议',
      detail: report.value.recommendation,
      time: '实时同步',
      tone: 'chat',
    }]
    : [];
  return [...resourceRecords, ...evaluationRecord];
});

const dataStats = computed(() => [
  { label: '资源数量', value: `${resourceTotal.value} 个`, delta: '真实资源库' },
  { label: '掌握度', value: `${report.value?.overallScore ?? 0}%`, delta: '来自评估报告' },
  { label: '薄弱点', value: `${profile.value?.weakPoints.length ?? 0} 个`, delta: '画像聚类' },
  { label: '登录身份', value: props.user.role, delta: '当前 JWT 登录态' },
]);

const sidebarItems: Array<{ label: string; page: PageKey }> = [
  { label: '学习首页', page: 'learningHome' },
  { label: '对话画像', page: 'profile' },
  { label: '资源生成', page: 'resources' },
  { label: '资源中心', page: 'resourceCenter' },
  { label: '学习路径', page: 'learningPath' },
  { label: '知识库', page: 'knowledgeBase' },
  { label: '智能辅导', page: 'tutor' },
  { label: '练习测试', page: 'quiz' },
  { label: '学习评估', page: 'evaluation' },
];

const profileCompletion = computed(() => {
  const item = profile.value;
  if (!item) return 0;
  const fields = [
    item.learningGoal,
    item.weakPoints.length,
    item.resourcePreference.length,
    item.cognitiveStyle.length,
    item.learningPace,
  ];
  return Math.round((fields.filter(Boolean).length / fields.length) * 100);
});
const statusSummary = computed(() => {
  const weak = profile.value?.weakPoints[0] || '当前课程';
  const recommendation = report.value?.recommendation || '继续完成学习任务，系统会同步刷新画像、路径和资源推荐。';
  return `当前重点是「${weak}」。${recommendation}`;
});
const settingRows = computed(() => [
  ['内容呈现方式', profile.value?.resourcePreference.join(' / ') || '暂无画像偏好'],
  ['学习节奏', profile.value?.learningPace || '暂无画像节奏'],
  ['登录账号', props.user.email || props.user.username],
  ['登录角色', props.user.role],
]);

onMounted(async () => {
  try {
    const [profileResult, evaluationReport, resources] = await Promise.all([
      getCurrentProfile(),
      getEvaluationReport(),
      listResources({ page: 1, size: 3 }),
    ]);
    profile.value = profileResult.profile;
    report.value = evaluationReport;
    resourceItems.value = resources.items;
    resourceTotal.value = resources.total;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '个人中心数据加载失败';
  }
});
</script>

<template>
  <div class="user-passport-page page-enter">
    <aside class="passport-sidebar">
      <button class="sidebar-exit hover-scale" type="button" aria-label="返回上一页" @click="$emit('exit')">
        <svg viewBox="0 0 28 28">
          <path d="M17 6 9 14l8 8M10 14h14" />
        </svg>
      </button>
      <nav>
        <button
          v-for="item in sidebarItems"
          :key="item.label"
          type="button"
          class="sidebar-item"
          @click="$emit('navigate', item.page)"
        >
          <span class="sidebar-icon"></span>
          {{ item.label }}
        </button>
        <button type="button" class="sidebar-item" @click="scrollToGenerationRecords">
          <span class="sidebar-icon sidebar-icon--history"></span>
          生成记录
        </button>
      </nav>
    </aside>

    <main class="passport-main">
      <section class="passport-title">
        <h1>个人中心</h1>
        <p>掌握学习全貌，AI 伴你成长</p>
        <p v-if="errorMessage" class="passport-error">{{ errorMessage }}</p>
      </section>

      <section class="identity-card interactive-card">
        <div class="portrait">
          {{ user.name.slice(0, 1) }}
        </div>
        <div class="identity-name">
          <div>
            <h2>{{ user.name }}</h2>
            <span>{{ user.role }}</span>
          </div>
          <p>当前专注课程</p>
          <strong>{{ profile?.targetCourses.join('、') || '暂无课程偏好' }}</strong>
        </div>

        <div class="profile-score">
          <span>学习画像完整度</span>
          <strong>{{ profileCompletion }}%</strong>
          <div class="score-bar"><i></i></div>
          <em>{{ profileCompletion >= 80 ? '完整' : '待补充' }}</em>
        </div>

        <div class="status-summary">
          <span>学习状态总结</span>
          <p>{{ statusSummary }}</p>
        </div>
      </section>

      <section class="profile-overview interactive-card">
        <h2>学习画像概览</h2>
        <div class="profile-item-grid">
          <article v-for="item in profileItems" :key="item.label" :class="`profile-item tone-${item.tone}`">
            <span></span>
            <strong>{{ item.label }}</strong>
            <p>{{ item.value }}</p>
          </article>
        </div>
      </section>

      <section class="passport-dashboard">
        <article class="recent-card interactive-card">
          <div class="card-heading">
            <h2>近期学习记录</h2>
            <button type="button" @click="scrollToGenerationRecords">查看生成记录</button>
          </div>
          <div class="record-list">
            <article v-for="record in records" :key="record.title" class="record-row" :class="`record-${record.tone}`">
              <span class="record-icon"></span>
              <div>
                <strong>{{ record.title }}</strong>
                <p>{{ record.detail }}</p>
              </div>
              <time>{{ record.time }}</time>
            </article>
            <p v-if="!records.length" class="record-empty">暂无近期资源或评估记录。</p>
          </div>
        </article>

        <article class="data-card interactive-card">
          <div class="card-heading">
            <h2>学习数据概览</h2>
          </div>
          <div class="stats-row">
            <article v-for="stat in dataStats" :key="stat.label">
              <span>{{ stat.label }}</span>
              <strong>{{ stat.value }}</strong>
              <small>{{ stat.delta }}</small>
            </article>
          </div>
          <p class="data-source-note">趋势图接口尚未提供，当前仅展示已接入接口的实时汇总。</p>
        </article>
      </section>

      <section ref="generationRecordsSection" class="generation-records-card interactive-card">
        <TaskCenter embedded />
      </section>

      <section class="passport-settings">
        <article class="settings-card interactive-card">
          <h2>环境偏好设置</h2>
          <div v-for="[label, value] in settingRows" :key="label" class="setting-row">
            <span>{{ label }}</span>
            <strong>{{ value }}</strong>
          </div>
        </article>

        <article class="settings-card interactive-card">
          <h2>账号操作</h2>
          <div class="setting-row teacher-entry">
            <span>教师端页面</span>
            <button class="teacher-insights-button" type="button" @click="openTeacherInsights">班级洞察</button>
          </div>
          <div class="setting-row danger">
            <span>退出登录</span>
            <button type="button" @click="$emit('logout')">退出</button>
          </div>
          <p v-if="accountAccessNotice" class="account-access-notice" role="alert" aria-live="polite">
            {{ accountAccessNotice }}
          </p>
        </article>
      </section>
    </main>
  </div>
</template>

<style scoped>
.user-passport-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  color: var(--color-ink);
  background:
    radial-gradient(circle at 83% 10%, rgba(166, 230, 219, 0.34), transparent 28%),
    radial-gradient(circle at 10% 92%, rgba(171, 232, 224, 0.42), transparent 30%),
    linear-gradient(135deg, #f7fbf8 0%, #fffdf8 48%, #eef9f5 100%);
  overflow: hidden;
}

.passport-sidebar {
  background: rgba(235, 249, 246, 0.72);
  border-right: 1px solid rgba(16, 94, 83, 0.08);
  padding: 34px 28px;
}

.sidebar-exit {
  align-items: center;
  border: 0;
  background: transparent;
  color: var(--color-ink);
  cursor: pointer;
  display: flex;
  height: 42px;
  margin-bottom: 62px;
  width: 42px;
}

.sidebar-exit svg {
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2.2;
}

.passport-sidebar nav {
  display: grid;
  gap: 22px;
}

.sidebar-item {
  align-items: center;
  border: 0;
  border-radius: 14px;
  background: transparent;
  color: var(--color-ink);
  cursor: pointer;
  display: flex;
  font-size: 18px;
  font-weight: 800;
  gap: 16px;
  padding: 12px;
  text-align: left;
  transition: background 180ms ease, color 180ms ease, transform 180ms ease;
}

.sidebar-item:hover {
  background: rgba(0, 121, 102, 0.1);
  color: var(--color-primary);
  transform: translateX(4px);
}

.sidebar-icon {
  border: 2px solid currentColor;
  border-radius: 6px;
  display: block;
  height: 22px;
  width: 22px;
}

.sidebar-icon--history {
  border-radius: 999px;
  position: relative;
}

.sidebar-icon--history::after {
  border-left: 2px solid currentColor;
  border-top: 2px solid currentColor;
  content: '';
  height: 6px;
  left: 9px;
  position: absolute;
  top: 4px;
  width: 5px;
}

.passport-main {
  max-height: 100vh;
  overflow-y: auto;
  padding: 52px 34px 70px;
}

.passport-title {
  margin: 0 auto 34px;
  max-width: 980px;
}

.passport-title h1 {
  font-size: 48px;
  margin: 0 0 12px;
}

.passport-title p {
  color: rgba(31, 54, 49, 0.62);
  font-size: 18px;
  margin: 0;
}

.passport-error,
.record-empty,
.data-source-note {
  color: #df5b45;
  font-weight: 800;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.record-empty,
.data-source-note {
  color: rgba(31, 54, 49, 0.62);
  margin: 0;
}

.identity-card,
.profile-overview,
.recent-card,
.data-card,
.settings-card,
.generation-records-card {
  border: 1px solid rgba(16, 94, 83, 0.1);
  background: rgba(255, 255, 255, 0.78);
  border-radius: 18px;
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px);
}

.generation-records-card {
  margin: 0 auto 34px;
  max-width: 980px;
  padding: 28px;
  scroll-margin-top: 24px;
}

.identity-card {
  align-items: center;
  display: grid;
  grid-template-columns: 132px minmax(0, 1fr) 220px 260px;
  gap: 30px;
  margin: 0 auto 26px;
  max-width: 980px;
  padding: 34px;
}

.portrait {
  align-items: center;
  background: linear-gradient(135deg, #d9e7df, #f6f1e8);
  border-radius: 999px;
  display: flex;
  font-size: 48px;
  font-weight: 900;
  height: 112px;
  justify-content: center;
  width: 112px;
}

.identity-name h2 {
  display: inline-block;
  font-size: 34px;
  margin: 0 14px 18px 0;
  overflow-wrap: anywhere;
}

.identity-name span {
  border-radius: 999px;
  background: rgba(0, 121, 102, 0.15);
  color: var(--color-primary);
  font-weight: 800;
  padding: 8px 12px;
}

.identity-name p,
.profile-score span,
.status-summary span,
.record-row p,
.setting-row span {
  color: rgba(31, 54, 49, 0.58);
  margin: 0 0 8px;
}

.profile-score,
.status-summary {
  border-left: 1px solid rgba(31, 54, 49, 0.1);
  min-height: 104px;
  padding-left: 28px;
}

.profile-score strong {
  color: var(--color-primary);
  display: block;
  font-size: 44px;
}

.score-bar {
  border-radius: 999px;
  background: rgba(31, 54, 49, 0.1);
  height: 8px;
  margin: 8px 0;
  overflow: hidden;
}

.score-bar i {
  background: linear-gradient(90deg, #00a68d, #057d6b);
  border-radius: inherit;
  display: block;
  height: 100%;
  width: 92%;
}

.profile-score em {
  color: var(--color-primary);
  font-style: normal;
  font-weight: 800;
}

.status-summary p {
  color: rgba(31, 54, 49, 0.72);
  line-height: 1.7;
  margin: 0;
}

.profile-overview {
  margin: 0 auto 26px;
  max-width: 980px;
  padding: 28px;
}

.profile-overview h2,
.card-heading h2,
.settings-card h2 {
  font-size: 24px;
  margin: 0 0 22px;
}

.profile-item-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 0;
  border: 1px solid rgba(31, 54, 49, 0.08);
  border-radius: 14px;
  overflow: hidden;
}

.profile-item {
  display: grid;
  gap: 8px;
  min-height: 118px;
  padding: 18px;
  border-right: 1px solid rgba(31, 54, 49, 0.08);
}

.profile-item:last-child {
  border-right: 0;
}

.profile-item span {
  border-radius: 8px;
  height: 24px;
  width: 24px;
}

.profile-item p {
  color: rgba(31, 54, 49, 0.62);
  line-height: 1.55;
  margin: 0;
}

.tone-green span { background: #82d897; }
.tone-orange span { background: #f6a34b; }
.tone-blue span { background: #80b7ee; }
.tone-cyan span { background: #56bdd0; }
.tone-coral span { background: #ff825d; }

.passport-dashboard,
.passport-settings {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 26px;
  margin: 0 auto 34px;
  max-width: 980px;
}

.recent-card,
.data-card,
.settings-card {
  padding: 28px;
}

.card-heading {
  align-items: center;
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.card-heading button {
  border: 0;
  background: transparent;
  color: var(--color-primary);
  cursor: pointer;
  font-weight: 800;
}

.period-tabs {
  display: flex;
  gap: 8px;
}

.period-tabs span {
  border-radius: 8px;
  background: rgba(31, 54, 49, 0.06);
  color: rgba(31, 54, 49, 0.66);
  font-size: 12px;
  font-weight: 800;
  padding: 8px 10px;
}

.period-tabs span:first-child {
  background: rgba(0, 121, 102, 0.14);
  color: var(--color-primary);
}

.record-list {
  display: grid;
  gap: 12px;
}

.record-row {
  align-items: center;
  border: 1px solid rgba(31, 54, 49, 0.08);
  border-radius: 13px;
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  gap: 14px;
  padding: 14px;
}

.record-row > div,
.setting-row strong,
.status-summary,
.identity-name {
  min-width: 0;
}

.record-icon {
  border-radius: 12px;
  display: block;
  height: 40px;
  width: 40px;
}

.record-video .record-icon { background: #ff7f2a; }
.record-doc .record-icon { background: #75c86c; }
.record-note .record-icon { background: #52aeea; }
.record-chat .record-icon { background: #7b67df; }

.record-row strong,
.record-row time {
  color: var(--color-ink);
}

.record-row p {
  font-size: 13px;
  overflow-wrap: anywhere;
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(118px, 1fr));
  gap: 12px;
}

.stats-row article {
  border-radius: 12px;
  background: rgba(31, 54, 49, 0.04);
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 16px 12px;
  text-align: center;
}

.stats-row strong {
  color: var(--color-primary);
  font-size: 26px;
}

.stats-row small {
  color: rgba(0, 121, 102, 0.74);
  overflow-wrap: anywhere;
}

.setting-row {
  align-items: center;
  border-bottom: 1px solid rgba(31, 54, 49, 0.08);
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 0;
}

.setting-row:last-child {
  border-bottom: 0;
}

.setting-row strong {
  color: var(--color-primary);
}

.setting-row button {
  border: 0;
  background: transparent;
  color: #ff6048;
  cursor: pointer;
  font-weight: 800;
}

.setting-row .teacher-insights-button {
  min-height: 38px;
  border: 1px solid rgba(0, 121, 102, 0.18);
  border-radius: 999px;
  background: rgba(0, 121, 102, 0.1);
  color: var(--color-primary);
  padding: 8px 15px;
  transition: background 180ms ease, color 180ms ease, transform 180ms ease;
}

.setting-row .teacher-insights-button:hover {
  background: var(--color-primary);
  color: #fff;
  transform: translateY(-1px);
}

.account-access-notice {
  border: 1px solid rgba(223, 91, 69, 0.18);
  border-radius: 12px;
  background: rgba(255, 240, 236, 0.78);
  color: #b84836;
  font-size: 13px;
  font-weight: 750;
  line-height: 1.55;
  margin: 14px 0 0;
  padding: 10px 12px;
}

@media (max-width: 1180px) {
  .user-passport-page {
    grid-template-columns: 1fr;
  }

  .passport-sidebar {
    position: static;
  }

  .passport-sidebar nav {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .identity-card,
  .passport-dashboard,
  .passport-settings,
  .profile-item-grid {
    grid-template-columns: 1fr;
  }

  .profile-item {
    border-right: 0;
    border-bottom: 1px solid rgba(31, 54, 49, 0.08);
  }
}

@media (max-width: 720px) {
  .passport-main {
    padding: 28px 18px 46px;
  }

  .passport-sidebar nav,
  .stats-row {
    grid-template-columns: 1fr;
  }
}
</style>
