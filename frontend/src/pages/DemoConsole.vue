<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { getCourses } from '../api/course';
import { getEvaluationReport } from '../api/evaluation';
import { searchKnowledgeBase } from '../api/kb';
import { getCurrentLearningPath } from '../api/learningPath';
import { getCurrentProfile } from '../api/profile';
import { listResources } from '../api/resource';
import type { PageKey } from '../types';

const emit = defineEmits<{ navigate: [page: PageKey] }>();

type ReadinessState = 'ready' | 'action' | 'checking';
type DemoSession = { startedAt: number; completed: number[] };

const STORAGE_KEY = 'edupath_competition_demo_session';
const checking = ref(false);
const lastCheckedAt = ref('');
const now = ref(Date.now());
const readiness = ref<Array<{ label: string; detail: string; state: ReadinessState }>>([
  { label: '双课程与知识库', detail: '等待预检', state: 'checking' },
  { label: '动态学习画像', detail: '等待预检', state: 'checking' },
  { label: '个性化资源', detail: '等待预检', state: 'checking' },
  { label: '学习路径', detail: '等待预检', state: 'checking' },
  { label: '测验与评估', detail: '等待预检', state: 'checking' },
]);

const demoSteps: Array<{ title: string; summary: string; page: PageKey; proof: string }> = [
  { title: '画像理解', summary: '输入两周学习目标，让 ProfileAgent 抽取薄弱点、偏好和节奏。', page: 'profile', proof: '展示 8+ 维画像与版本更新原因' },
  { title: '证据检索', summary: '在课程知识库检索递归或 Cache，观察混合召回与结构证据。', page: 'knowledgeBase', proof: '展示来源、分数、MMR 与标题路径' },
  { title: '多智能体生成', summary: '选择讲义、导图和代码实验，观察 Agent 协作轨道。', page: 'resources', proof: '展示 task_id、进度和 SafetyAgent' },
  { title: '路径编排', summary: '把画像和资源组合成可解释的每日学习路径。', page: 'learningPath', proof: '展示节点原因、时间预算和调整策略' },
  { title: '辅导与练习', summary: '进行基于证据的追问，并完成一次专项测验。', page: 'tutor', proof: '回答含引用；随后进入专项练习' },
  { title: '评估回流', summary: '查看错因、掌握度和自动补救路径，闭合学习循环。', page: 'evaluation', proof: '展示画像更新、根因与补救任务' },
];

const loadSession = (): DemoSession => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as DemoSession;
      if (Number.isFinite(parsed.startedAt) && Array.isArray(parsed.completed)) return parsed;
    }
  } catch {
    // Ignore invalid local presentation state; business data is never touched.
  }
  return { startedAt: 0, completed: [] };
};

const session = ref<DemoSession>(loadSession());
let clock: ReturnType<typeof setInterval> | undefined;

const persistSession = () => localStorage.setItem(STORAGE_KEY, JSON.stringify(session.value));
const completedCount = computed(() => session.value.completed.length);
const progressPercent = computed(() => Math.round((completedCount.value / demoSteps.length) * 100));
const elapsedSeconds = computed(() => session.value.startedAt ? Math.max(0, Math.floor((now.value - session.value.startedAt) / 1000)) : 0);
const elapsedLabel = computed(() => {
  const minutes = Math.floor(elapsedSeconds.value / 60).toString().padStart(2, '0');
  const seconds = (elapsedSeconds.value % 60).toString().padStart(2, '0');
  return `${minutes}:${seconds}`;
});
const readyCount = computed(() => readiness.value.filter((item) => item.state === 'ready').length);

const startDemo = () => {
  session.value = { startedAt: Date.now(), completed: [] };
  now.value = Date.now();
  persistSession();
};

const resetGuide = () => {
  session.value = { startedAt: 0, completed: [] };
  localStorage.removeItem(STORAGE_KEY);
};

const toggleComplete = (index: number) => {
  const completed = new Set(session.value.completed);
  if (completed.has(index)) completed.delete(index);
  else completed.add(index);
  session.value = { ...session.value, completed: [...completed].sort((a, b) => a - b) };
  persistSession();
};

const enterStep = (index: number) => {
  if (!session.value.startedAt) startDemo();
  emit('navigate', demoSteps[index].page);
};

const refreshReadiness = async () => {
  checking.value = true;
  readiness.value = readiness.value.map((item) => ({ ...item, detail: '正在检查真实接口…', state: 'checking' }));
  const results = await Promise.allSettled([
    getCourses(),
    getCurrentProfile(),
    listResources({ page: 1, size: 1 }),
    getCurrentLearningPath(),
    getEvaluationReport(),
    searchKnowledgeBase(1, '二叉树递归遍历的核心不变式', 1),
  ]);
  const [courses, profile, resources, path, evaluation, rag] = results;
  const courseCount = courses.status === 'fulfilled' ? courses.value.length : 0;
  const ragReady = rag.status === 'fulfilled' && rag.value.results.length > 0;
  const profileReady = profile.status === 'fulfilled' && Boolean(profile.value.profile.studentId);
  const profileVersion = profile.status === 'fulfilled' ? profile.value.version : 0;
  const resourceCount = resources.status === 'fulfilled' ? resources.value.total : 0;
  const pathDays = path.status === 'fulfilled' ? path.value.learningPath.dailyPlan.length : 0;
  const evaluationReady = evaluation.status === 'fulfilled' && (evaluation.value.mastery.length > 0 || evaluation.value.overallScore > 0);
  const masteryCount = evaluation.status === 'fulfilled' ? evaluation.value.mastery.length : 0;

  readiness.value = [
    {
      label: '双课程与知识库',
      detail: ragReady ? `${courseCount} 门课程，真实 RAG 已召回证据` : `${courseCount} 门课程；请检查向量服务`,
      state: courseCount >= 2 && ragReady ? 'ready' : 'action',
    },
    {
      label: '动态学习画像',
      detail: profileReady ? `画像版本 ${profileVersion} 可用于个性化` : '先完成一次画像对话',
      state: profileReady ? 'ready' : 'action',
    },
    {
      label: '个性化资源',
      detail: resourceCount > 0 ? `已有 ${resourceCount} 个可演示资源` : '先生成一个资源包',
      state: resourceCount > 0 ? 'ready' : 'action',
    },
    {
      label: '学习路径',
      detail: pathDays > 0 ? `当前路径包含 ${pathDays} 个学习阶段` : '先生成学习路径',
      state: pathDays > 0 ? 'ready' : 'action',
    },
    {
      label: '测验与评估',
      detail: evaluationReady ? `已有 ${masteryCount} 个掌握度观测点` : '完成一次专项测验即可激活',
      state: evaluationReady ? 'ready' : 'action',
    },
  ];
  lastCheckedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  checking.value = false;
};

onMounted(() => {
  clock = setInterval(() => { now.value = Date.now(); }, 1000);
  void refreshReadiness();
});

onBeforeUnmount(() => {
  if (clock) clearInterval(clock);
});
</script>

<template>
  <div class="demo-console ai-page-bg page-enter">
    <section class="demo-hero">
      <div>
        <span class="demo-kicker">COMPETITION RUNBOOK</span>
        <h1>7 分钟演示控制台</h1>
        <p>用一条可恢复的动线串起画像、RAG、多智能体生成、路径、辅导与评估回流。重置只清除本机演示勾选，不删除业务数据。</p>
      </div>
      <div class="demo-clock" :class="{ active: session.startedAt }">
        <span>演示计时</span>
        <strong>{{ elapsedLabel }}</strong>
        <small>目标 ≤ 07:00</small>
      </div>
      <div class="demo-hero-actions">
        <button class="primary-action" type="button" @click="startDemo">{{ session.startedAt ? '重新计时' : '开始演示' }}</button>
        <button class="secondary-action" type="button" @click="resetGuide">重置演示进度</button>
      </div>
    </section>

    <section class="demo-summary-grid">
      <article class="demo-summary interactive-card">
        <span>系统预检</span><strong>{{ readyCount }}/5</strong><small>真实接口已就绪</small>
      </article>
      <article class="demo-summary interactive-card">
        <span>故事线进度</span><strong>{{ progressPercent }}%</strong><small>{{ completedCount }}/{{ demoSteps.length }} 环节</small>
      </article>
      <article class="demo-summary interactive-card">
        <span>核心证据</span><strong>812</strong><small>生产向量索引</small>
      </article>
    </section>

    <section class="readiness-panel interactive-card">
      <div class="demo-section-head">
        <div><span>LIVE PREFLIGHT</span><h2>演示前真实状态</h2></div>
        <button class="secondary-action" type="button" :disabled="checking" @click="refreshReadiness">
          {{ checking ? '检查中…' : '重新预检' }}
        </button>
      </div>
      <p v-if="lastCheckedAt" class="check-time">最近检查 {{ lastCheckedAt }}</p>
      <div class="readiness-grid">
        <article v-for="item in readiness" :key="item.label" :class="`readiness-${item.state}`">
          <i aria-hidden="true"></i><div><strong>{{ item.label }}</strong><p>{{ item.detail }}</p></div>
        </article>
      </div>
    </section>

    <section class="demo-runbook">
      <div class="demo-section-head">
        <div><span>DEMO FLOW</span><h2>按证据推进，不靠口头跳转</h2></div>
        <p>每一步都对应一个可见产品结果</p>
      </div>
      <div class="demo-step-list">
        <article v-for="(step, index) in demoSteps" :key="step.title" class="demo-step interactive-card" :class="{ done: session.completed.includes(index) }">
          <button class="step-check" type="button" :aria-label="`标记 ${step.title} 完成`" @click="toggleComplete(index)">
            {{ session.completed.includes(index) ? '✓' : index + 1 }}
          </button>
          <div class="step-copy"><span>STEP {{ String(index + 1).padStart(2, '0') }}</span><h3>{{ step.title }}</h3><p>{{ step.summary }}</p><small>{{ step.proof }}</small></div>
          <button class="step-enter" type="button" @click="enterStep(index)">进入页面 <b>→</b></button>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.demo-console { min-height: calc(100vh - 72px); padding: clamp(24px, 4vw, 54px); color: var(--ink); }
.demo-hero { display: grid; grid-template-columns: minmax(0, 1fr) 190px; gap: 24px; align-items: center; padding: clamp(28px, 5vw, 58px); border: 1px solid rgba(18,128,111,.16); border-radius: 28px; background: radial-gradient(circle at 82% 12%, rgba(52,111,168,.2), transparent 28%), linear-gradient(135deg, rgba(255,255,255,.94), rgba(223,244,237,.82)); box-shadow: var(--shadow-card); }
.demo-kicker,.demo-section-head span,.step-copy>span { color: var(--teal); font-size: .75rem; font-weight: 900; letter-spacing: .16em; }
.demo-hero h1 { margin: 10px 0 14px; font-size: clamp(2.3rem, 5vw, 4.8rem); line-height: .96; letter-spacing: -.055em; }
.demo-hero p { max-width: 760px; margin: 0; color: var(--muted); line-height: 1.8; }
.demo-clock { display: grid; place-items: center; min-height: 150px; border: 1px solid rgba(35,58,53,.12); border-radius: 22px; background: rgba(255,255,255,.76); }
.demo-clock.active { border-color: rgba(18,128,111,.4); box-shadow: inset 0 0 0 4px rgba(18,128,111,.07); }
.demo-clock span,.demo-clock small { color: var(--muted); font-weight: 700; }
.demo-clock strong { font-variant-numeric: tabular-nums; font-size: 2.25rem; letter-spacing: .04em; }
.demo-hero-actions { grid-column: 1 / -1; display: flex; gap: 12px; flex-wrap: wrap; }
.secondary-action { min-height: 44px; border: 1px solid rgba(35,58,53,.18); border-radius: 999px; background: rgba(255,255,255,.76); padding: 0 18px; color: var(--ink); font-weight: 850; }
.demo-summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin: 20px 0; }
.demo-summary { display: grid; gap: 6px; padding: 22px; border-radius: 18px; background: rgba(255,255,255,.8); }
.demo-summary span,.demo-summary small { color: var(--muted); }.demo-summary strong { font-size: 2rem; }
.readiness-panel { padding: clamp(22px, 3vw, 34px); border-radius: 24px; background: rgba(255,255,255,.82); }
.demo-section-head { display: flex; justify-content: space-between; gap: 20px; align-items: end; }
.demo-section-head h2 { margin: 6px 0 0; font-size: clamp(1.5rem, 2.8vw, 2.4rem); }.demo-section-head>p,.check-time { color: var(--muted); }.check-time { margin: 8px 0 0; font-size: .82rem; }
.readiness-grid { display: grid; grid-template-columns: repeat(5, minmax(0,1fr)); gap: 12px; margin-top: 22px; }
.readiness-grid article { display: grid; grid-template-columns: 12px 1fr; gap: 10px; min-height: 116px; padding: 18px; border: 1px solid rgba(35,58,53,.1); border-radius: 16px; background: rgba(247,250,247,.76); }
.readiness-grid i { width: 10px; height: 10px; margin-top: 5px; border-radius: 50%; background: #9aa6a2; }.readiness-ready i { background: var(--teal); box-shadow: 0 0 0 5px rgba(18,128,111,.12); }.readiness-action i { background: var(--amber); }.readiness-grid p { margin: 8px 0 0; color: var(--muted); font-size: .84rem; line-height: 1.5; }
.demo-runbook { margin-top: 28px; }.demo-step-list { display: grid; gap: 14px; margin-top: 18px; }
.demo-step { display: grid; grid-template-columns: 52px minmax(0,1fr) auto; gap: 18px; align-items: center; padding: 20px 22px; border-radius: 20px; background: rgba(255,255,255,.82); }.demo-step.done { background: linear-gradient(90deg, rgba(223,244,237,.92), rgba(255,255,255,.86)); }
.step-check { width: 44px; height: 44px; border: 1px solid rgba(18,128,111,.32); border-radius: 50%; background: var(--color-primary-soft); color: var(--teal); font-weight: 900; }.step-copy h3 { margin: 4px 0; font-size: 1.22rem; }.step-copy p { margin: 0; color: var(--muted); }.step-copy small { display: block; margin-top: 8px; color: var(--blue); font-weight: 750; }.step-enter { border: 0; background: transparent; color: var(--teal); font-weight: 900; white-space: nowrap; }.step-enter b { font-size: 1.2rem; }
@media (max-width: 1100px) { .readiness-grid { grid-template-columns: repeat(2,1fr); }.demo-hero { grid-template-columns: 1fr; }.demo-clock { width: 190px; }.demo-hero-actions { grid-column: auto; } }
@media (max-width: 720px) { .demo-summary-grid,.readiness-grid { grid-template-columns: 1fr; }.demo-step { grid-template-columns: 44px 1fr; }.step-enter { grid-column: 2; justify-self: start; }.demo-section-head { align-items: flex-start; flex-direction: column; } }
</style>
