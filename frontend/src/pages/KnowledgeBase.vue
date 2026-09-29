<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getCourses } from '../api/course';
import { listKnowledgeDocuments, searchKnowledgeBase, uploadKnowledgeDocument } from '../api/kb';
import SplitText from '../components/SplitText.vue';
import type { KnowledgeCorpusSummary } from '../api/kb';
import type { User } from '../types';

const props = defineProps<{
  user?: User;
}>();

const selectedCourseId = ref<number | null>(null);
const courses = ref<Array<{ id: number; name: string }>>([]);
const evidenceSnippets = ref<
  Array<{
    source: string;
    chapter: string;
    tag: string;
    quote: string;
    knowledgePoint: string;
    licenseStatus: string;
    documentType: string;
    containsExamples: boolean;
  }>
>([]);
const documents = ref<Array<{ title: string; state: string; tone: 'doc' | 'slide' | 'task'; builtIn?: boolean }>>([]);
const searchQuery = ref('');
const corpusSummary = ref<KnowledgeCorpusSummary | null>(null);
const loading = ref(true);
const uploading = ref(false);
const errorMessage = ref('');
const fileInput = ref<HTMLInputElement | null>(null);

const canManageKnowledgeBase = computed(() => props.user?.role === 'teacher' || props.user?.role === 'admin');
const indexedCount = computed(() => documents.value.filter((doc) => doc.state.includes('indexed') || doc.state.includes('已索引')).length);
const searchableMaterialCount = computed(() => {
  if (indexedCount.value > 0) return indexedCount.value;
  return new Set(evidenceSnippets.value.map((item) => item.source || item.chapter)).size;
});
const documentRows = computed(() => {
  if (documents.value.length) return documents.value;
  return evidenceSnippets.value.map((item, index) => ({
    title: item.chapter,
    state: `内置课程语料 / ${item.tag}`,
    tone: (index % 3 === 0 ? 'doc' : index % 3 === 1 ? 'slide' : 'task') as 'doc' | 'slide' | 'task',
    builtIn: true,
  }));
});
const graphNodes = computed(() => {
  const titles = evidenceSnippets.value.map((item) => item.chapter).filter(Boolean);
  const root = selectedCourseName.value ? `${selectedCourseName.value}知识图谱` : '知识图谱';
  const merged = titles.filter(Boolean);
  return [root, ...Array.from(new Set(merged)).slice(0, 8)];
});
const selectedCourseName = computed(() => courses.value.find((course) => course.id === selectedCourseId.value)?.name || '');
const graphBranches = computed(() => [
  {
    title: graphNodes.value[1] || '暂无证据节点',
    relation: '前置概念',
    children: [graphNodes.value[4], graphNodes.value[5]].filter(Boolean),
    tone: 'good',
  },
  {
    title: graphNodes.value[2] || '暂无证据节点',
    relation: '核心主题',
    children: [graphNodes.value[6], graphNodes.value[7]].filter(Boolean),
    tone: 'alert',
  },
  {
    title: graphNodes.value[3] || '暂无证据节点',
    relation: '迁移应用',
    children: [graphNodes.value[8]].filter(Boolean),
    tone: 'weak',
  },
]);
const graphStats = computed(() => [
  `${graphBranches.value.length} 条主干`,
  `${graphBranches.value.reduce((sum, item) => sum + item.children.length, 0)} 个子节点`,
  `${evidenceSnippets.value.length} 条证据`,
]);
const corpusModeLabel = computed(() => {
  if (corpusSummary.value?.mode === 'course_corpus') return '真实课程语料';
  if (corpusSummary.value?.mode === 'builtin_demo') return '内置演示语料';
  return '状态未知';
});

const loadKnowledgeBase = async () => {
  if (!selectedCourseId.value) return;
  loading.value = true;
  errorMessage.value = '';
  try {
    const [docRows, search] = await Promise.all([
      listKnowledgeDocuments({ courseId: selectedCourseId.value }),
      searchKnowledgeBase(
        selectedCourseId.value,
        searchQuery.value.trim() || selectedCourseName.value || '课程知识',
        6,
      ),
    ]);
    documents.value = docRows.map((doc, index) => ({
      title: doc.filename,
      state: `${doc.parse_status} / ${doc.index_status}`,
      tone: index % 3 === 0 ? 'doc' : index % 3 === 1 ? 'slide' : 'task',
    }));
    corpusSummary.value = search.corpus;
    evidenceSnippets.value = search.results.map((item) => ({
      source: item.source,
      chapter: item.title,
      tag: `score ${Math.round(item.score * 100)}%`,
      quote: item.content,
      knowledgePoint: item.knowledge_point,
      licenseStatus: item.license_status,
      documentType: item.document_type,
      containsExamples: item.contains_examples,
    }));
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '知识库加载失败';
  } finally {
    loading.value = false;
  }
};

const submitSearch = async () => {
  await loadKnowledgeBase();
};

const selectCourse = async (courseId: number) => {
  selectedCourseId.value = courseId;
  await loadKnowledgeBase();
};

const triggerUpload = () => {
  if (!canManageKnowledgeBase.value) return;
  fileInput.value?.click();
};

const handleUpload = async (event: Event) => {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (!selectedCourseId.value) {
    errorMessage.value = '课程列表尚未加载，无法上传资料';
    input.value = '';
    return;
  }
  uploading.value = true;
  errorMessage.value = '';
  try {
    await uploadKnowledgeDocument(selectedCourseId.value, file);
    await loadKnowledgeBase();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '上传失败';
  } finally {
    uploading.value = false;
    input.value = '';
  }
};

onMounted(async () => {
  try {
    courses.value = await getCourses();
    selectedCourseId.value = courses.value[0]?.id ?? null;
    await loadKnowledgeBase();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '课程数据加载失败';
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="kb-page ai-page-bg page-enter">
    <section class="kb-hero">
      <div>
        <SplitText tag="h1" text="课程知识库" />
        <p>AI 回答与资源生成均基于课程知识库。</p>
      </div>
      <button v-if="canManageKnowledgeBase" class="primary-upload hover-scale" type="button" :disabled="uploading" @click="triggerUpload">
        {{ uploading ? '上传中' : '上传课程资料' }}
      </button>
      <input
        v-if="canManageKnowledgeBase"
        ref="fileInput"
        class="sr-only"
        type="file"
        accept=".md,.markdown,.txt,.pdf,.docx,.pptx"
        @change="handleUpload"
      />
    </section>

    <div class="course-pills">
      <button
        v-for="course in courses"
        :key="course.id"
        :class="{ active: selectedCourseId === course.id }"
        type="button"
        @click="selectCourse(course.id)"
      >
        {{ course.name }}
      </button>
    </div>

    <form class="kb-search interactive-card" @submit.prevent="submitSearch">
      <label for="kb-query">检索真实课程知识</label>
      <div class="kb-search-row">
        <input
          id="kb-query"
          v-model="searchQuery"
          type="search"
          maxlength="500"
          placeholder="例如：组相联 Cache 如何计算组号？"
        />
        <button type="submit" :disabled="loading || !selectedCourseId">
          {{ loading ? '检索中' : '检索证据' }}
        </button>
      </div>
      <p>当前：{{ corpusModeLabel }} · {{ corpusSummary?.external_documents ?? 0 }} 份外部资料 · {{ corpusSummary?.chunks ?? 0 }} 个语义片段</p>
    </form>

    <section v-if="errorMessage" class="kb-error interactive-card">{{ errorMessage }}</section>

    <section class="kb-layout">
      <main class="kb-main">
        <article class="knowledge-graph-card interactive-card">
          <div class="graph-title">
            <span class="graph-icon"></span>
            <h2>知识图谱</h2>
            <button type="button" aria-label="放大知识图谱">⌕</button>
          </div>

          <div class="graph-canvas">
            <div class="graph-root">
              <strong>{{ graphNodes[0] }}</strong>
              <span v-for="item in graphStats" :key="item">{{ item }}</span>
            </div>
            <div class="graph-branches">
              <article v-for="branch in graphBranches" :key="branch.title" class="graph-branch" :class="`is-${branch.tone}`">
                <div class="graph-branch-main">
                  <small>{{ branch.relation }}</small>
                  <strong>{{ branch.title }}</strong>
                </div>
                <div class="graph-child-list">
                  <span v-for="child in branch.children" :key="child">{{ child }}</span>
                </div>
              </article>
            </div>
          </div>
        </article>

        <article class="evidence-card interactive-card">
          <div class="section-title">
            <span></span>
            <h2>证据片段</h2>
          </div>
          <p v-if="loading">正在检索知识库证据...</p>
          <article v-for="item in evidenceSnippets" :key="item.quote" class="quote-snippet">
            <div class="quote-tags">
              <span>{{ item.source }}</span>
              <span>{{ item.chapter }}</span>
              <span>{{ item.knowledgePoint }}</span>
              <span v-if="item.containsExamples">含例题</span>
              <em>{{ item.tag }}</em>
            </div>
            <p>“{{ item.quote }}”</p>
            <small class="evidence-meta">{{ item.documentType }} · 授权状态 {{ item.licenseStatus }}</small>
          </article>
          <p v-if="!loading && !evidenceSnippets.length">暂无检索证据，请上传课程资料或稍后重试。</p>
        </article>
      </main>

      <aside class="kb-side">
        <article class="system-card interactive-card">
          <h2>系统状态</h2>
          <div class="system-metric">
            <span>可检索资料</span>
            <strong>{{ searchableMaterialCount }}</strong>
          </div>
          <div class="system-metric">
            <span>检索证据</span>
            <strong>{{ evidenceSnippets.length }}</strong>
          </div>
          <ul>
            <li>语料模式：{{ corpusModeLabel }}</li>
            <li>外部资料：{{ corpusSummary?.external_documents ?? 0 }} 份，语义片段 {{ corpusSummary?.chunks ?? 0 }} 个</li>
            <li>检索状态：本次返回 {{ evidenceSnippets.length }} 条证据</li>
            <li>文档状态：已读取 {{ documents.length }} 条文档记录</li>
          </ul>
        </article>

        <article class="document-card interactive-card">
          <div class="document-head">
            <h2>文档列表</h2>
            <button type="button" aria-label="筛选文档">≡</button>
          </div>
          <div class="document-list">
            <article v-for="doc in documentRows" :key="doc.title" class="document-row" :class="[`doc-${doc.tone}`, { 'is-built-in': doc.builtIn }]">
              <span class="doc-icon"></span>
              <div>
                <strong>{{ doc.title }}</strong>
                <small>{{ doc.state }}</small>
              </div>
              <button type="button" aria-label="更多操作">⋮</button>
            </article>
          </div>
          <button class="secondary-wide hover-scale" type="button">查看所有文档</button>
        </article>
      </aside>
    </section>
  </div>
</template>

<style scoped>
.kb-page {
  min-height: calc(100vh - 72px);
  overflow-x: hidden;
  padding: 44px 70px 60px;
  color: var(--color-ink);
}

.kb-hero {
  align-items: center;
  display: flex;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 34px;
}

.kb-hero h1 {
  font-size: clamp(34px, 4vw, 48px);
  margin: 0 0 12px;
}

.kb-hero p,
.quote-snippet p,
.system-card li {
  color: rgba(31, 54, 49, 0.7);
  line-height: 1.8;
  margin: 0;
}

.primary-upload {
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #007966, #0b8f7d);
  color: #fff;
  cursor: pointer;
  font-weight: 900;
  min-height: 54px;
  padding: 0 32px;
  box-shadow: 0 18px 30px rgba(0, 121, 102, 0.24);
}

.primary-upload:disabled {
  cursor: wait;
  opacity: 0.72;
}

.kb-error {
  border: 1px solid rgba(223, 91, 69, 0.22);
  border-radius: 14px;
  background: rgba(255, 244, 240, 0.86);
  color: #df5b45;
  font-weight: 800;
  margin-bottom: 24px;
  padding: 16px 20px;
}

.course-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 32px;
}

.course-pills button {
  border: 1px solid rgba(31, 54, 49, 0.12);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.78);
  color: rgba(31, 54, 49, 0.72);
  cursor: pointer;
  font-weight: 800;
  min-width: 160px;
  padding: 13px 22px;
}

.course-pills .active {
  background: rgba(0, 121, 102, 0.16);
  color: var(--color-primary);
}

.kb-search {
  border: 1px solid rgba(0, 121, 102, 0.14);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: var(--shadow-card);
  margin-bottom: 28px;
  padding: 20px 24px;
}

.kb-search label {
  display: block;
  font-weight: 900;
  margin-bottom: 12px;
}

.kb-search-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
}

.kb-search input {
  border: 1px solid rgba(31, 54, 49, 0.16);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--color-ink);
  min-height: 48px;
  padding: 0 16px;
}

.kb-search button {
  border: 0;
  border-radius: 12px;
  background: var(--color-primary);
  color: #fff;
  cursor: pointer;
  font-weight: 900;
  min-width: 120px;
  padding: 0 20px;
}

.kb-search button:disabled {
  cursor: wait;
  opacity: 0.66;
}

.kb-search > p {
  color: rgba(31, 54, 49, 0.64);
  font-size: 13px;
  margin: 12px 0 0;
}

.kb-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 32px;
}

.kb-main,
.kb-side {
  display: grid;
  gap: 28px;
  align-content: start;
  min-width: 0;
}

.knowledge-graph-card,
.evidence-card,
.system-card,
.document-card {
  min-width: 0;
  border: 1px solid rgba(16, 94, 83, 0.1);
  background: rgba(255, 255, 255, 0.78);
  border-radius: 18px;
  box-shadow: var(--shadow-card);
  backdrop-filter: blur(18px);
  padding: 28px;
}

.graph-title,
.document-head,
.section-title {
  align-items: center;
  display: flex;
  min-width: 0;
  gap: 12px;
}

.graph-title h2,
.document-head h2,
.section-title h2,
.system-card h2 {
  font-size: 28px;
  margin: 0;
}

.graph-title button,
.document-head button,
.document-row button {
  border: 0;
  background: transparent;
  color: var(--color-primary);
  cursor: pointer;
  font-size: 22px;
  margin-left: auto;
}

.graph-icon,
.section-title span {
  border-radius: 9px;
  display: block;
  height: 24px;
  width: 24px;
}

.graph-icon {
  background: conic-gradient(from 120deg, #007966, #9fded4, #007966);
}

.section-title span {
  background: linear-gradient(135deg, #007966, #62c9b9);
}

.graph-canvas {
  align-items: stretch;
  border: 1px solid rgba(31, 54, 49, 0.1);
  border-radius: 14px;
  display: grid;
  grid-template-columns: minmax(190px, 0.34fr) minmax(0, 1fr);
  gap: 24px;
  min-height: 360px;
  margin-top: 28px;
  background:
    linear-gradient(90deg, rgba(0, 121, 102, 0.05) 1px, transparent 1px),
    linear-gradient(180deg, rgba(0, 121, 102, 0.04) 1px, transparent 1px),
    linear-gradient(135deg, rgba(255, 255, 255, 0.76), rgba(251, 247, 241, 0.64));
  background-size: 34px 34px, 34px 34px, auto;
  padding: 28px;
}

.graph-root,
.graph-branch-main,
.graph-child-list span {
  border-radius: 10px;
  border: 1px solid rgba(31, 54, 49, 0.12);
  font-weight: 900;
}

.graph-root {
  position: relative;
  display: grid;
  align-content: center;
  gap: 10px;
  min-height: 100%;
  background: rgba(0, 121, 102, 0.14);
  color: var(--color-primary);
  padding: 22px;
}

.graph-root::after {
  position: absolute;
  top: 50%;
  right: 0;
  width: 20px;
  height: 2px;
  background: rgba(0, 121, 102, 0.32);
  content: '';
}

.graph-root strong {
  font-size: 22px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.graph-root span {
  max-width: 100%;
  width: fit-content;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  color: rgba(31, 54, 49, 0.68);
  font-size: 12px;
  padding: 6px 9px;
}

.graph-branches {
  display: grid;
  gap: 18px;
}

.graph-branch {
  position: relative;
  display: grid;
  grid-template-columns: minmax(170px, 0.38fr) minmax(0, 1fr);
  gap: 18px;
  align-items: center;
}

.graph-branch::before {
  position: absolute;
  top: 50%;
  left: -24px;
  width: 24px;
  height: 2px;
  background: rgba(31, 54, 49, 0.16);
  content: '';
}

.graph-branch-main {
  display: grid;
  min-width: 0;
  gap: 8px;
  padding: 16px 18px;
}

.graph-branch-main strong,
.graph-child-list span,
.quote-snippet p,
.document-row strong,
.document-row small,
.system-card li {
  overflow-wrap: anywhere;
}

.graph-branch-main small {
  color: rgba(31, 54, 49, 0.58);
}

.graph-child-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.graph-child-list span {
  background: rgba(255, 255, 255, 0.82);
  color: rgba(31, 54, 49, 0.78);
  padding: 11px 14px;
}

.graph-branch.is-good .graph-branch-main {
  background: rgba(0, 121, 102, 0.18);
  color: var(--color-primary);
}

.graph-branch.is-alert .graph-branch-main {
  border-color: rgba(255, 104, 77, 0.5);
  color: #ff6048;
}

.graph-branch.is-weak .graph-branch-main {
  border-color: rgba(255, 104, 77, 0.58);
  color: #ff6048;
}

.quote-snippet {
  border: 1px solid rgba(31, 54, 49, 0.1);
  border-radius: 12px;
  margin-top: 18px;
  padding: 18px;
  background: rgba(255, 255, 255, 0.66);
}

.quote-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.quote-tags span,
.quote-tags em {
  border-radius: 999px;
  background: rgba(31, 54, 49, 0.07);
  color: rgba(31, 54, 49, 0.72);
  font-size: 12px;
  font-style: normal;
  font-weight: 800;
  padding: 6px 10px;
}

.quote-tags em {
  background: rgba(255, 104, 77, 0.15);
  color: #ff6048;
}

.evidence-meta {
  color: rgba(31, 54, 49, 0.56);
  display: block;
  margin-top: 12px;
}

.system-card {
  border-top: 4px solid #3b71bb;
}

.system-metric {
  align-items: center;
  border-bottom: 1px solid rgba(31, 54, 49, 0.1);
  display: flex;
  justify-content: space-between;
  padding: 24px 0;
}

.system-metric strong {
  color: #3768b1;
  font-size: 28px;
}

.system-card ul {
  display: grid;
  gap: 16px;
  margin: 24px 0 0;
  padding-left: 18px;
}

.system-card li::marker {
  color: var(--color-primary);
}

.document-list {
  display: grid;
  gap: 12px;
  margin: 20px 0;
}

.document-row {
  align-items: center;
  border: 1px solid rgba(31, 54, 49, 0.1);
  border-radius: 12px;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) 24px;
  gap: 12px;
  padding: 14px;
}

.doc-icon {
  border-radius: 10px;
  display: block;
  height: 38px;
  width: 38px;
  background: rgba(247, 153, 57, 0.13);
  border: 2px solid #f28a18;
}

.doc-task .doc-icon {
  border-color: #9aa3a0;
  background: rgba(31, 54, 49, 0.06);
}

.document-row strong,
.document-row small {
  display: block;
}

.document-row small {
  color: rgba(31, 54, 49, 0.62);
  margin-top: 4px;
}

.secondary-wide {
  border: 1px solid rgba(31, 54, 49, 0.12);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.8);
  cursor: pointer;
  font-weight: 800;
  min-height: 44px;
  width: 100%;
}

@media (max-width: 980px) {
  .kb-page {
    padding: 30px 22px;
  }

  .kb-layout {
    grid-template-columns: 1fr;
  }

  .kb-search-row {
    grid-template-columns: 1fr;
  }

  .kb-search button {
    min-height: 46px;
  }

  .graph-canvas {
    grid-template-columns: 1fr;
  }

  .graph-root::after,
  .graph-branch::before {
    display: none;
  }

  .graph-branch {
    grid-template-columns: 1fr;
  }
}
</style>
