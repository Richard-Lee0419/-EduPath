<script setup lang="ts">
withDefaults(
  defineProps<{
    label?: string;
    hint?: string;
  }>(),
  {
    label: '输入学习目标或问题',
    hint: 'AI 将结合画像、知识证据和学习路径生成建议',
  },
);
</script>

<template>
  <div class="ai-command">
    <div class="ai-command__content">
      <span class="ai-command__label">{{ label }}</span>
      <small class="ai-command__hint">{{ hint }}</small>
    </div>
    <slot name="action" />
  </div>
</template>
