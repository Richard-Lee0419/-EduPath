<script setup lang="ts">
withDefaults(
  defineProps<{
    kicker?: string;
    title?: string;
    description?: string;
    interactive?: boolean;
  }>(),
  {
    kicker: '',
    title: '',
    description: '',
    interactive: true,
  },
);
</script>

<template>
  <article class="studio-card" :class="{ 'card-hover-lift': interactive }">
    <div v-if="kicker || title || description || $slots.meta" class="studio-card__header">
      <div class="studio-card__copy">
        <span v-if="kicker" class="studio-card__kicker">{{ kicker }}</span>
        <h3 v-if="title" class="studio-card__title">{{ title }}</h3>
        <p v-if="description" class="studio-card__description">{{ description }}</p>
      </div>
      <slot name="meta" />
    </div>
    <slot />
  </article>
</template>
