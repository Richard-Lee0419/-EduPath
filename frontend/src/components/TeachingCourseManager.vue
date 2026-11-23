<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import CoursePdfPreview from './CoursePdfPreview.vue';
import { appPath } from '@/appPath';
import { loadReviewSample, reviewSampleName } from '@/api/reviewSample';
import {
  addTeachingClassStudent, createTeachingClass, listTeachingClasses, listTeachingClassMembers, type ClassMember,
  type ReviewStudent,
} from '@/api/classInsight';
import {
  deleteKnowledgeDocument, listKnowledgeDocuments, reindexKnowledgeDocument, uploadKnowledgeDocument,
  type KnowledgeDocument,
} from '@/api/kb';
import { normalizeTaskId, waitForAgentTask } from '@/api/task';
import {
  createTeachingCourse, listTeachingCourses, setTeachingCoursePublication, updateTeachingCourse, type TeachingCourse,
} from '@/api/teachingCourse';
import type { TeachingClass } from '@/types/api';

const props = defineProps<{ reviewDemo?: boolean; reviewStudents?: ReviewStudent[] }>();
const courses = ref<TeachingCourse[]>([]);
const classes = ref<TeachingClass[]>([]);
const newClassName = ref('');
const memberClassId = ref(0);
const studentUsername = ref('');
const members = ref<ClassMember[]>([]);
const memberName = (username: string) =>
  props.reviewStudents?.find((student) => student.username === username)?.display_name ?? username;
const availableStudents = computed(() =>
  props.reviewStudents?.filter((student) => !members.value.some((member) => member.user_id === student.user_id)) ?? []);
const documents = ref<KnowledgeDocument[]>([]);
const selectedId = ref(0);
const name = ref('');
const description = ref('');
const topics = ref('');
const classIds = ref<number[]>([]);
const uploadTopic = ref<number | undefined>();
const fileInput = ref<HTMLInputElement | null>(null);
const preview = ref<InstanceType<typeof CoursePdfPreview> | null>(null);
const selected = computed(() => courses.value.find((course) => course.id === selectedId.value));
const busy = ref(false);
const message = ref('');
const error = ref('');
const fileMessages = ref<Array<{ filename: string; detail: string; passed: boolean }>>([]);
const statusLabels = { draft: '草稿', active: '已发布', archived: '已撤回' };
const indexLabels: Record<string, string> = { indexed: '可学习', failed: '解析或索引失败', pending: '待索引' };
const hasIndexedPdf = computed(() => documents.value.some((document) => document.index_status === 'indexed'));
const isSampleCourse = computed(() => props.reviewDemo && selected.value?.code.startsWith('REVIEW-'));
const emit = defineEmits<{ classUpdated: [] }>();

async function operate(work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    await work();
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '课程操作失败，请重试';
  } finally {
    busy.value = false;
  }
}

async function loadDocuments(courseId: number) {
  documents.value = await listKnowledgeDocuments({ courseId });
}

function editCourse(course?: TeachingCourse) {
  selectedId.value = course?.id ?? 0;
  name.value = course?.name ?? '';
  description.value = course?.description ?? '';
  topics.value = course?.topics.map((topic) => topic.name).join('\n') ?? '';
  classIds.value = [...(course?.class_ids ?? [])];
  uploadTopic.value = undefined;
  if (fileInput.value) fileInput.value.value = '';
  fileMessages.value = [];
}

function selectCourse(id: number) {
  return operate(async () => {
    const course = courses.value.find((item) => item.id === id);
    editCourse(course);
    documents.value = [];
    if (course) await loadDocuments(course.id);
  });
}

function upsert(course: TeachingCourse) {
  const exists = courses.value.some((item) => item.id === course.id);
  courses.value = exists ? courses.value.map((item) => item.id === course.id ? course : item) : [...courses.value, course];
}

function save() {
  return operate(async () => {
    const current = selected.value;
    const topicNames = topics.value.split(/\r?\n/).map((topic) => topic.trim()).filter(Boolean);
    if (!name.value.trim() || !topicNames.length || topicNames.length > 30) throw new Error('请输入课程名称和 1–30 个知识点');
    if (topicNames.some((topic) => topic.length > 100)) throw new Error('每个知识点不能超过 100 字');
    if (new Set(topicNames.map((topic) => topic.toLowerCase())).size !== topicNames.length) throw new Error('知识点名称不能重复');
    const changedTopics = topicNames.join('\n') !== current?.topics.map((topic) => topic.name).join('\n');
    const draft = { name: name.value.trim(), description: description.value.trim(), ...(changedTopics ? { topics: topicNames } : {}) };
    const course = current ? await updateTeachingCourse(current.id, draft) : await createTeachingCourse(draft);
    upsert(course);
    editCourse(course);
    await loadDocuments(course.id);
    message.value = current ? '课程信息已保存。' : '已创建课程草稿，可以上传讲义。';
  });
}

async function setPublication(courseId: number, published: boolean) {
  const course = await setTeachingCoursePublication(courseId, classIds.value, published);
  upsert(course);
  classIds.value = [...course.class_ids];
  message.value = published ? '课程已向选中的班级发布。' : '课程已撤回，班内学生暂时无法访问。';
  emit('classUpdated');
}

function publish(published: boolean) {
  const course = selected.value;
  if (course) return operate(() => setPublication(course.id, published));
}

function publishSample() {
  const course = selected.value;
  if (!isSampleCourse.value || !course || !classIds.value.length) return;
  return operate(async () => {
    const sample = documents.value.find((document) => document.filename === reviewSampleName);
    if (sample?.index_status !== 'indexed') {
      if (sample) {
        const created = await reindexKnowledgeDocument(sample.id);
        try {
          await waitForAgentTask(normalizeTaskId(created));
        } finally {
          await loadDocuments(course.id);
        }
      } else {
        const result = await uploadKnowledgeDocument(course.id, await loadReviewSample());
        await loadDocuments(course.id);
        if (result.index_status !== 'indexed') throw new Error(result.index_error || '示例 PDF 未完成索引');
      }
    }
    await setPublication(course.id, true);
  });
}

function createClass() {
  return operate(async () => {
    const label = newClassName.value.trim();
    if (!label) throw new Error('请输入班级名称');
    const result = await createTeachingClass(label);
    classes.value = await listTeachingClasses();
    memberClassId.value = result.class_info.id;
    members.value = [];
    studentUsername.value = '';
    newClassName.value = '';
    message.value = `班级「${result.class_info.name}」已创建。`;
    emit('classUpdated');
  });
}

function chooseMemberClass() {
  members.value = [];
  studentUsername.value = '';
  if (!memberClassId.value) return;
  return operate(async () => { members.value = await listTeachingClassMembers(memberClassId.value); });
}

function addStudent() {
  return operate(async () => {
    const username = studentUsername.value.trim();
    if (!memberClassId.value || !username) throw new Error('请先选择班级并输入学生用户名');
    members.value = await addTeachingClassStudent(memberClassId.value, username);
    studentUsername.value = '';
    message.value = `学生「${memberName(username)}」已加入班级。`;
    emit('classUpdated');
  });
}

function upload() {
  const input = fileInput.value;
  const files = Array.from(input?.files ?? []);
  const courseId = selectedId.value;
  if (!courseId || !files.length || !input) return;
  return operate(async () => {
    fileMessages.value = [];
    for (const file of files) {
      try {
        if (!/\.pdf$/i.test(file.name) || file.size > 8 * 1024 * 1024) {
          throw new Error('请选择 8 MiB 以内的 PDF');
        }
        const result = await uploadKnowledgeDocument(courseId, file, uploadTopic.value);
        const passed = result.index_status === 'indexed';
        const detail = passed ? '已索引，可用于学习' : result.index_error || '解析或索引失败';
        fileMessages.value.push({ filename: file.name, detail, passed });
      } catch (failure) {
        fileMessages.value.push({ filename: file.name, detail: failure instanceof Error ? failure.message : '上传失败', passed: false });
      }
    }
    input.value = '';
    await loadDocuments(courseId);
  });
}

function reindex(document: KnowledgeDocument) {
  return operate(async () => {
    const created = await reindexKnowledgeDocument(document.id);
    try {
      await waitForAgentTask(normalizeTaskId(created));
      message.value = '资料已完成索引。';
    } finally {
      await loadDocuments(selectedId.value);
    }
  });
}

function remove(document: KnowledgeDocument) {
  if (!window.confirm(`删除「${document.filename}」？学生将无法继续访问这份资料。`)) return;
  return operate(async () => {
    await deleteKnowledgeDocument(document.id);
    await loadDocuments(selectedId.value);
    message.value = '资料已删除。';
  });
}

onMounted(() => operate(async () => {
  [courses.value, classes.value] = await Promise.all([listTeachingCourses(), listTeachingClasses()]);
  const course = (props.reviewDemo && courses.value.find((item) => item.code.startsWith('REVIEW-'))) || courses.value[0];
  editCourse(course);
  if (props.reviewDemo) {
    memberClassId.value = course?.class_ids[0] ?? classes.value[0]?.id ?? 0;
    if (memberClassId.value) members.value = await listTeachingClassMembers(memberClassId.value);
  }
  if (course) await loadDocuments(course.id);
}));
</script>

<template>
  <section class="teaching-courses interactive-card" aria-labelledby="teaching-course-title">
    <header>
      <h2 id="teaching-course-title">班级课程管理</h2>
      <label class="course-field">
        <span>管理课程</span>
        <select :value="selectedId" :disabled="busy" @change="selectCourse(Number(($event.target as HTMLSelectElement).value))">
          <option :value="0">新建班级课程</option>
          <option v-for="course in courses" :key="course.id" :value="course.id">{{ course.name }} · {{ statusLabels[course.status] }}</option>
        </select>
      </label>
    </header>
    <p v-if="error" class="is-error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <section v-if="isSampleCourse" class="course-panel review-course" aria-label="示例课程体验">
      <h3>{{ selected?.name }}</h3>
      <p v-if="selected?.status === 'active'">示例讲义已发布。点击页面顶部的「切换体验身份」，在学生端打开同一门课。</p>
      <p v-else>已备好课程草稿和两页讲义。发布后切换到学生端，即可按讲义提问并查看原文页码。</p>
      <button v-if="selected?.status !== 'active'" type="button" :disabled="busy || !classIds.length" @click="publishSample">
        导入示例 PDF 并发布
      </button>
    </section>
    <details class="course-panel classroom-controls" :open="reviewDemo">
      <summary>班级与成员</summary>
      <div class="course-management-grid">
        <form class="course-panel" @submit.prevent="createClass">
          <label class="course-field">班级名称<input v-model="newClassName" required maxlength="255" :disabled="busy" /></label>
          <button type="submit" :disabled="busy">创建班级</button>
        </form>
        <form v-if="classes.length" class="course-panel" @submit.prevent="addStudent">
          <label class="course-field">添加到班级
            <select v-model.number="memberClassId" :disabled="busy" @change="chooseMemberClass">
              <option :value="0" disabled>选择班级</option>
              <option v-for="group in classes" :key="group.id" :value="group.id">{{ group.name }}</option>
            </select>
          </label>
          <label v-if="reviewDemo" class="course-field">选择示例学生
            <select v-model="studentUsername" required :disabled="busy || !availableStudents.length">
              <option value="" disabled>{{ availableStudents.length ? '请选择要添加的学生' : '示例学生已全部入班' }}</option>
              <option v-for="student in availableStudents" :key="student.user_id" :value="student.username">{{ student.display_name }}</option>
            </select>
          </label>
          <label v-else class="course-field">学生用户名<input v-model="studentUsername" required maxlength="255" :disabled="busy" /></label>
          <button type="submit" :disabled="busy || !memberClassId || (reviewDemo && !studentUsername)">添加学生</button>
          <small v-if="memberClassId && members.length">班级学生：{{ members.filter((member) => member.class_role === 'student').map((member) => memberName(member.username)).join('、') || '暂无' }}</small>
        </form>
      </div>
    </details>
    <div class="course-management-grid">
      <form class="course-panel" @submit.prevent="save">
        <h3>{{ selected ? '编辑课程' : '创建课程' }}</h3>
        <label class="course-field">课程名称<input v-model="name" required maxlength="100" :disabled="busy" /></label>
        <label class="course-field">课程介绍<textarea v-model="description" rows="2" maxlength="4000" :disabled="busy" /></label>
        <label class="course-field">知识点（每行一项）<textarea v-model="topics" rows="4" required :disabled="busy" /></label>
        <button type="submit" :disabled="busy">{{ selected ? '保存课程信息' : '创建课程草稿' }}</button>
      </form>
      <section v-if="selected" class="course-panel" aria-label="PDF 课程资料管理">
        <h3>PDF 课程资料</h3>
        <form class="course-field" @submit.prevent="upload">
          <label class="course-field">关联知识点
            <select v-model="uploadTopic" :disabled="busy">
              <option :value="undefined">整门课程</option>
              <option v-for="topic in selected.topics" :key="topic.id" :value="topic.id">{{ topic.name }}</option>
            </select>
          </label>
          <label class="course-field">选择 PDF（可多选）
            <input ref="fileInput" type="file" multiple accept=".pdf,application/pdf" required :disabled="busy" />
          </label>
          <small>每份最多 8 MiB、100 页，支持可提取正文的未加密 PDF。</small>
          <button type="submit" :disabled="busy">上传课程 PDF</button>
        </form>
        <ul v-if="fileMessages.length" class="file-results" aria-live="polite">
          <li v-for="entry in fileMessages" :key="entry.filename" :class="{ 'is-error': !entry.passed }">
            <strong>{{ entry.filename }}</strong> · {{ entry.detail }}
          </li>
        </ul>
        <p v-if="!documents.length">还没有上传课程 PDF。</p>
        <ul v-else class="course-documents">
          <li v-for="document in documents" :key="document.id">
            <div><strong>{{ document.filename }}</strong><br />
              <small>{{ indexLabels[document.index_status] || document.index_status }} · {{ selected.topics.find((topic) => topic.id === document.knowledge_point_id)?.name || '整门课程' }}</small>
              <p v-if="document.index_error" class="is-error">{{ document.index_error }}</p>
            </div>
            <div class="course-actions">
              <button v-if="document.index_status === 'indexed'" type="button" @click="preview?.open(document.id, 1, document.filename)">查看原文</button>
              <button type="button" :disabled="busy" @click="reindex(document)">重建索引</button>
              <button type="button" :disabled="busy" @click="remove(document)">删除</button>
            </div>
          </li>
        </ul>
      </section>
    </div>
    <section v-if="selected" class="course-panel course-publication" aria-label="发布给班级">
      <h3>发布给班级</h3>
      <p v-if="!classes.length">当前账号还没有可管理的班级。</p>
      <div v-else class="course-actions">
        <label v-for="group in classes" :key="group.id"><input v-model="classIds" type="checkbox" :value="group.id" :disabled="busy" /> {{ group.name }}</label>
      </div>
      <small v-if="!hasIndexedPdf">成功索引至少一份 PDF 后，即可发布。</small>
      <div class="course-actions">
        <button type="button" :disabled="busy || !classIds.length || !hasIndexedPdf" @click="publish(true)">
          {{ selected.status === 'active' ? '更新发布班级' : '发布课程' }}
        </button>
        <button v-if="selected.status === 'active'" type="button" :disabled="busy" @click="publish(false)">撤回课程</button>
        <a v-if="selected.status === 'active'" :href="appPath(`/knowledge-base?course=${selected.id}`)">打开课程知识库</a>
      </div>
    </section>
    <CoursePdfPreview ref="preview" />
  </section>
</template>

<style scoped>
.teaching-courses { padding: clamp(16px, 3vw, 28px); border: 1px solid #d8e9e2; border-radius: 22px; background: #fff; }
.teaching-courses header, .course-actions { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
.teaching-courses header { justify-content: space-between; }
.course-management-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr)); gap: 16px; margin-block: 16px; }
.course-panel { display: grid; gap: 10px; align-content: start; padding: 14px; border: 1px solid #d8e9e2; border-radius: 12px; }
.course-panel h3, .course-panel p { margin: 0; }
.classroom-controls summary { cursor: pointer; }
.review-course { margin-block: 14px; background: #eef7f3; }
.review-course button:not(:disabled) { color: #fff; background: #105e53; border-color: #105e53; }
.course-panel button { justify-self: start; }
.course-field { display: grid; gap: 6px; }
select, textarea, input:not([type='checkbox']) { max-width: 100%; padding: 8px; border: 1px solid #a8c8be; border-radius: 8px; font: inherit; }
button { padding: 7px 12px; border: 1px solid #a8c8be; border-radius: 8px; background: #eef7f3; color: #155b4c; font: inherit; cursor: pointer; }
button:disabled { opacity: .6; cursor: default; }
.is-error { color: #a32d35; }
.course-documents, .file-results { margin: 0; padding: 0; overflow-wrap: anywhere; list-style: none; }
.course-documents li { display: grid; gap: 6px; padding: 8px 0; border-top: 1px solid #d8e9e2; }
</style>
