<script setup lang="ts">
withDefaults(
  defineProps<{
    kicker?: string;
    title: string;
    description?: string;
  }>(),
  {
    kicker: '',
    description: '',
  },
);
</script>

<template>
  <section class="empty-state">
    <div class="empty-state__inner">
      <span v-if="kicker" class="empty-state__kicker">{{ kicker }}</span>
      <h2 class="empty-state__title">{{ title }}</h2>
      <p v-if="description" class="empty-state__description">{{ description }}</p>
      <slot />
    </div>
  </section>
</template>
