<script setup lang="ts">
import type { User } from '../types';

defineProps<{
  user: User;
}>();

defineEmits<{
  open: [];
}>();
</script>

<template>
  <button class="user-avatar-button nav-item-hover" type="button" title="进入个人中心" @click="$emit('open')">
    <span class="user-avatar-button__glyph" aria-hidden="true">
      <span class="user-avatar-button__head"></span>
      <span class="user-avatar-button__body"></span>
    </span>
    <span class="sr-only">进入 {{ user.name }} 的个人中心</span>
  </button>
</template>
