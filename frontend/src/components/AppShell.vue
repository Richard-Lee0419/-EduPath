<script setup lang="ts">
import { computed } from 'vue';
import type { User, PageKey } from '../types';
import AppTopNav from './AppTopNav.vue';

const props = defineProps<{
  currentPage: PageKey;
  user: User;
}>();

defineEmits<{
  navigate: [page: PageKey];
  exit: [target: PageKey];
  openUserCenter: [];
  logout: [];
}>();

const functionPages: PageKey[] = [
  'learningHome',
  'profile',
  'resources',
  'resourceCenter',
  'learningPath',
  'knowledgeBase',
  'evaluation',
  'tutor',
  'quiz',
  'teacherInsights',
  'taskCenter',
];

const showTopNav = computed(() => functionPages.includes(props.currentPage));
</script>

<template>
  <div class="app-shell app-shell--studio page-enter">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <AppTopNav
      v-if="showTopNav"
      :current-page="currentPage"
      :user="user"
      @navigate="$emit('navigate', $event)"
      @exit="$emit('exit', $event)"
      @open-user-center="$emit('openUserCenter')"
    />

    <main id="main-content" class="main-surface main-surface--studio" :class="{ 'main-surface--function-page': showTopNav }" tabindex="-1">
      <slot />
    </main>
  </div>
</template>
