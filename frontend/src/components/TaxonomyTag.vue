<script setup lang="ts">
type TagTone = 'default' | 'primary' | 'resource' | 'weak' | 'data';

withDefaults(
  defineProps<{
    tone?: TagTone;
  }>(),
  {
    tone: 'default',
  },
);
</script>

<template>
  <span class="taxonomy-tag" :class="`taxonomy-tag--${tone}`">
    <slot />
  </span>
</template>
