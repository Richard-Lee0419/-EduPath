<script setup lang="ts">
import { ref } from 'vue';
import authLearningBg from '../assets/images/auth-learning-bg.webp';

const tiltX = ref('0px');
const tiltY = ref('0px');

const handlePointerMove = (event: MouseEvent) => {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  const x = (event.clientX - rect.left) / rect.width - 0.5;
  const y = (event.clientY - rect.top) / rect.height - 0.5;

  tiltX.value = `${x * 18}px`;
  tiltY.value = `${y * 18}px`;
};

const resetTilt = () => {
  tiltX.value = '0px';
  tiltY.value = '0px';
};

const visualTags = [
  { label: '学习画像', tone: 'teal', x: 31, y: 28 },
  { label: '资源生成', tone: 'amber', x: 61, y: 38 },
  { label: '学习数据', tone: 'blue', x: 31, y: 55 },
  { label: '推荐资源', tone: 'amber', x: 62, y: 57 },
  { label: 'AI 导师', tone: 'teal', x: 38, y: 69 },
  { label: '评估报告', tone: 'blue', x: 55, y: 78 },
  { label: '好奇心指数', tone: 'coral', x: 30, y: 82 },
];
</script>

<template>
  <section
    class="auth-visual"
    :style="{ '--tilt-x': tiltX, '--tilt-y': tiltY }"
    @mousemove="handlePointerMove"
    @mouseleave="resetTilt"
  >
    <div class="auth-bubble-stage" aria-hidden="true">
      <img
        class="auth-background-image"
        :src="authLearningBg"
        alt=""
        width="1254"
        height="1254"
        decoding="async"
        fetchpriority="high"
      />

      <span
        v-for="tag in visualTags"
        :key="tag.label"
        class="auth-floating-tag"
        :class="`auth-floating-tag--${tag.tone}`"
        :style="{ '--tag-x': `${tag.x}%`, '--tag-y': `${tag.y}%` }"
      >
        {{ tag.label }}
      </span>
    </div>
  </section>
</template>
