<script setup lang="ts">
import TaxonomyTag from './TaxonomyTag.vue';

withDefaults(
  defineProps<{
    kicker?: string;
    title: string;
    status?: string;
  }>(),
  {
    kicker: '',
    status: '',
  },
);
</script>

<template>
  <aside class="insight-panel">
    <div class="insight-panel__header">
      <div>
        <span v-if="kicker" class="insight-panel__kicker">{{ kicker }}</span>
        <h3 class="insight-panel__title">{{ title }}</h3>
      </div>
      <TaxonomyTag v-if="status" tone="primary">{{ status }}</TaxonomyTag>
    </div>
    <div class="insight-panel__body">
      <slot />
    </div>
  </aside>
</template>
