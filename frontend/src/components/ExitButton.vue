<script setup lang="ts">
import type { PageKey } from '../types';

withDefaults(
  defineProps<{
    target?: PageKey;
    title?: string;
    label?: string;
  }>(),
  {
    target: 'dashboard',
    title: '返回首页',
    label: '',
  },
);

defineEmits<{
  exit: [target: PageKey];
}>();
</script>

<template>
  <button class="exit-button nav-item-hover" type="button" :title="title" @click="$emit('exit', target)">
    <span class="exit-button__icon" aria-hidden="true">
      <span></span>
    </span>
    <span v-if="label" class="exit-button__label">{{ label }}</span>
  </button>
</template>
