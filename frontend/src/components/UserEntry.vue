<script setup lang="ts">
import type { User, PageKey } from '../types';

defineProps<{
  user: User;
}>();

defineEmits<{
  navigate: [page: PageKey];
  logout: [];
}>();
</script>

<template>
  <aside class="home-user-entry" aria-label="用户个人中心入口">
    <div class="home-user-entry__avatar">{{ user.name.slice(0, 1) }}</div>
    <div class="home-user-entry__copy">
      <span>个人中心</span>
      <strong>{{ user.name }}</strong>
      <small>{{ [user.major, user.grade || user.role].filter(Boolean).join(' · ') }}</small>
    </div>
    <button class="text-action" type="button" @click="$emit('navigate', 'userCenter')">档案</button>
    <button class="studio-logout" type="button" @click="$emit('logout')">退出</button>
  </aside>
</template>
