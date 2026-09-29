<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { getCourses, getKnowledgePoints } from '../api/course';
import type { Course, KnowledgePoint } from '@/types/api';

const courses = ref<Course[]>([]);
const selectedCourseId = ref<number | null>(null);
const knowledgePoints = ref<KnowledgePoint[]>([]);
const loading = ref(true);
const errorMessage = ref('');

const loadCourses = async () => {
  loading.value = true;
  errorMessage.value = '';
  try {
    courses.value = await getCourses();
    selectedCourseId.value = courses.value[0]?.id ?? null;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '课程数据加载失败';
  } finally {
    loading.value = false;
  }
};

watch(selectedCourseId, async (courseId) => {
  if (!courseId) {
    knowledgePoints.value = [];
    return;
  }
  try {
    knowledgePoints.value = await getKnowledgePoints(courseId);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '知识点加载失败';
  }
});

onMounted(loadCourses);
</script>

<template>
  <section class="page-section">
    <h2>课程知识</h2>
    <p v-if="loading">正在读取课程目录...</p>
    <p v-else-if="errorMessage">{{ errorMessage }}</p>
    <template v-else>
      <div class="course-pills">
        <button
          v-for="course in courses"
          :key="course.id"
          :class="{ active: selectedCourseId === course.id }"
          type="button"
          @click="selectedCourseId = course.id"
        >
          {{ course.name }}
        </button>
      </div>
      <article v-for="point in knowledgePoints" :key="point.id" class="interactive-card" style="margin-top: 14px; padding: 18px;">
        <strong>{{ point.name }}</strong>
        <p v-if="point.children?.length">包含 {{ point.children.length }} 个子知识点</p>
      </article>
    </template>
  </section>
</template>
