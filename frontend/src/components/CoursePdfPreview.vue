<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { getCoursePdf } from '@/api/kb';

const dialog = ref<HTMLDialogElement | null>(null);
const source = ref('');
const title = ref('');
const pageNumber = ref(1);
const error = ref('');
const loading = ref(false);
let version = 0;

function clearPreview() {
  version += 1;
  if (source.value) URL.revokeObjectURL(source.value);
  source.value = '';
}

async function open(documentId: number, page = 1, label = '课程 PDF 原文') {
  clearPreview();
  const requestVersion = version;
  title.value = label;
  pageNumber.value = page;
  error.value = '';
  loading.value = true;
  if (dialog.value && !dialog.value.open) dialog.value.showModal();
  try {
    const file = await getCoursePdf(documentId);
    if (version === requestVersion) source.value = URL.createObjectURL(file);
  } catch (failure) {
    if (version === requestVersion) error.value = failure instanceof Error ? failure.message : '原文加载失败';
  } finally {
    if (version === requestVersion) loading.value = false;
  }
}

defineExpose({ open });
onBeforeUnmount(clearPreview);
</script>

<template>
  <dialog ref="dialog" class="course-pdf-dialog" aria-label="课程 PDF 原文" @close="clearPreview">
    <form method="dialog">
      <strong>{{ title }} · 第 {{ pageNumber }} 页</strong>
      <button type="submit">关闭原文</button>
    </form>
    <p v-if="loading" role="status">正在读取原文…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <iframe v-else-if="source" :src="`${source}#page=${pageNumber}`" title="课程 PDF 原文" />
  </dialog>
</template>

<style scoped>
.course-pdf-dialog { width: min(92vw, 960px); border: 1px solid #9abcb3; border-radius: 12px; }
.course-pdf-dialog::backdrop { background: #102c29a6; }
.course-pdf-dialog form { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.course-pdf-dialog iframe { display: block; width: 100%; height: min(70vh, 850px); margin-top: 12px; border: 0; }
</style>
