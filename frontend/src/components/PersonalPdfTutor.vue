<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import {
  askPersonalPdf, deletePersonalPdf, getPersonalPdf, listPersonalPdfs, uploadPersonalPdf,
  type PersonalPdf, type PersonalPdfAnswer,
} from '@/api/personalPdf';
import { loadReviewSample, reviewSampleName, reviewSampleQuestion } from '@/api/reviewSample';

defineProps<{ reviewDemo?: boolean }>();

const documents = ref<PersonalPdf[]>([]);
const selectedId = ref('');
const selected = computed(() => documents.value.find((document) => document.id === selectedId.value));
const input = ref<HTMLInputElement | null>(null);
const file = ref<File | null>(null);
const question = ref('');
const answer = ref<PersonalPdfAnswer | null>(null);
const busy = ref('');
const error = ref('');
const dialog = ref<HTMLDialogElement | null>(null);
const preview = ref('');
const previewPage = ref(1);
let unmounted = false;

async function run(action: string, work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = action;
  error.value = '';
  try {
    await work();
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '个人资料操作失败，请重试';
  } finally {
    busy.value = '';
  }
}

function refresh() {
  return run('list', async () => {
    documents.value = await listPersonalPdfs();
    if (!documents.value.some((document) => document.id === selectedId.value)) {
      selectedId.value = documents.value[0]?.id ?? '';
    }
  });
}

function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}

async function addPdf(pdf: File) {
  if (pdf.size > 8 * 1024 * 1024) throw new Error('PDF 文件不能超过 8 MiB');
  const document = await uploadPersonalPdf(pdf);
  documents.value = [document, ...documents.value.filter((item) => item.id !== document.id)];
  selectedId.value = document.id;
}

function upload() {
  const picked = file.value;
  if (!picked) return;
  return run('upload', async () => {
    await addPdf(picked);
    file.value = null;
    if (input.value) input.value.value = '';
  });
}

function openSample() {
  return run('sample', async () => {
    const existing = documents.value.find((document) => document.filename === reviewSampleName);
    if (existing) selectedId.value = existing.id;
    else await addPdf(await loadReviewSample());
    question.value = reviewSampleQuestion;
  });
}

async function ask() {
  const text = question.value.trim();
  const id = selectedId.value;
  if (!id || text.length < 2 || busy.value) return;
  answer.value = null;
  await run('ask', async () => {
    answer.value = await askPersonalPdf(id, text);
  });
}

function releasePreview() {
  if (preview.value) URL.revokeObjectURL(preview.value);
  preview.value = '';
}

async function openPage(page: number) {
  const id = selectedId.value;
  if (!id) return;
  await run('preview', async () => {
    const blob = await getPersonalPdf(id);
    if (unmounted || !dialog.value) return;
    releasePreview();
    preview.value = URL.createObjectURL(blob);
    previewPage.value = page;
    dialog.value.showModal();
  });
}

async function remove() {
  const document = selected.value;
  if (!document || !window.confirm(`删除「${document.filename}」及其页码索引？`)) return;
  await run('delete', async () => {
    await deletePersonalPdf(document.id);
    documents.value = documents.value.filter((item) => item.id !== document.id);
    selectedId.value = documents.value[0]?.id ?? '';
  });
}

watch(selectedId, () => { answer.value = null; });
onMounted(refresh);
onUnmounted(() => {
  unmounted = true;
  releasePreview();
});
</script>

<template>
  <main class="personal-pdf interactive-card" aria-labelledby="personal-pdf-title">
    <h1 id="personal-pdf-title">个人 PDF 辅导</h1>
    <p>上传你的课程讲义，选择一份资料后提问。资料仅对你的账号可见。</p>
    <div v-if="reviewDemo" class="pdf-actions">
      <button type="button" :disabled="Boolean(busy)" @click="openSample">
        {{ busy === 'sample' ? '正在准备示例讲义…' : '导入示例 PDF 并填入问题' }}
      </button>
    </div>

    <form class="pdf-form" @submit.prevent="upload">
      <label for="personal-pdf-file">上传课程 PDF</label>
      <input id="personal-pdf-file" ref="input" type="file" accept=".pdf,application/pdf"
        :disabled="Boolean(busy)" @change="chooseFile" />
      <small>支持可复制文字的 PDF，最多 8 MiB、100 页。</small>
      <button type="submit" :disabled="!file || Boolean(busy)">{{ busy === 'upload' ? '正在提取页文本…' : '上传 PDF' }}</button>
    </form>

    <p v-if="error" role="alert" class="pdf-error">{{ error }}</p>
    <button v-if="error && !documents.length" type="button" :disabled="Boolean(busy)" @click="refresh">刷新资料列表</button>
    <p v-if="busy === 'list'" role="status">正在读取个人资料…</p>
    <section v-if="documents.length" class="pdf-form" aria-label="我的课程资料">
      <label for="personal-pdf-choice">提问使用的资料</label>
      <select id="personal-pdf-choice" v-model="selectedId" :disabled="Boolean(busy)">
        <option v-for="document in documents" :key="document.id" :value="document.id">
          {{ document.filename }}（{{ document.page_count }} 页）
        </option>
      </select>
      <div class="pdf-actions">
        <button type="button" :disabled="Boolean(busy)" @click="openPage(1)">打开原文</button>
        <button type="button" :disabled="Boolean(busy)" @click="remove">删除此资料</button>
      </div>
    </section>

    <form v-if="selected" class="pdf-form" @submit.prevent="ask">
      <label for="personal-pdf-question">想弄懂哪一部分？</label>
      <textarea id="personal-pdf-question" v-model="question" rows="3" minlength="2" maxlength="500"
        placeholder="例如：讲义里递归终止的条件是什么？" :disabled="Boolean(busy)" />
      <button type="submit" :disabled="question.trim().length < 2 || Boolean(busy)">
        {{ busy === 'ask' ? '正在查找相关段落…' : '按这份 PDF 提问' }}
      </button>
    </form>

    <section v-if="answer" class="pdf-result" aria-label="个人 PDF 回答" aria-live="polite">
      <h2>{{ answer.outcome === 'answered' ? '资料依据与学习提示' : '暂时无法回答' }}</h2>
      <p>{{ answer.guidance }}</p>
      <article v-for="item in answer.evidence" :key="item.page">
        <button type="button" :disabled="Boolean(busy)" @click="openPage(item.page)">查看 PDF 第 {{ item.page }} 页</button>
        <blockquote>{{ item.excerpt }}</blockquote>
      </article>
    </section>

    <dialog ref="dialog" class="pdf-preview" aria-labelledby="personal-pdf-preview-title" @close="releasePreview">
      <form method="dialog" class="pdf-actions">
        <strong id="personal-pdf-preview-title">{{ selected?.filename }} · 第 {{ previewPage }} 页</strong>
        <button type="submit">关闭预览</button>
      </form>
      <iframe v-if="preview" :src="`${preview}#page=${previewPage}`" title="课程 PDF 原文" />
    </dialog>
  </main>
</template>

<style scoped>
.personal-pdf { min-width: 0; align-self: start; padding: clamp(16px, 3vw, 32px); border-radius: 24px; background: white; border: 1px solid rgba(16, 94, 83, .12); }
h1, h2 { margin-block: 0 10px; }
h2 { font-size: 1rem; }
.pdf-form, .pdf-result { display: grid; gap: 10px; margin-top: 22px; }
.pdf-form > button { justify-self: start; }
.pdf-form select, .pdf-form textarea { width: 100%; border: 1px solid #9abcb3; border-radius: 8px; padding: 8px; font: inherit; }
.pdf-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: space-between; }
button { padding: 8px 12px; border: 1px solid #9abcb3; border-radius: 8px; background: #eef7f3; color: #155b4c; font: inherit; cursor: pointer; }
button:disabled { opacity: .6; cursor: default; }
.pdf-result blockquote { margin: 8px 0 12px; padding-inline-start: 12px; border-inline-start: 2px solid #9abcb3; overflow-wrap: anywhere; }
.pdf-error { color: #9b2929; }
.pdf-preview { width: min(92vw, 960px); border: 1px solid #9abcb3; border-radius: 12px; }
.pdf-preview::backdrop { background: #102c29a6; }
.pdf-preview iframe { display: block; width: 100%; height: min(70vh, 850px); margin-top: 10px; border: 0; }
</style>
