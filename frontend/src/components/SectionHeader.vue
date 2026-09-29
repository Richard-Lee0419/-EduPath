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
  <header class="section-header">
    <div class="section-header__copy">
      <span v-if="kicker" class="section-header__kicker">{{ kicker }}</span>
      <h2 class="section-header__title">{{ title }}</h2>
      <p v-if="description" class="section-header__description">{{ description }}</p>
    </div>
    <div v-if="$slots.aside" class="section-header__aside">
      <slot name="aside" />
    </div>
  </header>
</template>
