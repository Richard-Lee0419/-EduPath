<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { getClassLearningInsights, listTeachingClasses } from '@/api/classInsight';
import { createTeacherAssignment, getTeacherAssignmentProgress, listTeacherAssignments, publishTeacherAssignment } from '@/api/teacherAssignments';
import type { ClassLearningInsights, TeachingClass } from '@/types/api';
import type { AssignmentProgress, TeacherAssignment } from '@/types/api';

const classes = ref<TeachingClass[]>([]);
const selectedClassId = ref<number | null>(null);
const selectedCourseId = ref<number | null>(null);
const windowDays = ref(30);
const insights = ref<ClassLearningInsights | null>(null);
const loading = ref(false);
const errorMessage = ref('');
const assignments = ref<TeacherAssignment[]>([]);
const assignmentProgress = ref<Record<number, AssignmentProgress[]>>({});
const assignmentTitle = ref('');
const assignmentInstructions = ref('');
const assignmentSaving = ref(false);
const assignmentMessage = ref('');
const selectedAssignmentId = ref<number | null>(null);

const maxTrendEvents = computed(() => Math.max(1, ...(insights.value?.activity_trend.map((item) => item.event_count) ?? [1])));
const percent = (value: number) => `${Math.round(value * 100)}%`;
const score = (value?: number) => value == null ? '—' : `${Math.round(value * 10) / 10}`;
const riskLabels: Record<string, string> = {
  critical: '高风险',
  attention: '需关注',
  insufficient_data: '证据不足',
  steady: '稳定',
};
const formatTime = (value?: string) => value
  ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
  : '暂无活动';

const loadInsights = async () => {
  if (!selectedClassId.value) {
    insights.value = null;
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await getClassLearningInsights(selectedClassId.value, {
      courseId: selectedCourseId.value || undefined,
      windowDays: windowDays.value,
    });
    insights.value = result;
    selectedCourseId.value = result.selected_course.course_id;
    await loadAssignments();
  } catch (error) {
    insights.value = null;
    errorMessage.value = error instanceof Error ? error.message : '班级洞察加载失败';
  } finally {
    loading.value = false;
  }
};

const loadAssignments = async () => {
  if (!selectedClassId.value) return;
  assignments.value = await listTeacherAssignments(selectedClassId.value);
  for (const assignment of assignments.value.filter((item) => item.status === 'published')) {
    if (!assignmentProgress.value[assignment.id]) {
      assignmentProgress.value[assignment.id] = await getTeacherAssignmentProgress(selectedClassId.value, assignment.id);
    }
  }
};

const createAssignment = async () => {
  if (!selectedClassId.value || !selectedCourseId.value || !assignmentTitle.value.trim()) return;
  assignmentSaving.value = true;
  assignmentMessage.value = '';
  try {
    await createTeacherAssignment(selectedClassId.value, {
      course_id: selectedCourseId.value,
      title: assignmentTitle.value.trim(),
      instructions: assignmentInstructions.value.trim() || undefined,
    });
    assignmentTitle.value = '';
    assignmentInstructions.value = '';
    assignmentMessage.value = '任务草稿已保存，可在下方发布给班级。';
    await loadAssignments();
  } catch (error) {
    assignmentMessage.value = error instanceof Error ? error.message : '任务创建失败';
  } finally {
    assignmentSaving.value = false;
  }
};

const publishAssignment = async (assignment: TeacherAssignment) => {
  if (!selectedClassId.value) return;
  try {
    const result = await publishTeacherAssignment(selectedClassId.value, assignment.id);
    assignmentMessage.value = `已发布，系统为 ${result.assigned_count} 名学生建立任务记录。`;
    await loadAssignments();
  } catch (error) {
    assignmentMessage.value = error instanceof Error ? error.message : '任务发布失败';
  }
};

const showProgress = async (assignment: TeacherAssignment) => {
  if (!selectedClassId.value) return;
  selectedAssignmentId.value = assignment.id;
  assignmentProgress.value[assignment.id] = await getTeacherAssignmentProgress(selectedClassId.value, assignment.id);
};

const prepareAssignment = (title: string, instructions: string) => {
  assignmentTitle.value = title;
  assignmentInstructions.value = instructions;
  assignmentMessage.value = '已根据洞察预填任务草稿，请确认后保存。';
  window.setTimeout(() => document.querySelector('.assignment-create input')?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 0);
};

const loadClasses = async () => {
  loading.value = true;
  errorMessage.value = '';
  try {
    classes.value = await listTeachingClasses();
    selectedClassId.value = classes.value[0]?.id ?? null;
    if (selectedClassId.value) await loadInsights();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '班级列表加载失败';
  } finally {
    loading.value = false;
  }
};

watch(windowDays, () => void loadInsights());

const changeClass = () => {
  selectedCourseId.value = null;
  void loadInsights();
};

const changeCourse = () => void loadInsights();

onMounted(() => void loadClasses());
</script>

<template>
  <main class="teacher-insights page-enter">
    <header class="insights-hero">
      <div>
        <span>TEACHING INTELLIGENCE</span>
        <h1>班级学习洞察</h1>
        <p>聚合真实学习事件、掌握度、测验和资源完成记录，帮助教师识别共性薄弱点与需要优先跟进的学生。</p>
      </div>
      <div class="insight-filters">
        <label>
          <span>班级</span>
          <select v-model="selectedClassId" @change="changeClass">
            <option v-for="item in classes" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </label>
        <label v-if="insights?.courses.length">
          <span>课程</span>
          <select v-model="selectedCourseId" @change="changeCourse">
            <option v-for="item in insights.courses" :key="item.course_id" :value="item.course_id">{{ item.course_name }}</option>
          </select>
        </label>
        <label>
          <span>观察窗口</span>
          <select v-model.number="windowDays">
            <option :value="7">近 7 天</option>
            <option :value="30">近 30 天</option>
            <option :value="90">近 90 天</option>
          </select>
        </label>
      </div>
    </header>

    <p v-if="loading && !insights" class="insight-state">正在聚合班级真实学习证据…</p>
    <p v-else-if="errorMessage" class="insight-state is-error">{{ errorMessage }}</p>
    <section v-else-if="!classes.length" class="insight-state">
      当前账号还没有可管理班级，请先通过班级接口创建班级、绑定课程并添加学生。
    </section>

    <template v-else-if="insights">
      <section class="insight-kpis">
        <article>
          <span>班级学生</span>
          <strong>{{ insights.summary.student_count }}</strong>
          <small>掌握度覆盖 {{ percent(insights.summary.mastery_coverage) }}</small>
        </article>
        <article>
          <span>近期活跃率</span>
          <strong>{{ percent(insights.summary.active_rate) }}</strong>
          <small>{{ insights.summary.active_students }} 人产生 {{ insights.summary.learning_event_count }} 条事件</small>
        </article>
        <article>
          <span>平均掌握度</span>
          <strong>{{ score(insights.summary.average_mastery) }}</strong>
          <small>近期测验均分 {{ score(insights.summary.average_quiz_score) }}</small>
        </article>
        <article class="is-risk">
          <span>需要跟进</span>
          <strong>{{ insights.summary.critical_students + insights.summary.attention_students }}</strong>
          <small>高风险 {{ insights.summary.critical_students }} · 需关注 {{ insights.summary.attention_students }}</small>
        </article>
      </section>

      <section class="insight-grid">
        <article class="insight-card activity-card">
          <header><div><span>ACTIVITY TREND</span><h2>学习活跃趋势</h2></div><b>{{ windowDays }} 天</b></header>
          <div v-if="insights.activity_trend.length" class="activity-bars">
            <div v-for="item in insights.activity_trend" :key="item.date">
              <i><b :style="{ height: `${Math.max(8, item.event_count / maxTrendEvents * 100)}%` }" /></i>
              <strong>{{ item.event_count }}</strong>
              <span>{{ item.date.slice(5) }}</span>
            </div>
          </div>
          <p v-else class="card-empty">当前窗口还没有学习事件。</p>
        </article>

        <article class="insight-card">
          <header><div><span>KNOWLEDGE RISK</span><h2>班级共性薄弱点</h2></div></header>
          <div v-if="insights.weak_knowledge_points.length" class="weak-list">
            <div v-for="item in insights.weak_knowledge_points" :key="item.knowledge_point">
              <div><strong>{{ item.knowledge_point }}</strong><small>{{ item.student_count }} 名学生 · {{ item.attempts }} 次练习</small></div>
              <b>{{ score(item.average_mastery) }}</b>
              <span>{{ item.at_risk_students }} 人低于 60</span>
              <button type="button" class="insight-action" @click="prepareAssignment(`${item.knowledge_point}补强练习`, `围绕${item.knowledge_point}完成分层练习，并在提交后查看解析。`)">创建任务</button>
            </div>
          </div>
          <p v-else class="card-empty">尚无可归属到本课程知识点的掌握度数据。</p>
        </article>

        <article class="insight-card student-card">
          <header><div><span>STUDENT SIGNALS</span><h2>学生风险分层</h2></div><b>确定性阈值</b></header>
          <div v-if="insights.student_risks.length" class="student-table">
            <article v-for="item in insights.student_risks" :key="item.user_id">
              <div class="student-name"><strong>{{ item.username }}</strong><small>最近活动 {{ formatTime(item.last_active_at) }}</small></div>
              <span :class="`risk-${item.risk_level}`">{{ riskLabels[item.risk_level] }}</span>
              <div><b>{{ score(item.average_mastery) }}</b><small>掌握度</small></div>
              <div><b>{{ item.activity_count }}</b><small>学习事件</small></div>
              <p>{{ item.reasons.join('；') }}</p>
              <button type="button" class="insight-action" @click="prepareAssignment(`${item.username}个性化跟进`, `请先完成${item.reasons[0] || '薄弱知识点'}对应练习，再查看学习反馈。`)">预填跟进</button>
            </article>
          </div>
          <p v-else class="card-empty">班级尚未添加学生成员。</p>
        </article>

        <article class="insight-card intervention-card">
          <header><div><span>TEACHING ACTIONS</span><h2>建议干预动作</h2></div></header>
          <ol>
            <li v-for="item in insights.recommended_interventions" :key="item">{{ item }}</li>
          </ol>
          <footer>生成于 {{ formatTime(insights.source.generated_at) }} · 仅基于真实持久化数据，不调用模型推测</footer>
        </article>
      </section>

      <section class="assignment-workbench">
        <header class="workbench-header">
          <div>
            <span>TEACHING ACTIONS</span>
            <h2>补救任务工作台</h2>
            <p>把班级洞察转成可发布的学习任务，并回收学生完成结果。</p>
          </div>
          <div class="workbench-status">{{ assignments.length }} 个任务</div>
        </header>
        <div class="assignment-create">
          <input v-model="assignmentTitle" type="text" placeholder="例如：递归调用栈补强练习" aria-label="任务标题" />
          <input v-model="assignmentInstructions" type="text" placeholder="给学生的简短说明（可选）" aria-label="任务说明" />
          <button type="button" :disabled="assignmentSaving || !assignmentTitle.trim()" @click="createAssignment">
            {{ assignmentSaving ? '保存中…' : '保存草稿' }}
          </button>
        </div>
        <p v-if="assignmentMessage" class="assignment-message">{{ assignmentMessage }}</p>
        <div v-if="assignments.length" class="assignment-list">
          <article v-for="assignment in assignments" :key="assignment.id" class="assignment-row">
            <div class="assignment-copy">
              <strong>{{ assignment.title }}</strong>
              <small>{{ assignment.instructions || '暂无说明' }}</small>
            </div>
            <span :class="['assignment-status', `status-${assignment.status}`]">
              {{ assignment.status === 'published' ? '已发布' : assignment.status === 'draft' ? '草稿' : '已归档' }}
            </span>
            <span class="assignment-count">{{ assignment.completed_count }}/{{ assignment.assigned_count }} 完成</span>
            <button v-if="assignment.status === 'draft'" type="button" class="quiet-button" @click="publishAssignment(assignment)">发布</button>
            <button v-else type="button" class="quiet-button" @click="showProgress(assignment)">查看进度</button>
          </article>
        </div>
        <p v-else class="card-empty">还没有班级任务。可以根据上方风险学生或薄弱知识点先创建一条补救任务。</p>
        <div v-if="selectedAssignmentId && assignmentProgress[selectedAssignmentId]" class="progress-drawer">
          <div class="progress-drawer-header">
            <strong>学生完成进度</strong>
            <button type="button" class="close-progress" @click="selectedAssignmentId = null">收起</button>
          </div>
          <div v-for="item in assignmentProgress[selectedAssignmentId]" :key="item.student_id" class="progress-row">
            <span>{{ item.username }}</span>
            <span :class="item.status === 'completed' ? 'done' : 'pending'">{{ item.status === 'completed' ? '已完成' : '待完成' }}</span>
            <span>{{ item.score == null ? '—' : `${item.score} 分` }}</span>
          </div>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.teacher-insights { width: min(1240px, calc(100% - 40px)); margin: 0 auto; padding: 42px 0 70px; }
.insights-hero { display: flex; justify-content: space-between; gap: 32px; border-radius: 26px; padding: 32px; background: linear-gradient(135deg, #e7f7f2, #fff8e8); box-shadow: 0 20px 58px rgba(30, 77, 66, .09); }
.insights-hero > div:first-child { max-width: 650px; }
.insights-hero span, .insight-card header span { color: #087766; font-size: 11px; font-weight: 900; letter-spacing: .12em; }
.insights-hero h1 { margin: 7px 0 10px; color: #173f37; font-size: clamp(30px, 4vw, 46px); }
.insights-hero p { margin: 0; color: rgba(31,54,49,.68); line-height: 1.7; }
.insight-filters { display: flex; align-self: flex-end; flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.insight-filters label { display: grid; gap: 5px; }
.insight-filters select { min-width: 132px; border: 1px solid rgba(0,121,102,.14); border-radius: 10px; background: rgba(255,255,255,.9); color: #173f37; padding: 10px 12px; }
.insight-kpis { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 14px; margin: 22px 0; }
.insight-kpis article { display: grid; gap: 5px; border: 1px solid rgba(0,121,102,.1); border-radius: 17px; background: #fff; padding: 20px; box-shadow: 0 12px 34px rgba(30,77,66,.06); }
.insight-kpis span, .insight-kpis small { color: rgba(31,54,49,.58); font-size: 11px; }
.insight-kpis strong { color: #087766; font-size: 28px; }
.insight-kpis article.is-risk strong { color: #c65b43; }
.insight-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 16px; }
.insight-card { border: 1px solid rgba(0,121,102,.11); border-radius: 20px; background: rgba(255,255,255,.94); padding: 22px; box-shadow: 0 16px 42px rgba(30,77,66,.07); }
.insight-card > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.insight-card h2 { margin: 5px 0 0; color: #173f37; font-size: 19px; }
.insight-card header > b { color: rgba(31,54,49,.5); font-size: 11px; }
.activity-bars { display: flex; align-items: flex-end; gap: 10px; min-height: 190px; margin-top: 18px; overflow-x: auto; }
.activity-bars > div { display: grid; grid-template-rows: 130px auto auto; flex: 1 0 34px; gap: 4px; text-align: center; }
.activity-bars i { display: flex; align-items: flex-end; justify-content: center; border-radius: 9px; background: rgba(0,121,102,.05); overflow: hidden; }
.activity-bars i b { width: 100%; border-radius: 9px; background: linear-gradient(#4fc1a3,#087766); }
.activity-bars strong { color: #173f37; font-size: 11px; }
.activity-bars span, .weak-list small, .student-table small { color: rgba(31,54,49,.5); font-size: 10px; }
.weak-list { display: grid; gap: 10px; margin-top: 18px; }
.weak-list > div { display: grid; grid-template-columns: minmax(0,1fr) auto auto auto; align-items: center; gap: 12px; border-radius: 11px; background: rgba(246,250,248,.9); padding: 11px 12px; }
.weak-list > div > div { display: grid; gap: 3px; }
.weak-list > div > b { color: #c65b43; font-size: 18px; }
.weak-list > div > span { border-radius: 99px; background: rgba(198,91,67,.1); color: #aa4935; font-size: 10px; padding: 5px 8px; }
.student-card { grid-column: 1 / -1; }
.student-table { display: grid; gap: 8px; margin-top: 18px; }
.student-table > article { display: grid; grid-template-columns: minmax(170px,1.2fr) 86px 80px 80px minmax(150px,1.2fr) auto; align-items: center; gap: 12px; border-top: 1px solid rgba(31,54,49,.07); padding: 11px 4px; }
.student-name, .student-table article > div { display: grid; gap: 3px; }
.student-table article > span { border-radius: 99px; padding: 6px 8px; text-align: center; font-size: 10px; font-weight: 800; }
.risk-critical { background: rgba(204,76,59,.12); color: #b84231; }.risk-attention { background: rgba(229,151,50,.15); color: #a85c1f; }.risk-insufficient_data { background: rgba(70,102,132,.1); color: #486477; }.risk-steady { background: rgba(0,121,102,.1); color: #087766; }
.student-table p { margin: 0; color: rgba(31,54,49,.62); font-size: 11px; line-height: 1.5; }
.insight-action { border:1px solid rgba(8,119,102,.16); border-radius:999px; background:rgba(8,119,102,.08); color:#087766; padding:6px 9px; cursor:pointer; font-size:10px; font-weight:800; white-space:nowrap; }
.insight-action:hover { background:rgba(8,119,102,.15); }
.intervention-card ol { display: grid; gap: 10px; margin: 18px 0; padding-left: 22px; color: #244f46; line-height: 1.6; }
.intervention-card li::marker { color: #d8892f; font-weight: 900; }
.intervention-card footer, .card-empty, .insight-state { color: rgba(31,54,49,.58); font-size: 11px; }
.insight-state { margin: 22px 0; border-radius: 14px; background: rgba(0,121,102,.06); padding: 18px; }
.insight-state.is-error { color: #b84231; }
.assignment-workbench { margin-top: 18px; border: 1px solid rgba(0,121,102,.11); border-radius: 22px; background: linear-gradient(135deg, rgba(255,255,255,.96), rgba(239,249,245,.82)); padding: 24px; box-shadow: 0 16px 42px rgba(30,77,66,.07); }
.workbench-header { display:flex; align-items:flex-start; justify-content:space-between; gap:18px; }
.workbench-header span { color:#087766; font-size:11px; font-weight:900; letter-spacing:.12em; }
.workbench-header h2 { margin:5px 0 6px; color:#173f37; font-size:21px; }
.workbench-header p { margin:0; color:rgba(31,54,49,.62); font-size:12px; }
.workbench-status { border-radius:99px; background:rgba(0,121,102,.09); color:#087766; padding:8px 12px; font-size:11px; font-weight:800; }
.assignment-create { display:grid; grid-template-columns:1.1fr 1.4fr auto; gap:10px; margin:20px 0 10px; }
.assignment-create input { min-width:0; border:1px solid rgba(0,121,102,.14); border-radius:11px; background:rgba(255,255,255,.88); padding:11px 12px; color:#173f37; outline:none; }
.assignment-create input:focus { border-color:#38a58c; box-shadow:0 0 0 3px rgba(56,165,140,.12); }
.assignment-create button, .quiet-button { border:0; border-radius:11px; background:#087766; color:#fff; padding:0 16px; cursor:pointer; font-weight:800; }
.assignment-create button:disabled { cursor:not-allowed; opacity:.5; }
.assignment-message { margin:8px 0 0; color:#087766; font-size:11px; }
.assignment-list { display:grid; gap:8px; margin-top:15px; }
.assignment-row { display:grid; grid-template-columns:minmax(0,1.5fr) 74px 90px auto; align-items:center; gap:12px; border-top:1px solid rgba(31,54,49,.08); padding:13px 2px; }
.assignment-copy { display:grid; gap:4px; min-width:0; }.assignment-copy strong { color:#244f46; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.assignment-copy small { color:rgba(31,54,49,.52); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.assignment-status { border-radius:99px; padding:5px 8px; text-align:center; font-size:10px; font-weight:800; }.status-published { background:rgba(0,121,102,.1); color:#087766; }.status-draft { background:rgba(216,137,47,.14); color:#9b611c; }.status-archived { background:rgba(31,54,49,.08); color:#61716c; }
.assignment-count { color:rgba(31,54,49,.6); font-size:11px; }.quiet-button { min-height:31px; font-size:11px; padding:0 12px; background:rgba(8,119,102,.1); color:#087766; }.quiet-button:hover { background:rgba(8,119,102,.17); }
.progress-drawer { margin-top:12px; border-radius:14px; background:rgba(246,250,248,.92); padding:13px; }.progress-drawer-header { display:flex; justify-content:space-between; align-items:center; color:#244f46; font-size:12px; }.close-progress { border:0; background:transparent; color:#087766; cursor:pointer; font-size:11px; }.progress-row { display:grid; grid-template-columns:1fr 80px 60px; gap:10px; border-top:1px solid rgba(31,54,49,.07); padding:9px 0; color:rgba(31,54,49,.7); font-size:11px; }.progress-row .done { color:#087766; }.progress-row .pending { color:#a85c1f; }
@media (max-width: 900px) { .insights-hero { flex-direction: column; }.insight-filters { align-self: stretch; justify-content: flex-start; }.insight-kpis { grid-template-columns: repeat(2,1fr); }.insight-grid { grid-template-columns: 1fr; }.student-card { grid-column: auto; }.student-table > article { grid-template-columns: 1fr 90px; }.student-table p { grid-column: 1 / -1; }.student-table .insight-action { grid-column: 2; } }
@media (max-width: 560px) { .teacher-insights { width: min(100% - 24px,1240px); }.insight-kpis { grid-template-columns: 1fr; }.insights-hero { padding: 24px; }.insight-filters label, .insight-filters select { width: 100%; }.weak-list > div { grid-template-columns: 1fr auto; }.weak-list > div > span, .weak-list > div > .insight-action { grid-column: 1 / -1; width: max-content; }.assignment-create { grid-template-columns:1fr; }.assignment-create button { min-height:40px; }.assignment-row { grid-template-columns:1fr auto; }.assignment-count { grid-column:1; }.assignment-row .quiet-button { grid-column:2; grid-row:2; } }
</style>
