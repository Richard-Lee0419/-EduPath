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
  <section class="page-hero page-enter">
    <span v-if="kicker" class="page-hero__kicker">{{ kicker }}</span>
    <h1 class="page-hero__title">{{ title }}</h1>
    <p v-if="description" class="page-hero__description">{{ description }}</p>
    <div v-if="$slots.actions" class="page-hero__actions">
      <slot name="actions" />
    </div>
    <slot />
  </section>
</template>
