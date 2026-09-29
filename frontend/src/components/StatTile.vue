<script setup lang="ts">
defineProps<{
  label: string;
  value: string;
  detail: string;
  tone: 'teal' | 'amber' | 'coral' | 'blue';
}>();
</script>

<template>
  <section class="stat-tile" :class="`tone-${tone}`">
    <span>{{ label }}</span>
    <strong>{{ value }}</strong>
    <small>{{ detail }}</small>
  </section>
</template>
