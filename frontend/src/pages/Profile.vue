<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getCurrentProfile } from '../api/profile';
import type { Profile } from '../types';

const profile = ref<Profile | null>(null);
const version = ref(0);
const loading = ref(true);
const errorMessage = ref('');

const rows = computed(() => {
  const item = profile.value;
  if (!item) return [];
  return [
    ['学习目标', item.learningGoal],
    ['薄弱知识点', item.weakPoints.join('、') || '暂无明确薄弱点'],
    ['资源偏好', item.resourcePreference.join('、') || '暂无偏好记录'],
    ['认知风格', item.cognitiveStyle.join('、') || '暂无认知风格记录'],
    ['学习节奏', item.learningPace],
    ['画像置信度', `${Math.round(item.confidenceScore * 100)}%`],
  ];
});

onMounted(async () => {
  try {
    const result = await getCurrentProfile();
    profile.value = result.profile;
    version.value = result.version;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '学习画像加载失败';
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <section class="page-section">
    <h2>学习画像</h2>
    <p v-if="loading">正在读取最新学习画像...</p>
    <p v-else-if="errorMessage">{{ errorMessage }}</p>
    <template v-else-if="profile">
      <p>当前画像版本：v{{ version }}，学生：{{ profile.studentName }}</p>
      <article v-for="[label, value] in rows" :key="label" class="interactive-card" style="margin-top: 14px; padding: 18px;">
        <strong>{{ label }}</strong>
        <p>{{ value }}</p>
      </article>
    </template>
  </section>
</template>
